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
    # no terminal for the questions unless a test gives one (CRINGLE_INSTALL_TTY)
    run() { CRINGLE_INSTALL_TTY="${CRINGLE_INSTALL_TTY:-/nonexistent}" CRINGLE_INSTALL_ROOT="$ROOT" CRINGLE_RELEASE_BASE_URL="$BASE" sh "$INSTALLER" "$@"; }
else
    # there is only one system: every test starts from a removed installation
    new_root() { ROOT=""; P=""; sh "$INSTALLER" --uninstall --purge > /dev/null 2>&1; }
    run() { CRINGLE_INSTALL_TTY="${CRINGLE_INSTALL_TTY:-/nonexistent}" CRINGLE_RELEASE_BASE_URL="$BASE" sh "$INSTALLER" "$@"; }
    [ "$(id -u)" -eq 0 ] || { echo "--system needs root"; exit 1; }
fi

# the settings file of the daemon (the key-value store, #314): `setting <key>` is one value, `settings` all of them
setting() { sed -n "s/^$1=//p" "$P/var/lib/cringle/config/cringle.conf"; }
settings() { grep -v '^#' "$P/var/lib/cringle/config/cringle.conf" | tr '\n' ' ' | sed 's/ $//'; }

echo "== install"
new_root
run --release 1.0.0 > "$WORK/out" 2>&1 || { cat "$WORK/out"; fail "install failed"; }
ok "archive unpacked" test -x "$P/opt/cringle/1.0.0/bin/cringle"
eq "current points to the version" "1.0.0" "$(readlink "$P/opt/cringle/current")"
ok "config file" test -f "$P/etc/cringle/cringle.env"
ok "data dir" test -d "$P/var/lib/cringle"
ok "daemon unit" test -f "$P/etc/systemd/system/cringle-daemon.service"
not "no unit of its own for the management server" test -e "$P/etc/systemd/system/cringle-management.service"
ok "the unit starts the daemon without arguments (it reads its settings file)" grep -q '^ExecStart=/opt/cringle/current/bin/cringle-daemon$' "$P/etc/systemd/system/cringle-daemon.service"
eq "default settings in the settings file" "bind=loopback components=management,repository daemon.port=7400 router.port=7450 management.port=7500 management.web.port=8443 repository.port=7600" "$(settings)"
ok "the unit lets the service user open ports below 1024" grep -q '^AmbientCapabilities=CAP_NET_BIND_SERVICE$' "$P/etc/systemd/system/cringle-daemon.service"
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

echo "== bind address and ports"
new_root
run --release 1.0.0 --bind all --port 7501 --web-port 9443 --web-url https://cringle.example:9443 --host node1.tail.example --router-port 7451 > /dev/null 2>&1 || fail "install with --bind failed"
eq "bind, ports and address are written to the settings file" "bind=all components=management,repository daemon.port=7400 cringle.host=node1.tail.example router.port=7451 management.port=7501 management.web.port=9443 management.web.url=https://cringle.example:9443 repository.port=7600" "$(settings)"
echo "JAVA_HOME=/my/jdk" >> "$P/etc/cringle/cringle.env"
run --release 1.0.0 > /dev/null 2>&1 || fail "install again failed"
ok "a later installation without options keeps the values" grep -q '^bind=all$' "$P/var/lib/cringle/config/cringle.conf"
ok "and the rest of the env file" grep -q '^JAVA_HOME=/my/jdk$' "$P/etc/cringle/cringle.env"
run --release 1.0.0 --bind loopback > /dev/null 2>&1 || fail "install with --bind loopback failed"
eq "an option changes the value" "1" "$(grep -c '^bind=loopback$' "$P/var/lib/cringle/config/cringle.conf")"
not "a bad port is refused" run --release 1.0.0 --web-port 70000
not "a word instead of a port is refused" run --release 1.0.0 --port abc
not "an option without a value is refused" run --release 1.0.0 --bind
not "an address instead of loopback or all is refused" run --release 1.0.0 --bind 192.0.2.7
not "a web address with a path is refused" run --release 1.0.0 --web-url https://cringle.example/path
not "a host with a port is refused" run --release 1.0.0 --host node1.tail.example:7500
not "a host with a scheme is refused" run --release 1.0.0 --host https://node1.tail.example
not "a bad router port is refused" run --release 1.0.0 --router-port 0
not "ports cannot be combined with --uninstall" run --uninstall --bind all

