#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Installer of Cringle for Linux with systemd (docs/daemon-service.md).
#
#   sudo ./install.sh [--release <version>] [--components <list>] [--bind <loopback|all>] [--port <n>] [--web-port <n>] [--no-ask] [--start]
#   sudo ./install.sh --uninstall [--purge]
#
#   --release <version>  install this version (default: the latest release)
#   --components <list>  what the daemon runs besides itself: management (the management server with the web interface and the
#                        user logins), repository (the package repository), both separated by a comma, or none. Default: both.
#   --daemon-only        the same as --components none
#   --host <name>        the name or address under which other Cringle machines reach this one: in a Tailscale network its Tailscale name or
#                        address. The Connect page of the web interface builds its addresses from it (default: not set)
#   --router-port <n>    port of the router of the daemon (default 7450)
#   --web-url <url>      the public address of the web interface for login links and QR codes, https://host:port (default: the address of the request)
#   --no-ask             do not ask: on the first installation, in a terminal, the installer asks for every value that no option gives
#                        (the components, the ports, the address); without a terminal it never asks and takes the options or the defaults
#   --bind <value>       where the servers listen: loopback (default) or all (every network interface)
#   --port <n>           port of the management server (default 7500)
#   --web-port <n>       port of the web interface (default 8443)
#   --repository-port <n>  port of the repository (default 7600)
#   --daemon-port <n>    port of the daemon (default 7400)
#                        The values are kept in /var/lib/cringle/config/cringle.conf; change them later with `cringle config` or `cringle setup`.
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
CONF_FILE="$DATA_DIR/config/cringle.conf"
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
    sed -n '4,32p' "$0" | sed 's/^# \{0,1\}//'
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
SET_COMPONENTS=""
SET_WEB_URL=""
SET_HOST=""
SET_ROUTER_PORT=""
NO_ASK=0

# normalize_components <list>: "management,repository", "management", "repository" or "none" in this order; fails for anything else
normalize_components() {
    m=0
    r=0
    old_ifs=$IFS
    IFS=,
    for part in $1; do
        case "$part" in
            management) m=1 ;;
            repository) r=1 ;;
            none | '') ;;
            *) IFS=$old_ifs; return 1 ;;
        esac
    done
    IFS=$old_ifs
    if [ $m -eq 1 ] && [ $r -eq 1 ]; then echo "management,repository"
    elif [ $m -eq 1 ]; then echo "management"
    elif [ $r -eq 1 ]; then echo "repository"
    else echo "none"
    fi
}

# ---- questions ----
# On the first installation, in a terminal, the installer asks for what no option gave. The answers are read from the terminal itself (/dev/tty),
# so they work when the script comes through a pipe (curl ... | sh); CRINGLE_INSTALL_TTY names another file to read them from (for tests).
# Without a terminal nothing is asked.

ASK_TTY="${CRINGLE_INSTALL_TTY:-/dev/tty}"

# ask <question> <default>: the answer in ANSWER; Enter, or the end of the input, is the default
ask() {
    printf '%s [%s]: ' "$1" "$2" >&2
    if read -r ANSWER <&3; then :; else ANSWER=""; echo >&2; fi
    [ -n "$ANSWER" ] || ANSWER=$2
}

# ask_yes <question> <default yes|no>: succeeds for yes
ask_yes() {
    tries=0
    while [ $tries -lt 3 ]; do
        ask "$1 (yes/no)" "$2"
        case "$ANSWER" in
            y | Y | yes | YES | Yes) return 0 ;;
            n | N | no | NO | No) return 1 ;;
        esac
        echo "please answer yes or no" >&2
        tries=$((tries + 1))
    done
    die "no valid answer given"
}

