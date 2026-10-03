#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# Cringle Linux installer script
# Downloads, verifies, and installs Cringle releases

set -euo pipefail

# Global variables
INSTALL_DIR="/opt/cringle"
SYSTEM_USER="cringle"
SYSTEM_GROUP="cringle"
VERSION_FILE="${INSTALL_DIR}/current/VERSION"
DAEMON_SERVICE="/etc/systemd/system/cringle-daemon.service"
MANAGEMENT_SERVICE="/etc/systemd/system/cringle-management.service"
BIN_SYMLINK="/usr/local/bin/cringle"

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
VERSION="latest"
WITH_MANAGEMENT=false
UNINSTALL=false
PURGE=false
SHOW_VERSION=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            SHOW_VERSION=true
            shift
            ;;
        --with-management)
            WITH_MANAGEMENT=true
            shift
            ;;
        --uninstall)
            UNINSTALL=true
            shift
            ;;
        --purge)
            PURGE=true
            shift
            ;;
        -v|--version)
            SHOW_VERSION=true
            shift
            ;;
        -m|--with-management)
            WITH_MANAGEMENT=true
            shift
            ;;
        -u|--uninstall)
            UNINSTALL=true
            shift
            ;;
        -p|--purge)
            PURGE=true
            shift
            ;;
        -*)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
        *)
            if [[ "$1" != "latest" && "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
                VERSION="$1"
            fi
            shift
            ;;
    esac
done

# Check if running as root
check_root() {
    if [[ $EUID -ne 0 ]]; then
        echo "${RED}Error:${NC} This script must be run as root" >&2
        exit 1
    fi
}

# Check Java version (21+)
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "${RED}Error:${NC} Java is not installed" >&2
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d" " -f2 | tr -d '"')
    MAJOR_VERSION=$(echo "$JAVA_VERSION" | cut -d. -f1)
    
    if [[ "$MAJOR_VERSION" -lt 21 ]]; then
        echo "${RED}Error:${NC} Java 21 or later is required (found: $JAVA_VERSION)" >&2
        exit 1
    fi
    
    echo "${GREEN}Java check passed:${NC} $JAVA_VERSION"
}

