#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests of installer/install.sh. It builds a fake release (archives, SHA256SUMS) in a temporary directory and runs the real
# install.sh against it.
#
#   installer/tests/test-install.sh            below a temporary root (CRINGLE_INSTALL_ROOT): no root rights needed, nothing
#                                              outside of the temporary directory is touched; no users, no systemctl
#   installer/tests/test-install.sh --system   on the real system, as root: use it only in a throw-away container
#                                              (installer/tests/test-install-container.sh does that)
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
INSTALLER="$HERE/../install.sh"
MODE=staging
[ "${1:-}" = "--system" ] && MODE=system

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILED=0
CHECKS=0

pass() { CHECKS=$((CHECKS + 1)); }
fail() { CHECKS=$((CHECKS + 1)); FAILED=$((FAILED + 1)); echo "  FAIL: $*"; }
ok() { # ok <description> <command...>
    local d="$1"; shift
    if "$@" > /dev/null 2>&1; then pass; else fail "$d"; fi
}
not() { # not <description> <command...>: the command has to fail
    local d="$1"; shift
    if "$@" > /dev/null 2>&1; then fail "$d"; else pass; fi
}
eq() { # eq <description> <expected> <actual>
    if [ "$2" = "$3" ]; then pass; else fail "$1 (expected '$2', got '$3')"; fi
}

# make_release <dir> <version>: <dir>/v<version>/ with a linux archive, a windows archive and SHA256SUMS
make_release() {
    local rel="$1/v$2" src
    src=$(mktemp -d -p "$WORK")
    mkdir -p "$rel" "$src/cringle-$2/bin" "$src/cringle-$2/lib" "$src/cringle-$2/conf"
    printf '%s\n' "$2" > "$src/cringle-$2/VERSION"
    cat > "$src/cringle-$2/bin/cringle" << 'EOF'
#!/bin/sh
d=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
echo "cringle $(cat "$d/VERSION")"
EOF
    printf '#!/bin/sh\nexec sleep 1\n' > "$src/cringle-$2/bin/cringle-daemon"
    printf '#!/bin/sh\nexec sleep 1\n' > "$src/cringle-$2/bin/cringle-management-server"
    chmod 755 "$src/cringle-$2/bin/"*
    tar -czf "$rel/cringle-$2-linux.tar.gz" -C "$src" "cringle-$2"
    printf 'zip of %s\n' "$2" > "$rel/cringle-$2-windows.zip"
    (cd "$rel" && sha256sum "cringle-$2-linux.tar.gz" "cringle-$2-windows.zip" > SHA256SUMS)
    rm -rf "$src"
}

RELEASES="$WORK/releases"
make_release "$RELEASES" 1.0.0
make_release "$RELEASES" 2.0.0
make_release "$RELEASES" 3.0.0-rc.1
# a release with a manipulated archive: the checksum is that of the original
make_release "$RELEASES" 9.9.9
echo "tampered" >> "$RELEASES/v9.9.9/cringle-9.9.9-linux.tar.gz"
# a release without a line for the linux archive
make_release "$RELEASES" 8.8.8
grep -v linux "$RELEASES/v8.8.8/SHA256SUMS" > "$RELEASES/v8.8.8/SUMS" && mv "$RELEASES/v8.8.8/SUMS" "$RELEASES/v8.8.8/SHA256SUMS"
# a native Windows curl (Git for Windows) needs a Windows path in a file URL
if command -v cygpath > /dev/null 2>&1; then BASE="file:///$(cygpath -m "$RELEASES")"; else BASE="file://$RELEASES"; fi

if [ "$MODE" = staging ]; then
    new_root() { ROOT=$(mktemp -d -p "$WORK"); P="$ROOT"; }
    run() { CRINGLE_INSTALL_ROOT="$ROOT" CRINGLE_RELEASE_BASE_URL="$BASE" sh "$INSTALLER" "$@"; }
else
    # there is only one system: every test starts from a removed installation
    new_root() { ROOT=""; P=""; sh "$INSTALLER" --uninstall --purge > /dev/null 2>&1; }
    run() { CRINGLE_RELEASE_BASE_URL="$BASE" sh "$INSTALLER" "$@"; }
    [ "$(id -u)" -eq 0 ] || { echo "--system needs root"; exit 1; }
fi