# ask_port <question> <default>: a port in ANSWER
ask_port() {
    tries=0
    while [ $tries -lt 3 ]; do
        ask "$1" "$2"
        case "$ANSWER" in
            '' | *[!0-9]*) ;;
            *) if [ "$ANSWER" -ge 1 ] && [ "$ANSWER" -le 65535 ]; then return 0; fi ;;
        esac
        echo "that is not a port (1 to 65535)" >&2
        tries=$((tries + 1))
    done
    die "no valid port given"
}

ask_settings() {
    [ "$UNINSTALL" -eq 0 ] && [ "$NO_ASK" -eq 0 ] || return 0
    # a later installation keeps what the first one decided; the options change it
    [ ! -f "$CONF_DIR/cringle.env" ] && [ ! -f "$CONF_FILE" ] || return 0
    # a subshell: a file that cannot be opened ends the shell that tries to (that is the rule for exec)
    ( exec 3< "$ASK_TTY" ) 2> /dev/null || return 0
    exec 3< "$ASK_TTY"
    echo "Cringle installation: press Enter to take the value in [ ]. (--no-ask or the options skip the questions.)" >&2
    if [ -z "$SET_COMPONENTS" ]; then
        chosen=""
        if ask_yes "Run the management server (web interface, user logins) on this machine?" yes; then chosen="management"; fi
        if ask_yes "Run the package repository on this machine?" yes; then chosen="$chosen,repository"; fi
        SET_COMPONENTS=$(normalize_components "$chosen")
    fi
    case ",$SET_COMPONENTS," in
        *,management,*)
            [ -n "$SET_MANAGEMENT_PORT" ] || { ask_port "Port of the management server" "$MANAGEMENT_PORT"; SET_MANAGEMENT_PORT=$ANSWER; }
            [ -n "$SET_WEB_PORT" ] || { ask_port "Port of the web interface" "$WEB_PORT"; SET_WEB_PORT=$ANSWER; }
            ;;
    esac
    case ",$SET_COMPONENTS," in
        *,repository,*) [ -n "$SET_REPOSITORY_PORT" ] || { ask_port "Port of the repository" "$REPOSITORY_PORT"; SET_REPOSITORY_PORT=$ANSWER; } ;;
    esac
    case ",$SET_COMPONENTS," in
        *,management,*)
            if [ -z "$SET_WEB_URL" ]; then
                tries=0
                while [ $tries -lt 3 ]; do
                    ask "Public address of the web interface for links and QR codes (https://host:port, Enter: the address of the request)" ""
                    if [ -z "$ANSWER" ] || printf '%s' "$ANSWER" | grep -Eq '^https://[^/ ?#@]+$'; then SET_WEB_URL=$ANSWER; break; fi
                    echo "that is not an address like https://host:port" >&2
                    tries=$((tries + 1))
                done
                [ $tries -lt 3 ] || die "no valid address given"
            fi
            ;;
    esac
    [ -n "$SET_DAEMON_PORT" ] || { ask_port "Port of the daemon" "$DAEMON_PORT"; SET_DAEMON_PORT=$ANSWER; }
    if [ -z "$SET_HOST" ]; then
        # in a Tailscale network the name or address of the machine there is the one the other machines use
        suggestion=""
        if command -v tailscale > /dev/null 2>&1; then suggestion=$(tailscale ip -4 2> /dev/null | head -n 1 || true); fi
        tries=0
        while [ $tries -lt 3 ]; do
            ask "Name or address under which other Cringle machines reach this one (Tailscale name or address; Enter: not set)" "$suggestion"
            if [ -z "$ANSWER" ] || printf '%s' "$ANSWER" | grep -Eq '^([A-Za-z0-9][A-Za-z0-9._-]*|\[[0-9A-Fa-f:.]+\])$'; then SET_HOST=$ANSWER; break; fi
            echo "that is not a name or an address (no scheme, port or path)" >&2
            tries=$((tries + 1))
        done
        [ $tries -lt 3 ] || die "no valid name given"
    fi
    if [ -z "$SET_BIND" ]; then
        if ask_yes "Listen on all network interfaces (no: only on this machine)?" no; then SET_BIND=all; else SET_BIND=loopback; fi
    fi
    exec 3<&-
}

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
        --components) SET_COMPONENTS=$(option_value "$@"); shift 2 ;;
        --web-url) SET_WEB_URL=$(option_value "$@"); shift 2 ;;
        --host) SET_HOST=$(option_value "$@"); shift 2 ;;
        --router-port) SET_ROUTER_PORT=$(option_value "$@"); shift 2 ;;
        --no-ask) NO_ASK=1; shift ;;
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
if [ -n "$SET_HOST" ]; then
    printf '%s' "$SET_HOST" | grep -Eq '^([A-Za-z0-9][A-Za-z0-9._-]*|\[[0-9A-Fa-f:.]+\])$' || die "--host needs a name or an address without scheme, port or path, not '$SET_HOST'"