echo "== components"
new_root
run --release 1.0.0 --components repository --daemon-port 7401 --repository-port 7601 > /dev/null 2>&1 || fail "install with --components failed"
eq "only the repository" "bind=loopback components=repository daemon.port=7401 router.port=7450 management.port=7500 management.web.port=8443 repository.port=7601" "$(settings)"
run --release 1.0.0 --components management > /dev/null 2>&1 || fail "change of the components failed"
eq "only the management server" "management" "$(setting components)"
run --release 1.0.0 --components repository,management > /dev/null 2>&1 || fail "both components failed"
eq "both, in the usual order" "management,repository" "$(setting components)"
not "an unknown component is refused" run --release 1.0.0 --components management,router
not "--daemon-only and --components exclude each other" run --release 1.0.0 --daemon-only --components management
not "components cannot be combined with --uninstall" run --uninstall --components none

echo "== questions"
new_root
printf 'yes\nno\n7510\n8444\nhttps://cringle.example:8444\n7410\nnode2.tail.example\nyes\n' > "$WORK/answers"
CRINGLE_INSTALL_TTY="$WORK/answers" run --release 1.0.0 > "$WORK/out" 2>&1 || { cat "$WORK/out"; fail "install with questions failed"; }
eq "the answers are the settings" "bind=all components=management daemon.port=7410 cringle.host=node2.tail.example router.port=7450 management.port=7510 management.web.port=8444 management.web.url=https://cringle.example:8444 repository.port=7600" "$(settings)"
ok "the questions were shown" grep -q 'Port of the web interface \[8443\]' "$WORK/out"
# a second installation does not ask again, an option still changes a value
printf 'no\nno\n' > "$WORK/answers2"
CRINGLE_INSTALL_TTY="$WORK/answers2" run --release 1.0.0 --bind loopback > "$WORK/out2" 2>&1 || fail "second installation failed"
not "no questions on a later installation" grep -q 'Run the management server' "$WORK/out2"
eq "the earlier decisions stay" "management" "$(setting components)"
# Enter takes the defaults, an invalid answer is asked again, the end of the input takes the defaults
new_root
printf '\n\n\n\n\n\nabc\n99999\n\n\n' > "$WORK/answers3"
CRINGLE_INSTALL_TTY="$WORK/answers3" run --release 1.0.0 > "$WORK/out3" 2>&1 || { cat "$WORK/out3"; fail "install with defaults failed"; }
eq "defaults after Enter and bad answers" "loopback management,repository" "$(setting bind) $(setting components)"
ok "a bad port is said so" grep -q 'that is not a port' "$WORK/out3"
# an option skips its question, --no-ask all of them
new_root
printf 'no\n' > "$WORK/answers4"
CRINGLE_INSTALL_TTY="$WORK/answers4" run --release 1.0.0 --components management --port 7520 --web-port 8450 --web-url https://h.example:8450 --daemon-port 7420 --host h.tail.example --bind loopback > "$WORK/out4" 2>&1 || fail "install with options and a terminal failed"
not "no question for what the options gave" grep -q 'Port of the' "$WORK/out4"
new_root
CRINGLE_INSTALL_TTY="$WORK/answers" run --release 1.0.0 --no-ask > "$WORK/out5" 2>&1 || fail "install with --no-ask failed"
not "--no-ask asks nothing" grep -q 'Port of the' "$WORK/out5"
eq "--no-ask takes the defaults" "management,repository" "$(setting components)"

echo "== management server"
new_root
run --release 1.0.0 --daemon-only > /dev/null 2>&1 || fail "install daemon-only failed"
eq "daemon-only has no components" "none" "$(setting components)"
# an older installation with a unit for the management server
printf '[Unit]\nDescription=old\n' > "$P/etc/systemd/system/cringle-management.service"
run --release 2.0.0 > /dev/null 2>&1 || fail "upgrade failed"
not "old management unit removed on upgrade" test -e "$P/etc/systemd/system/cringle-management.service"
eq "an upgrade keeps daemon-only" "none" "$(setting components)"

