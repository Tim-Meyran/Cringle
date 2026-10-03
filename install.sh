#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# Cringle Linux installer script
# Downloads, verifies, and installs Cringle releases

set -euo pipefail

# Global variables
INSTALL_DIR="/opt/cringle"
SYSTEM_USER="cringle"
SYSTEM_GROUP="cringle"

DAEMON_SERVICE="/etc/systemd/system/cringle-daemon.service"
MANAGEMENT_SERVICE="/etc/systemd/system/cringle-management.service"
BIN_SYMLINK="/usr/local/bin/cringle"

# Create temporary directory for the entire script
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# Colors for output
# Removed color variables - using plain text output

  # Default values
  RELEASE="latest"
  WITH_MANAGEMENT=false
  UNINSTALL=false
  PURGE=false
  START_SERVICES=false

  # Parse command line arguments
  while [[ $# -gt 0 ]]; do
      case "$1" in
          --version)
              echo "Cringle installer version: 1.0.0"
              exit 0
              ;;
          --release)
              if [[ $# -gt 1 ]]; then
                  if ! [[ "$2" =~ ^- ]]; then
                      # Validate release version format
                      if [[ "$2" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
                          RELEASE="$2"
                          shift
                      else
                          echo "Error: --release value must match pattern ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$" >&2
                          exit 1
                      fi
                  else
                      echo "Error: --release requires a version argument" >&2
                      exit 1
                  fi
              else
                  echo "Error: --release requires a version argument" >&2
                  exit 1
              fi
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
          --start)
              START_SERVICES=true
              shift
              ;;
          -*)
              echo "Error: Unknown option: $1" >&2
              exit 1
              ;;
          *)
              echo "Error: Unknown positional argument: $1" >&2
              exit 1
              ;;
      esac
  done

# Check if running as root
check_root() {
    if [[ $EUID -ne 0 ]]; then
        echo "Error: This script must be run as root" >&2
        exit 1
    fi
}

# Check Java version (21+)
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "Error: Java is not installed" >&2
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d" " -f2 | tr -d '"')
    MAJOR_VERSION=$(echo "$JAVA_VERSION" | cut -d. -f1)
    
    if [[ "$MAJOR_VERSION" -lt 21 ]]; then
        echo "Error: Java 21 or later is required (found: $JAVA_VERSION)" >&2
        exit 1
    fi
    
    echo "Java check passed: $JAVA_VERSION"
}