fi
for pair in "--port:$SET_MANAGEMENT_PORT" "--web-port:$SET_WEB_PORT" "--repository-port:$SET_REPOSITORY_PORT" "--daemon-port:$SET_DAEMON_PORT" "--router-port:$SET_ROUTER_PORT"; do
    value=${pair#*:}
    if [ -n "$value" ]; then
        printf '%s' "$value" | grep -Eq '^[0-9]{1,5}$' && [ "$value" -ge 1 ] && [ "$value" -le 65535 ] || die "${pair%%:*} needs a port (1 to 65535), not '$value'"
    fi
done
if [ "$DAEMON_ONLY" -eq 1 ]; then
    [ -z "$SET_COMPONENTS" ] || [ "$SET_COMPONENTS" = none ] || die "--daemon-only and --components exclude each other"
    SET_COMPONENTS=none
fi
if [ -n "$SET_COMPONENTS" ]; then
    SET_COMPONENTS=$(normalize_components "$SET_COMPONENTS") || die "--components needs management, repository, both separated by a comma, or none, not '$SET_COMPONENTS'"
fi
if [ -n "$SET_WEB_URL" ]; then
    printf '%s' "$SET_WEB_URL" | grep -Eq '^https://[^/ ?#@]+$' || die "--web-url needs an address like https://host:port, not '$SET_WEB_URL'"
fi
if [ -n "$SET_BIND" ]; then
    case "$SET_BIND" in
        loopback | all) ;;
        *) die "--bind needs loopback or all, not '$SET_BIND'" ;;
    esac
fi
if [ "$UNINSTALL" -eq 1 ] && { [ -n "$SET_BIND$SET_MANAGEMENT_PORT$SET_WEB_PORT$SET_REPOSITORY_PORT$SET_DAEMON_PORT$SET_COMPONENTS$SET_WEB_URL$SET_HOST$SET_ROUTER_PORT" ] || [ -n "$RELEASE" ] || [ "$WITH_MANAGEMENT" -eq 1 ] || [ "$DAEMON_ONLY" -eq 1 ] || [ "$START" -eq 1 ] || [ "$NO_ASK" -eq 1 ]; }; then
    die "--uninstall cannot be combined with --release, --components, --daemon-only, --bind, a port or --start"
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
    write_settings
}

# conf_get <key>: the value in the settings file of the daemon, empty if there is none
conf_get() {
    if [ -f "$CONF_FILE" ]; then
        sed -n "s/^$1=//p" "$CONF_FILE" | tail -n 1
    fi
}

# conf_put <key> <value>: sets the key in the settings file, replacing the line it has
conf_put() {
    if grep -q "^$1=" "$CONF_FILE"; then
        sed "s|^$1=.*|$1=$2|" "$CONF_FILE" > "$CONF_FILE.new" && cat "$CONF_FILE.new" > "$CONF_FILE" && rm -f "$CONF_FILE.new"
    else
        printf '%s=%s\n' "$1" "$2" >> "$CONF_FILE"
    fi
}