echo "== an installation from before the settings file"
# with the settings in the env file and the arguments in the unit (the installers of #304 to #312)
new_root
mkdir -p "$P/etc/cringle" "$P/etc/systemd/system"
printf '# old\nJAVA_HOME=/my/jdk\nCRINGLE_BIND=all\nCRINGLE_COMPONENTS=repository\nCRINGLE_DAEMON_PORT=7402\nCRINGLE_MANAGEMENT_PORT=7502\nCRINGLE_WEB_PORT=8445\nCRINGLE_REPOSITORY_PORT=7602\nCRINGLE_DAEMON_ARGS=--port 7402 --combined --with-repository 7602\n' > "$P/etc/cringle/cringle.env"
printf '[Service]\nExecStart=/opt/cringle/current/bin/cringle-daemon $CRINGLE_DAEMON_ARGS\n' > "$P/etc/systemd/system/cringle-daemon.service"
run --release 1.0.0 > "$WORK/out6" 2>&1 || { cat "$WORK/out6"; fail "upgrade of an older installation failed"; }
eq "the values of the env file are in the settings file" "bind=all components=repository daemon.port=7402 router.port=7450 management.port=7502 management.web.port=8445 repository.port=7602" "$(settings)"
not "and no longer in the env file" grep -q '^CRINGLE_' "$P/etc/cringle/cringle.env"
ok "the rest of the env file stays" grep -q '^JAVA_HOME=/my/jdk$' "$P/etc/cringle/cringle.env"
not "no questions for an older installation" grep -q 'Run the management server' "$WORK/out6"
# the unit of an installation of before #304 has the arguments in it
new_root
mkdir -p "$P/etc/systemd/system"
printf '[Service]\nExecStart=/opt/cringle/current/bin/cringle-daemon --port 7400 --combined --with-management 7500 --web-port 8443 --with-repository 7600\n' > "$P/etc/systemd/system/cringle-daemon.service"
run --release 1.0.0 > /dev/null 2>&1 || fail "upgrade of an oldest installation failed"
eq "an oldest installation with management keeps it" "management,repository" "$(setting components)"

echo "== a local build (--from-build)"
new_root
build_run() { CRINGLE_INSTALL_TTY=/nonexistent CRINGLE_INSTALL_ROOT="$ROOT" CRINGLE_RELEASE_BASE_URL=file:///nonexistent sh "$INSTALLER" "$@"; }
if [ "$MODE" = staging ]; then
    build_run --from-build "$RELEASES/v1.0.0" > "$WORK/outb" 2>&1 || { cat "$WORK/outb"; fail "install from a build failed"; }
    eq "the version is the one of the archive" "1.0.0" "$(readlink "$P/opt/cringle/current")"
    eq "cringle --version of the build" "cringle 1.0.0" "$("$P/usr/local/bin/cringle" --version 2>&1)"
    ok "nothing was downloaded" grep -q 'installing the local build 1.0.0' "$WORK/outb"
    not "a folder that is not there is refused" build_run --from-build "$WORK/nowhere"
    not "an empty folder is refused" build_run --from-build "$WORK"
    not "a tampered archive is refused" build_run --from-build "$RELEASES/v9.9.9"
    mkdir -p "$WORK/two"
    cp "$RELEASES/v1.0.0/cringle-1.0.0-linux.tar.gz" "$RELEASES/v2.0.0/cringle-2.0.0-linux.tar.gz" "$WORK/two/"
    cat "$RELEASES/v1.0.0/SHA256SUMS" "$RELEASES/v2.0.0/SHA256SUMS" > "$WORK/two/SHA256SUMS"
    not "several archives need the version" build_run --from-build "$WORK/two"
    build_run --from-build "$WORK/two" --release 2.0.0 > /dev/null 2>&1 || fail "install of one of two archives failed"
    eq "the version that was named" "2.0.0" "$(readlink "$P/opt/cringle/current")"
    not "--from-build cannot be combined with --uninstall" build_run --uninstall --from-build "$RELEASES/v1.0.0"
fi

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