echo "== install"
new_root
run --release 1.0.0 > "$WORK/out" 2>&1 || { cat "$WORK/out"; fail "install failed"; }
ok "archive unpacked" test -x "$P/opt/cringle/1.0.0/bin/cringle"
eq "current points to the version" "1.0.0" "$(readlink "$P/opt/cringle/current")"
ok "config file" test -f "$P/etc/cringle/cringle.env"
ok "data dir" test -d "$P/var/lib/cringle"
ok "daemon unit" test -f "$P/etc/systemd/system/cringle-daemon.service"
not "no management unit without the switch" test -e "$P/etc/systemd/system/cringle-management.service"
ok "unit starts current/bin" grep -q '^ExecStart=/opt/cringle/current/bin/cringle-daemon ' "$P/etc/systemd/system/cringle-daemon.service"
ok "unit sets CRINGLE_HOME" grep -q '^Environment=CRINGLE_HOME=/var/lib/cringle$' "$P/etc/systemd/system/cringle-daemon.service"
ok "unit enabled" test -L "$P/etc/systemd/system/multi-user.target.wants/cringle-daemon.service"
ok "bin symlink" test -L "$P/usr/local/bin/cringle"
eq "cringle --version" "cringle 1.0.0" "$("$P/usr/local/bin/cringle" --version 2>&1)"
not "no leftovers of the installation in opt" sh -c "ls -A '$P/opt/cringle' | grep -q '^\\.install'"
if [ "$MODE" = system ]; then
    ok "user cringle" getent passwd cringle
    ok "group cringle" getent group cringle
    eq "data dir owner" "cringle:cringle" "$(stat -c %U:%G /var/lib/cringle)"
    eq "config dir group" "root:cringle" "$(stat -c %U:%G /etc/cringle)"
    eq "cringle --version as a normal user" "cringle 1.0.0" "$(su -s /bin/sh nobody -c '/usr/local/bin/cringle --version' 2>&1)"
fi

echo "== a wrong checksum installs nothing"
new_root
not "tampered archive fails" run --release 9.9.9
not "nothing under opt after a failed checksum" test -e "$P/opt/cringle"
not "no unit after a failed checksum" test -e "$P/etc/systemd/system/cringle-daemon.service"
not "missing checksum line fails" run --release 8.8.8
not "nothing under opt after a missing checksum" test -e "$P/opt/cringle"
not "unknown version fails" run --release 7.7.7
not "nothing under opt after a failed download" test -e "$P/opt/cringle"

echo "== a second installation with another version"
new_root
run --release 1.0.0 > /dev/null 2>&1
echo "keep" > "$P/var/lib/cringle/state"
echo "JAVA_HOME=/my/jdk" >> "$P/etc/cringle/cringle.env"
env_before=$(cat "$P/etc/cringle/cringle.env")
run --release 2.0.0 > /dev/null 2>&1 || fail "second install failed"
eq "current moved" "2.0.0" "$(readlink "$P/opt/cringle/current")"
ok "old version kept" test -d "$P/opt/cringle/1.0.0"
eq "cringle --version after the switch" "cringle 2.0.0" "$("$P/usr/local/bin/cringle" --version 2>&1)"
eq "data unchanged" "keep" "$(cat "$P/var/lib/cringle/state")"
eq "config unchanged" "$env_before" "$(cat "$P/etc/cringle/cringle.env")"
not "current is not nested" test -e "$P/opt/cringle/2.0.0/2.0.0"
run --release 2.0.0 > /dev/null 2>&1 || fail "installing the same version again failed"
eq "same version again" "2.0.0" "$(readlink "$P/opt/cringle/current")"
run --release v3.0.0-rc.1 > /dev/null 2>&1 || fail "pre-release failed"
eq "pre-release" "3.0.0-rc.1" "$(readlink "$P/opt/cringle/current")"

echo "== management server"
new_root
run --release 1.0.0 --with-management > /dev/null 2>&1 || fail "install with management failed"
ok "management unit" grep -q '^ExecStart=/opt/cringle/current/bin/cringle-management-server ' "$P/etc/systemd/system/cringle-management.service"
ok "management unit enabled" test -L "$P/etc/systemd/system/multi-user.target.wants/cringle-management.service"
run --release 2.0.0 > /dev/null 2>&1 || fail "upgrade without the switch failed"
ok "management unit survives an upgrade" test -f "$P/etc/systemd/system/cringle-management.service"

echo "== uninstall"
echo "keep" > "$P/var/lib/cringle/state"
run --uninstall > /dev/null 2>&1 || fail "uninstall failed"
not "opt removed" test -e "$P/opt/cringle"
not "daemon unit removed" test -e "$P/etc/systemd/system/cringle-daemon.service"
not "management unit removed" test -e "$P/etc/systemd/system/cringle-management.service"
not "wants link removed" test -L "$P/etc/systemd/system/multi-user.target.wants/cringle-daemon.service"
not "bin symlink removed" test -L "$P/usr/local/bin/cringle"
eq "data kept" "keep" "$(cat "$P/var/lib/cringle/state" 2> /dev/null)"
ok "config kept" test -f "$P/etc/cringle/cringle.env"
[ "$MODE" = system ] && ok "user kept" getent passwd cringle
run --uninstall > /dev/null 2>&1 || fail "uninstalling twice failed"
run --uninstall --purge > /dev/null 2>&1 || fail "purge failed"
not "data removed" test -e "$P/var/lib/cringle"
not "config removed" test -e "$P/etc/cringle"
[ "$MODE" = system ] && not "user removed" getent passwd cringle

echo "== arguments"
new_root
not "--purge alone" run --purge
not "unknown option" run --bogus
not "--release without a value" run --release
not "bad version" run --release "1.0/../x"
not "--uninstall with --release" run --uninstall --release 1.0.0
not "no version with a base url" run
not "nothing written by wrong arguments" test -e "$P/opt/cringle"

echo "$CHECKS checks, $FAILED failed ($MODE mode)"
[ "$FAILED" -eq 0 ]