# resolve <key> <given value> <old variable of the env file> <default>: the given value, else the one in the settings file, else the one the
# env file of an older installation has, else the default
resolve() {
    if [ -n "$2" ]; then
        echo "$2"
        return
    fi
    found=$(conf_get "$1")
    if [ -z "$found" ] && [ -n "$3" ]; then found=$(env_get "$3"); fi
    if [ -n "$found" ]; then echo "$found"; else echo "$4"; fi
}

# The settings of the services (the key-value store of the daemon, docs/daemon-service.md): an option wins, then what the file has, then what an older
# installation had in the env file, then the default. The questions have turned into options by now.
write_settings() {
    mkdir -p "$DATA_DIR/config"
    if [ ! -f "$CONF_FILE" ]; then
        printf '%s\n' '# Settings of the Cringle services of this machine. Change them with `cringle config` or the web interface, or here and restart the daemon.' > "$CONF_FILE"
    fi
    conf_put bind "$(resolve bind "$SET_BIND" CRINGLE_BIND loopback)"
    conf_put components "$(resolve components "$SET_COMPONENTS" CRINGLE_COMPONENTS "$(default_components)")"
    conf_put daemon.port "$(resolve daemon.port "$SET_DAEMON_PORT" CRINGLE_DAEMON_PORT "$DAEMON_PORT")"
    host=$(resolve cringle.host "$SET_HOST" "" "")
    if [ -n "$host" ]; then conf_put cringle.host "$host"; fi
    conf_put router.port "$(resolve router.port "$SET_ROUTER_PORT" "" 7450)"
    conf_put management.port "$(resolve management.port "$SET_MANAGEMENT_PORT" CRINGLE_MANAGEMENT_PORT "$MANAGEMENT_PORT")"
    conf_put management.web.port "$(resolve management.web.port "$SET_WEB_PORT" CRINGLE_WEB_PORT "$WEB_PORT")"
    web_url=$(resolve management.web.url "$SET_WEB_URL" "" "")
    if [ -n "$web_url" ]; then conf_put management.web.url "$web_url"; fi
    conf_put repository.port "$(resolve repository.port "$SET_REPOSITORY_PORT" CRINGLE_REPOSITORY_PORT "$REPOSITORY_PORT")"
    # what the env file had of these settings is in the settings file now
    if [ -f "$CONF_DIR/cringle.env" ]; then
        sed '/^CRINGLE_\(BIND\|COMPONENTS\|DAEMON_PORT\|MANAGEMENT_PORT\|WEB_PORT\|REPOSITORY_PORT\|DAEMON_ARGS\)=/d' "$CONF_DIR/cringle.env" > "$CONF_DIR/cringle.env.new" \
            && cat "$CONF_DIR/cringle.env.new" > "$CONF_DIR/cringle.env" && rm -f "$CONF_DIR/cringle.env.new"
    fi
    # the daemon (the service user) writes this file when `cringle config` changes a setting
    if [ -z "$ROOT" ]; then chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR/config"; fi
}

# env_get <key>: the value in the env file, empty if there is none
env_get() {
    if [ -f "$CONF_DIR/cringle.env" ]; then
        sed -n "s/^$1=//p" "$CONF_DIR/cringle.env" | tail -n 1
    fi
}

# the components of an installation from before they were a setting: the daemon ran the management server unless it was installed daemon only
default_components() {
    if [ -f "$UNIT_DIR/cringle-daemon.service" ] && ! grep -q -e '--with-management' -e 'CRINGLE_DAEMON_ARGS' "$UNIT_DIR/cringle-daemon.service"; then
        echo none
    else
        echo "management,repository"
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
    # the daemon takes its settings (components, ports, address) from <home>/config/cringle.conf: no arguments in the unit
    write_unit cringle-daemon.service "Cringle daemon" network.target "$RUN_CURRENT/bin/cringle-daemon"
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

ask_settings
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
