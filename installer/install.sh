#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Installer of Cringle for Linux with systemd (docs/daemon-service.md).
#
#   sudo ./install.sh [--release <version>] [--daemon-only] [--bind <loopback|all|address>] [--port <n>] [--web-port <n>] [--start]
#   sudo ./install.sh --uninstall [--purge]
#
#   --release <version>  install this version (default: the latest release)
#   --daemon-only        run only the daemon; by default it also runs the management server (port 7500, web interface 8443,
#                        user logins) and the repository (port 7600) as programs it supervises
#   --bind <value>       where the servers listen: loopback (default), all (every network interface) or an address
#   --port <n>           port of the management server (default 7500)
#   --web-port <n>       port of the web interface (default 8443)
#   --repository-port <n>  port of the repository (default 7600)
#   --daemon-port <n>    port of the daemon (default 7400)
#                        The values are kept in /etc/cringle/cringle.env; change them later with `cringle setup`.
#   --with-management    no longer needed (the default); accepted for old scripts
#   --start              start the installed services (default: they are enabled, but not started)
#   --uninstall          stop and remove the services, the symlinks and /opt/cringle; data and configuration stay
#   --purge              with --uninstall: also remove /var/lib/cringle, /etc/cringle and the user cringle
#
# Environment (for tests):
#   CRINGLE_INSTALL_ROOT       prefix for every path the installer writes; when set, the installer needs no root
#                              rights and does not create users, change owners or call systemctl
#   CRINGLE_RELEASE_BASE_URL   where the releases are downloaded from (default: the GitHub releases); the archive is
#                              <base>/v<version>/cringle-<version>-linux.tar.gz, the checksums <base>/v<version>/SHA256SUMS

set -eu

REPO_RELEASES="https://github.com/Tim-Meyran/Cringle/releases"
BASE_URL="${CRINGLE_RELEASE_BASE_URL:-$REPO_RELEASES/download}"
ROOT="${CRINGLE_INSTALL_ROOT:-}"
SERVICE_USER="cringle"

OPT_DIR="$ROOT/opt/cringle"
DATA_DIR="$ROOT/var/lib/cringle"
CONF_DIR="$ROOT/etc/cringle"
UNIT_DIR="$ROOT/etc/systemd/system"
BIN_LINK="$ROOT/usr/local/bin/cringle"

# the paths as the running system sees them, they stand inside the unit files
RUN_CURRENT="/opt/cringle/current"
RUN_DATA="/var/lib/cringle"
RUN_ENV="/etc/cringle/cringle.env"

DAEMON_PORT=7400
MANAGEMENT_PORT=7500
WEB_PORT=8443
REPOSITORY_PORT=7600

TMP_DIR=""

die() {
    echo "install.sh: $*" >&2
    exit 1
}

info() {
    echo "install.sh: $*"
}

cleanup() {
    if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
        rm -rf "$TMP_DIR"
    fi
    if [ -n "${STAGE_DIR:-}" ] && [ -d "$STAGE_DIR" ]; then
        rm -rf "$STAGE_DIR"
    fi
}
STAGE_DIR=""
trap cleanup EXIT
trap 'exit 1' INT TERM

usage() {
    sed -n '4,26p' "$0" | sed 's/^# \{0,1\}//'
}

RELEASE=""
WITH_MANAGEMENT=0
DAEMON_ONLY=0
START=0
UNINSTALL=0
PURGE=0
SET_BIND=""
SET_DAEMON_PORT=""
SET_MANAGEMENT_PORT=""
SET_WEB_PORT=""
SET_REPOSITORY_PORT=""

# option_value <option> <value>: the value of an option that needs one
option_value() {
    [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value"
    printf '%s' "$2"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --release)
            [ $# -ge 2 ] || die "--release needs a version, for example --release 1.2.3"
            RELEASE="${2#v}"
            shift 2
            ;;
        --release=*)
            RELEASE="${1#--release=}"
            RELEASE="${RELEASE#v}"
            shift
            ;;
        --bind) SET_BIND=$(option_value "$@"); shift 2 ;;
        --port) SET_MANAGEMENT_PORT=$(option_value "$@"); shift 2 ;;
        --web-port) SET_WEB_PORT=$(option_value "$@"); shift 2 ;;
        --repository-port) SET_REPOSITORY_PORT=$(option_value "$@"); shift 2 ;;
        --daemon-port) SET_DAEMON_PORT=$(option_value "$@"); shift 2 ;;
        --with-management) WITH_MANAGEMENT=1; shift ;;
        --daemon-only) DAEMON_ONLY=1; shift ;;
        --start) START=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        --purge) PURGE=1; shift ;;
        -h | --help) usage; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