# Get latest version from GitHub
get_latest_version() {
    echo "Checking for latest Cringle version..."
    
    # Try to get latest release from GitHub
    if command -v curl >/dev/null 2>&1; then
        LATEST_RELEASE=$(curl -s https://api.github.com/repos/Tim-Meyran/Cringle/releases/latest 2>/dev/null | grep '"tag_name"' | sed -E 's/.*"([^"]*)".*/\1/')
        if [[ -n "$LATEST_RELEASE" ]]; then
            RELEASE="${LATEST_RELEASE#v}"
            echo "Latest version found: $RELEASE"
        else
            echo "Warning: Could not determine latest version from GitHub" >&2
            RELEASE="latest"
        fi
    else
        echo "Warning: curl not available, using 'latest' as version" >&2
        RELEASE="latest"
    fi
}

# Download release files
download_release() {
    local base_url="https://github.com/Tim-Meyran/Cringle/releases/download/v${RELEASE}"
    local archive_name="cringle-${RELEASE}-linux.tar.gz"
    local checksums_name="SHA256SUMS"
    
    echo "Downloading Cringle ${RELEASE}..."
    
    # Download files to temp directory
    if ! curl -L --fail --silent --show-error --output "${TMP_DIR}/${archive_name}" "${base_url}/${archive_name}"; then
        echo "Error: Failed to download ${archive_name}" >&2
        exit 1
    fi
    
    if ! curl -L --fail --silent --show-error --output "${TMP_DIR}/${checksums_name}" "${base_url}/${checksums_name}"; then
        echo "Error: Failed to download ${checksums_name}" >&2
        exit 1
    fi
    
    echo "Download completed: ${archive_name}, ${checksums_name}"
}

# Verify SHA-256 checksum
verify_checksum() {
    local archive_name="cringle-${RELEASE}-linux.tar.gz"
    local checksums_file="${TMP_DIR}/SHA256SUMS"
    
    echo "Verifying SHA-256 checksum..."
    
    # Verify specific Linux archive only
    if ! grep "${archive_name}$" "${checksums_file}" | sha256sum -c -; then
        echo "Error: SHA-256 checksum verification failed" >&2
        # Remove any downloaded files on checksum failure
        rm -f "${TMP_DIR}/${archive_name}" "${checksums_file}"
        exit 1
    fi
    
    echo "Checksum verification passed: ${archive_name}"
}

# Extract and install
install_release() {
    local archive_name="cringle-${RELEASE}-linux.tar.gz"
    local archive_path="${TMP_DIR}/${archive_name}"
    local install_path="${INSTALL_DIR}/${RELEASE}"
    local temp_extract_dir
    
    echo "Extracting and installing..."
    
    # Create installation directory if it doesn't exist
    mkdir -p "${install_path}"
    
    # Create temporary directory for extraction inside installation directory
    temp_extract_dir=$(mktemp -d -p "${INSTALL_DIR}")
    trap 'rm -rf "${temp_extract_dir}"' EXIT
    
    # Extract archive to temp directory
    if ! tar -xzf "${archive_path}" -C "${temp_extract_dir}" --strip-components=1; then
        echo "Error: Failed to extract ${archive_name}" >&2
        exit 1
    fi
    
    # Move entire extracted directory to final location (preserves dotfiles)
    if [[ -d "${install_path}" ]]; then
        # If directory exists, remove it first to handle re-installation
        rm -rf "${install_path}"
    fi
    mv "${temp_extract_dir}" "${install_path}"
    trap - EXIT  # Remove trap since we've moved files
    
    # Create current symlink
    ln -sfn "${install_path}" "${INSTALL_DIR}/current"
    
    echo "Installation completed: ${install_path}"
}

# Create system user and directories
create_system_user() {
    echo "Creating system user and directories..."
    
    # Create system user if it doesn't exist
    if ! id "${SYSTEM_USER}" >/dev/null 2>&1; then
        if command -v useradd >/dev/null 2>&1; then
            useradd --system --user-group -s /usr/sbin/nologin "${SYSTEM_USER}"
        elif command -v adduser >/dev/null 2>&1; then
            adduser -D -H -s /usr/sbin/nologin "${SYSTEM_USER}"
        else
            echo "Error: Neither useradd nor adduser found" >&2
            exit 1
        fi
    fi
    
    # Create directories with proper ownership if they don't exist
    if [[ ! -d "/var/lib/cringle" ]]; then
        install -d -o "${SYSTEM_USER}" -g "${SYSTEM_GROUP}" /var/lib/cringle || exit 1
    fi
    if [[ ! -d "/etc/cringle" ]]; then
        install -d -o "${SYSTEM_USER}" -g "${SYSTEM_GROUP}" /etc/cringle || exit 1
    fi
    
    echo "System user and directories created: ${SYSTEM_USER}"
}

# Install systemd service for daemon
install_daemon_service() {
    echo "Installing daemon systemd service..."
    
    cat > "${DAEMON_SERVICE}" <<EOF
[Unit]
Description=Cringle daemon
After=network.target

[Service]
User=${SYSTEM_USER}
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/opt/cringle/current/bin/cringle-daemon --port 7400 --combined --insecure-dev-mode
Restart=on-failure
RestartSec=5s
TimeoutStopSec=60s

[Install]
WantedBy=multi-user.target
EOF
    
    # Reload systemd and enable service
    systemctl daemon-reload
    systemctl enable cringle-daemon.service
    if [[ "$START_SERVICES" = true ]]; then
        systemctl start cringle-daemon.service
    fi
    
    echo "Daemon service installed and enabled: cringle-daemon.service"
}

# Install systemd service for management server
install_management_service() {
    echo "Installing management server systemd service..."
    
    cat > "${MANAGEMENT_SERVICE}" <<EOF
[Unit]
Description=Cringle Management Server
After=network.target

[Service]
User=${SYSTEM_USER}
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/opt/cringle/current/bin/cringle-management-server --port 7401 --insecure-dev-mode
Restart=on-failure
RestartSec=5s
TimeoutStopSec=60s

[Install]
WantedBy=multi-user.target
EOF
    
    # Reload systemd and enable service
    systemctl daemon-reload
    systemctl enable cringle-management.service
    if [[ "$START_SERVICES" = true ]]; then
        systemctl start cringle-management.service
    fi
    
    echo "Management server service installed and enabled: cringle-management.service"
}

# Create binary symlink
create_bin_symlink() {
    echo "Creating binary symlink..."
    
    ln -sf "${INSTALL_DIR}/current/bin/cringle" "${BIN_SYMLINK}"
    
    echo "Binary symlink created: ${BIN_SYMLINK}"
}



# Uninstall Cringle
uninstall() {
    echo "Uninstalling Cringle..."
    
    # Stop services
    if [[ -f "${DAEMON_SERVICE}" ]]; then
        systemctl stop cringle-daemon.service || true
        systemctl disable cringle-daemon.service || true
        rm "${DAEMON_SERVICE}"
        echo "Stopped and removed daemon service"
    fi
    
    # Always remove management service if present (not conditional on --with-management)
    if [[ -f "${MANAGEMENT_SERVICE}" ]]; then
        systemctl stop cringle-management.service || true
        systemctl disable cringle-management.service || true
        rm "${MANAGEMENT_SERVICE}"
        echo "Stopped and removed management service"
    fi
    
    # Remove binary symlink
    if [[ -L "${BIN_SYMLINK}" ]]; then
        rm "${BIN_SYMLINK}"
        echo "Removed binary symlink"
    fi
    
    # Remove installation
    if [[ -d "${INSTALL_DIR}" ]]; then
        rm -rf "${INSTALL_DIR}"
        echo "Removed installation directory"
    fi
    
    # Reload systemd
    systemctl daemon-reload
    
    echo "Uninstallation completed"
    
    if [[ "$PURGE" = true ]]; then
        echo "Purging system user and data..."
        
        # Remove system user
        if id "${SYSTEM_USER}" >/dev/null 2>&1; then
            userdel "${SYSTEM_USER}"
        fi
        
        # Remove data directories
        rm -rf /var/lib/cringle
        rm -rf /etc/cringle
        
        echo "Purge completed"
    fi
}

# Main installation function
install_cringle() {
    check_root
    check_java
    
    if [[ "$RELEASE" == "latest" ]]; then
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
    
    # Verify binary symlink is executable
    if [[ ! -x "${INSTALL_DIR}/current/bin/cringle" ]]; then
        echo "Error: Binary symlink not executable" >&2
        exit 1
    fi
    
    echo ""
    echo "Installation successful!"
    echo ""
    echo "Cringle ${RELEASE} has been installed to:"
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
# Check if purge is used without uninstall
if [[ "$PURGE" = true ]] && [[ "$UNINSTALL" = false ]]; then
    echo "Error: --purge requires --uninstall" >&2
    exit 1
fi

if [[ "$UNINSTALL" = true ]]; then
    uninstall
else
    install_cringle
fi