# Get latest version from GitHub
get_latest_version() {
    echo "${BLUE}Checking for latest Cringle version...${NC}"
    
    # Try to get latest release from GitHub
    if command -v curl >/dev/null 2>&1; then
        LATEST_RELEASE=$(curl -s https://api.github.com/repos/CringleProject/Cringle/releases/latest 2>/dev/null | grep '"tag_name"' | sed -E 's/.*"([^"]*)".*/\1/')
        if [[ -n "$LATEST_RELEASE" ]]; then
            VERSION="${LATEST_RELEASE#v}"
            echo "${GREEN}Latest version found:${NC} $VERSION"
        else
            echo "${YELLOW}Warning:${NC} Could not determine latest version from GitHub" >&2
            VERSION="latest"
        fi
    else
        echo "${YELLOW}Warning:${NC} curl not available, using 'latest' as version" >&2
        VERSION="latest"
    fi
}

# Download release files
download_release() {
    local base_url="https://github.com/CringleProject/Cringle/releases/download/v${VERSION}"
    local archive_name="cringle-${VERSION}-linux.tar.gz"
    local checksums_name="SHA256SUMS"
    local manifest_name="manifest.json"
    
    echo "${BLUE}Downloading Cringle ${VERSION}...${NC}"
    
    # Download files
    if ! curl -L --fail --silent --show-error --output "${archive_name}" "${base_url}/${archive_name}"; then
        echo "${RED}Error:${NC} Failed to download ${archive_name}" >&2
        exit 1
    fi
    
    if ! curl -L --fail --silent --show-error --output "${checksums_name}" "${base_url}/${checksums_name}"; then
        echo "${RED}Error:${NC} Failed to download ${checksums_name}" >&2
        exit 1
    fi
    
    if ! curl -L --fail --silent --show-error --output "${manifest_name}" "${base_url}/${manifest_name}"; then
        echo "${RED}Error:${NC} Failed to download ${manifest_name}" >&2
        exit 1
    fi
    
    echo "${GREEN}Download completed:${NC} ${archive_name}, ${checksums_name}, ${manifest_name}"
}

# Verify SHA-256 checksum
verify_checksum() {
    local archive_name="cringle-${VERSION}-linux.tar.gz"
    
    echo "${BLUE}Verifying SHA-256 checksum...${NC}"
    
    if ! sha256sum -c SHA256SUMS 2>&1 | grep -q "OK"; then
        echo "${RED}Error:${NC} SHA-256 checksum verification failed" >&2
        exit 1
    fi
    
    # Verify specific file
    local expected_sum=$(grep "${archive_name}" SHA256SUMS | awk '{print $1}')
    local actual_sum=$(sha256sum "${archive_name}" | awk '{print $1}')
    
    if [[ "$expected_sum" != "$actual_sum" ]]; then
        echo "${RED}Error:${NC} SHA-256 checksum mismatch for ${archive_name}" >&2
        exit 1
    fi
    
    echo "${GREEN}Checksum verification passed:${NC} ${archive_name}"
}

# Extract and install
install_release() {
    local archive_name="cringle-${VERSION}-linux.tar.gz"
    local install_path="${INSTALL_DIR}/${VERSION}"
    
    echo "${BLUE}Extracting and installing...${NC}"
    
    # Create installation directory
    mkdir -p "${install_path}"
    
    # Extract archive
    if ! tar -xzf "${archive_name}" -C "${install_path}" --strip-components=1; then
        echo "${RED}Error:${NC} Failed to extract ${archive_name}" >&2
        exit 1
    fi
    
    # Create current symlink
    ln -sf "${install_path}" "${INSTALL_DIR}/current"
    
    echo "${GREEN}Installation completed:${NC} ${install_path}"
}

# Create system user and directories
create_system_user() {
    echo "${BLUE}Creating system user and directories...${NC}"
    
    # Create system user if it doesn't exist
    if ! id "${SYSTEM_USER}" >/dev/null 2>&1; then
        if command -v useradd >/dev/null 2>&1; then
            useradd -r -s /bin/false "${SYSTEM_USER}"
        elif command -v adduser >/dev/null 2>&1; then
            adduser -D -H -s /bin/false "${SYSTEM_USER}"
        else
            echo "${RED}Error:${NC} Neither useradd nor adduser found" >&2
            exit 1
        fi
    fi
    
    # Create directories
    mkdir -p /var/lib/cringle
    mkdir -p /etc/cringle
    
    # Set ownership
    chown -R "${SYSTEM_USER}:${SYSTEM_GROUP}" /var/lib/cringle 2>/dev/null || true
    chown -R "${SYSTEM_USER}:${SYSTEM_GROUP}" /etc/cringle 2>/dev/null || true
    
    echo "${GREEN}System user and directories created:${NC} ${SYSTEM_USER}"
}

# Install systemd service for daemon
install_daemon_service() {
    echo "${BLUE}Installing daemon systemd service...${NC}"
    
    cat > "${DAEMON_SERVICE}" <<EOF
[Unit]
Description=Cringle daemon
After=network.target

[Service]
User=${SYSTEM_USER}
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/usr/bin/java -cp "${INSTALL_DIR}/current/lib/*" cringle.daemon.MainKt --port 7400 --combined --insecure-dev-mode
Restart=on-failure
RestartSec=5s
TimeoutStopSec=60s

[Install]
WantedBy=multi-user.target
EOF
    
    # Reload systemd and enable service
    systemctl daemon-reload
    systemctl enable --now cringle-daemon.service
    
    echo "${GREEN}Daemon service installed and enabled:${NC} cringle-daemon.service"
}

# Install systemd service for management server
install_management_service() {
    echo "${BLUE}Installing management server systemd service...${NC}"
    
    cat > "${MANAGEMENT_SERVICE}" <<EOF
[Unit]
Description=Cringle Management Server
After=network.target

[Service]
User=${SYSTEM_USER}
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/usr/bin/java -cp "${INSTALL_DIR}/current/lib/*" cringle.management.server.MainKt --port 7401 --insecure-dev-mode
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF
    
    # Reload systemd and enable service
    systemctl daemon-reload
    systemctl enable --now cringle-management.service
    
    echo "${GREEN}Management server service installed and enabled:${NC} cringle-management.service"
}

# Create binary symlink
create_bin_symlink() {
    echo "${BLUE}Creating binary symlink...${NC}"
    
    ln -sf "${INSTALL_DIR}/current/bin/cringle" "${BIN_SYMLINK}"
    
    echo "${GREEN}Binary symlink created:${NC} ${BIN_SYMLINK}"
}

# Show installed version
show_installed_version() {
    if [[ -f "${VERSION_FILE}" ]]; then
        echo "Installed Cringle version: $(cat "${VERSION_FILE}")"
    else
        echo "Cringle is not installed"
        exit 0
    fi
}

# Uninstall Cringle
uninstall() {
    echo "${BLUE}Uninstalling Cringle...${NC}"
    
    # Stop services
    if [[ -f "${DAEMON_SERVICE}" ]]; then
        systemctl stop cringle-daemon.service
        systemctl disable cringle-daemon.service
        rm "${DAEMON_SERVICE}"
        echo "${GREEN}Stopped and removed daemon service${NC}"
    fi
    
    if [[ "$WITH_MANAGEMENT" = true ]] && [[ -f "${MANAGEMENT_SERVICE}" ]]; then
        systemctl stop cringle-management.service
        systemctl disable cringle-management.service
        rm "${MANAGEMENT_SERVICE}"
        echo "${GREEN}Stopped and removed management service${NC}"
    fi
    
    # Remove binary symlink
    if [[ -L "${BIN_SYMLINK}" ]]; then
        rm "${BIN_SYMLINK}"
        echo "${GREEN}Removed binary symlink${NC}"
    fi
    
    # Remove installation
    if [[ -d "${INSTALL_DIR}" ]]; then
        rm -rf "${INSTALL_DIR}"
        echo "${GREEN}Removed installation directory${NC}"
    fi
    
    # Reload systemd
    systemctl daemon-reload
    
    echo "${GREEN}Uninstallation completed${NC}"
    
    if [[ "$PURGE" = true ]]; then
        echo "${BLUE}Purging system user and data...${NC}"
        
        # Remove system user
        if id "${SYSTEM_USER}" >/dev/null 2>&1; then
            userdel "${SYSTEM_USER}"
        fi
        
        # Remove data directories
        rm -rf /var/lib/cringle
        rm -rf /etc/cringle
        
        echo "${GREEN}Purge completed${NC}"
    fi
}

# Main installation function
install_cringle() {
    check_root
    check_java
    
    if [[ "$VERSION" == "latest" ]]; then
        get_latest_version
    fi
    
    download_release
    verify_checksum
    install_release
    create_system_user
    install_daemon_service
    
    if [[ "$WITH_MANAGEMENT" = true ]]; then
        install_management_service
    fi
    
    create_bin_symlink
    
    echo ""
    echo "${GREEN}Installation successful!${NC}"
    echo ""
    echo "Cringle ${VERSION} has been installed to:"
    echo "  - Installation: ${INSTALL_DIR}/current"
    echo "  - Binary: ${BIN_SYMLINK}"
    echo "  - Data: /var/lib/cringle"
    echo "  - Config: /etc/cringle"
    echo ""
    if [[ "$WITH_MANAGEMENT" = true ]]; then
        echo "Services installed:"
        echo "  - cringle-daemon.service"
        echo "  - cringle-management.service"
    else
        echo "Services installed:"
        echo "  - cringle-daemon.service"
    fi
    echo ""
    echo "To start using Cringle, run:"
    echo "  ${BIN_SYMLINK} --version"
}

# Main script execution
if [[ "$SHOW_VERSION" = true ]]; then
    show_installed_version
elif [[ "$UNINSTALL" = true ]]; then
    uninstall
else
    install_cringle
fi