if [ "$PURGE" -eq 1 ] && [ "$UNINSTALL" -eq 0 ]; then
    die "--purge works only together with --uninstall"
fi
for pair in "--port:$SET_MANAGEMENT_PORT" "--web-port:$SET_WEB_PORT" "--repository-port:$SET_REPOSITORY_PORT" "--daemon-port:$SET_DAEMON_PORT"; do
    value=${pair#*:}
    if [ -n "$value" ]; then
        printf '%s' "$value" | grep -Eq '^[0-9]{1,5}$' && [ "$value" -ge 1 ] && [ "$value" -le 65535 ] || die "${pair%%:*} needs a port (1 to 65535), not '$value'"
    fi
done
if [ -n "$SET_BIND" ]; then
    printf '%s' "$SET_BIND" | grep -Eq '^[A-Za-z0-9.:_-]+$' || die "--bind needs loopback, all or an address, not '$SET_BIND'"
fi
if [ "$UNINSTALL" -eq 1 ] && { [ -n "$SET_BIND$SET_MANAGEMENT_PORT$SET_WEB_PORT$SET_REPOSITORY_PORT$SET_DAEMON_PORT" ] || [ -n "$RELEASE" ] || [ "$WITH_MANAGEMENT" -eq 1 ] || [ "$DAEMON_ONLY" -eq 1 ] || [ "$START" -eq 1 ]; }; then
    die "--uninstall cannot be combined with --release, --daemon-only or --start"
fi
if [ -n "$RELEASE" ]; then
    printf '%s' "$RELEASE" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' \
        || die "'$RELEASE' is not a version like 1.2.3 or 1.2.3-rc.1"
fi

if [ -z "$ROOT" ]; then
    [ "$(id -u)" -eq 0 ] || die "this needs root rights, run it with sudo"
    command -v systemctl > /dev/null 2>&1 || die "systemctl not found: the installer is for distributions with systemd"
fi

# true if systemd is the init system that runs now (not in a plain container or chroot)
systemd_running() {
    [ -z "$ROOT" ] && [ -d /run/systemd/system ]
}

# download <url> <file>
download() {
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$2" "$1" || die "download failed: $1"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$2" "$1" || die "download failed: $1"
    else
        die "neither curl nor wget found"
    fi
}

latest_release() {
    api="https://api.github.com/repos/Tim-Meyran/Cringle/releases/latest"
    json="$TMP_DIR/latest.json"
    download "$api" "$json"
    tag=$(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$json" | head -n 1)
    [ -n "$tag" ] || die "cannot find the latest release; give a version with --release <version>"
    printf '%s' "${tag#v}"
}

# checks the file $1 in the directory $TMP_DIR against the line for it in SHA256SUMS
verify_checksum() {
    name="$1"
    line=$(grep -E "^[0-9a-fA-F]{64}[ ]+[*]?$name\$" "$TMP_DIR/SHA256SUMS" | head -n 1 || true)
    [ -n "$line" ] || die "no checksum for $name in SHA256SUMS"
    expected=$(printf '%s' "$line" | cut -c1-64 | tr 'A-F' 'a-f')
    actual=$(sha256sum "$TMP_DIR/$name" | cut -c1-64)
    [ "$expected" = "$actual" ] || die "checksum of $name does not match SHA256SUMS (expected $expected, got $actual); nothing was installed"
}

check_java() {
    if command -v java > /dev/null 2>&1 || { [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; }; then
        return 0
    fi
    info "warning: no Java found. Cringle needs a JDK 21 or newer; install it before you start the services."
}

create_user_and_dirs() {
    if [ -z "$ROOT" ]; then
        if ! getent passwd "$SERVICE_USER" > /dev/null 2>&1; then
            info "creating the system user $SERVICE_USER"
            useradd --system --user-group --home-dir "$RUN_DATA" --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER" \
                || die "cannot create the user $SERVICE_USER"
        fi
        mkdir -p "$DATA_DIR" "$CONF_DIR"
        chown "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"
        chown "root:$SERVICE_USER" "$CONF_DIR"
    else
        mkdir -p "$DATA_DIR" "$CONF_DIR"
    fi
    chmod 0750 "$DATA_DIR" "$CONF_DIR"
    # the configuration is written once and never overwritten
    if [ ! -e "$CONF_DIR/cringle.env" ]; then
        cat > "$CONF_DIR/cringle.env" << 'EOF'
# Environment of the Cringle services (systemd EnvironmentFile). This file is not overwritten by the installer.
# Extra JVM options of the daemon and the management server:
#CRINGLE_JVM_OPTS=-Xmx1g
# Path of a JDK 21 if java is not on the PATH of the services:
#JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EOF
        if [ -z "$ROOT" ]; then
            chown root:"$SERVICE_USER" "$CONF_DIR/cringle.env"
        fi
        chmod 0640 "$CONF_DIR/cringle.env"
    fi
    # the settings of the services: missing ones get their defaults, the ones given as options are set (others are left as they are)
    env_default CRINGLE_BIND "$SET_BIND" loopback
    env_default CRINGLE_DAEMON_PORT "$SET_DAEMON_PORT" "$DAEMON_PORT"
    env_default CRINGLE_MANAGEMENT_PORT "$SET_MANAGEMENT_PORT" "$MANAGEMENT_PORT"
    env_default CRINGLE_WEB_PORT "$SET_WEB_PORT" "$WEB_PORT"
    env_default CRINGLE_REPOSITORY_PORT "$SET_REPOSITORY_PORT" "$REPOSITORY_PORT"
}

# env_default <key> <given value> <default>: sets the key to the given value; without one it adds the key with the default if it is missing
env_default() {
    file="$CONF_DIR/cringle.env"
    if [ -n "$2" ]; then
        if grep -q "^$1=" "$file"; then
            sed "s|^$1=.*|$1=$2|" "$file" > "$file.new" && cat "$file.new" > "$file" && rm -f "$file.new"
        else
            printf '%s=%s\n' "$1" "$2" >> "$file"
        fi
    elif ! grep -q "^$1=" "$file"; then
        printf '%s=%s\n' "$1" "$3" >> "$file"
    fi
}

write_unit() {
    # write_unit <file name> <description> <after> <exec start line>
    mkdir -p "$UNIT_DIR"
    cat > "$UNIT_DIR/$1" << EOF
# Written by the Cringle installer (install.sh); changes are overwritten by the next installation.
# Put settings into $RUN_ENV or into a drop-in (systemctl edit $1).
[Unit]
Description=$2
After=$3

[Service]
User=$SERVICE_USER
Group=$SERVICE_USER
Environment=CRINGLE_HOME=$RUN_DATA
EnvironmentFile=-$RUN_ENV
ExecStart=$4
Restart=on-failure
RestartSec=5
# give engines time to stop gracefully
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "$UNIT_DIR/$1"
}

install_units() {
    # the ports are read from the env file by systemd (${KEY}), so `cringle setup` can change them without a new unit
    daemon_args="--port \${CRINGLE_DAEMON_PORT} --combined"
    if [ "$DAEMON_ONLY" -eq 0 ]; then
        daemon_args="$daemon_args --with-management \${CRINGLE_MANAGEMENT_PORT} --web-port \${CRINGLE_WEB_PORT} --with-repository \${CRINGLE_REPOSITORY_PORT}"
    fi
    write_unit cringle-daemon.service "Cringle daemon" network.target "$RUN_CURRENT/bin/cringle-daemon $daemon_args"
    # an older installation ran the management server as a unit of its own; the daemon runs it now
    if [ -e "$UNIT_DIR/cringle-management.service" ]; then
        if systemd_running; then
            systemctl disable --now cringle-management.service > /dev/null 2>&1 || true
        fi
        rm -f "$UNIT_DIR/cringle-management.service" "$UNIT_DIR/multi-user.target.wants/cringle-management.service"
    fi
}

# the units that exist
installed_units() {
    for unit in cringle-daemon.service cringle-management.service; do
        if [ -e "$UNIT_DIR/$unit" ]; then
            printf '%s\n' "$unit"
        fi
    done
}

enable_units() {
    for unit in $(installed_units); do
        if systemd_running; then
            systemctl enable "$unit" > /dev/null 2>&1 || die "cannot enable $unit"
        else
            # what `systemctl enable` does, for a system where systemd is not running (a container, a chroot)
            mkdir -p "$UNIT_DIR/multi-user.target.wants"
            ln -sfn "/etc/systemd/system/$unit" "$UNIT_DIR/multi-user.target.wants/$unit"
        fi
    done
}

install_release() {
    version="$1"
    archive="cringle-$version-linux.tar.gz"

    info "downloading Cringle $version"
    download "$BASE_URL/v$version/$archive" "$TMP_DIR/$archive"
    download "$BASE_URL/v$version/SHA256SUMS" "$TMP_DIR/SHA256SUMS"
    verify_checksum "$archive"

    check_java
    mkdir -p "$OPT_DIR"
    STAGE_DIR=$(mktemp -d "$OPT_DIR/.install.XXXXXX")
    tar -xzf "$TMP_DIR/$archive" -C "$STAGE_DIR" || die "cannot unpack $archive"
    [ -f "$STAGE_DIR/cringle-$version/bin/cringle" ] || die "$archive has no cringle-$version/bin/cringle"

    # an existing installation of the same version is replaced
    rm -rf "${OPT_DIR:?}/$version"
    mv "$STAGE_DIR/cringle-$version" "$OPT_DIR/$version"
    rm -rf "$STAGE_DIR"
    STAGE_DIR=""
    if [ -z "$ROOT" ]; then
        chown -R root:root "$OPT_DIR/$version"
    fi
    chmod -R go+rX,go-w "$OPT_DIR/$version"
    chmod 0755 "$OPT_DIR"

    # switch `current` in one step (a relative link, so it also works below a test root)
    ln -sfn "$version" "$OPT_DIR/current.new"
    mv -T "$OPT_DIR/current.new" "$OPT_DIR/current"

    create_user_and_dirs
    install_units
    enable_units
    mkdir -p "$(dirname "$BIN_LINK")"
    # below a test root the link points into the root, otherwise to the real path
    ln -sfn "$ROOT$RUN_CURRENT/bin/cringle" "$BIN_LINK"
}

stop_and_disable() {
    for unit in cringle-management.service cringle-daemon.service; do
        if systemd_running; then
            systemctl disable --now "$unit" > /dev/null 2>&1 || true
        fi
        rm -f "$UNIT_DIR/$unit" "$UNIT_DIR/multi-user.target.wants/$unit"
    done
    rmdir "$UNIT_DIR/multi-user.target.wants" 2> /dev/null || true
    if systemd_running; then
        systemctl daemon-reload || true
    fi
}

uninstall() {
    info "removing the Cringle services and program files"
    stop_and_disable
    if [ -L "$BIN_LINK" ]; then
        rm -f "$BIN_LINK"
    fi
    rm -rf "$OPT_DIR"
    if [ "$PURGE" -eq 1 ]; then
        info "removing the data in $RUN_DATA, the configuration in /etc/cringle and the user $SERVICE_USER"
        rm -rf "$DATA_DIR" "$CONF_DIR"
        if [ -z "$ROOT" ] && getent passwd "$SERVICE_USER" > /dev/null 2>&1; then
            userdel "$SERVICE_USER" > /dev/null 2>&1 || true
            if getent group "$SERVICE_USER" > /dev/null 2>&1; then
                groupdel "$SERVICE_USER" > /dev/null 2>&1 || true
            fi
        fi
    else
        info "kept the data in $RUN_DATA and the configuration in /etc/cringle (--purge removes them)"
    fi
}

if [ "$UNINSTALL" -eq 1 ]; then
    uninstall
    exit 0
fi

TMP_DIR=$(mktemp -d)
if [ -z "$RELEASE" ]; then
    [ "$BASE_URL" = "$REPO_RELEASES/download" ] || die "give the version with --release <version> when CRINGLE_RELEASE_BASE_URL is set"
    RELEASE=$(latest_release)
    info "the latest release is $RELEASE"
fi
install_release "$RELEASE"

if systemd_running; then
    systemctl daemon-reload
    if [ "$START" -eq 1 ]; then
        for unit in $(installed_units); do
            systemctl restart "$unit"
        done
    fi
elif [ "$START" -eq 1 ] && [ -z "$ROOT" ]; then
    info "systemd is not running here, so the services were not started"
fi

info "Cringle $RELEASE is installed in $RUN_CURRENT"
if [ "$START" -eq 0 ]; then
    info "the services are enabled, but not started: systemctl start cringle-daemon (a running service keeps the old version until it is restarted)"
fi
