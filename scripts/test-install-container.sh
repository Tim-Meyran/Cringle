#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# Test script for Cringle installer in Docker containers
# Tests installation, version switching, and uninstall in Ubuntu and Debian containers

set -euo pipefail

# Colors for output
# Removed color codes - using plain text output

# Check if docker is available
check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        echo "Error: Docker is not installed"
        exit 1
    fi
    
    # Check if docker daemon is running
    if ! docker info >/dev/null 2>&1; then
        echo "Error: Docker daemon is not running"
        exit 1
    fi
    
    echo "Docker is available and running"
}

# Create fake releases with proper structure
create_fake_releases() {
    echo "Creating fake release files..."
    
    local releases_dir="${TEST_DIR}/releases"
    mkdir -p "${releases_dir}"
    
    # Create version 1.0.0 release
    local v1_dir="${releases_dir}/v1.0.0"
    mkdir -p "${v1_dir}"
    
    # Create the archive structure
    local v1_archive="${v1_dir}/cringle-1.0.0-linux.tar.gz"
    local v1_temp=$(mktemp -d)
    
    # Create archive content
    mkdir -p "${v1_temp}/bin"
    echo "1.0.0" > "${v1_temp}/VERSION"
    cat > "${v1_temp}/bin/cringle" <<'EOF'
#!/bin/bash
echo "Cringle version 1.0.0"
EOF
    chmod +x "${v1_temp}/bin/cringle"
    
    cat > "${v1_temp}/bin/cringle-daemon" <<'EOF'
#!/bin/bash
echo "Cringle daemon 1.0.0"
EOF
    chmod +x "${v1_temp}/bin/cringle-daemon"
    
    # Create the tar.gz archive
    tar -czf "${v1_archive}" -C "${v1_temp}" .
    rm -rf "${v1_temp}"
    
    # Create version 2.0.0 release
    local v2_dir="${releases_dir}/v2.0.0"
    mkdir -p "${v2_dir}"
    
    local v2_archive="${v2_dir}/cringle-2.0.0-linux.tar.gz"
    local v2_temp=$(mktemp -d)
    
    # Create archive content
    mkdir -p "${v2_temp}/bin"
    echo "2.0.0" > "${v2_temp}/VERSION"
    cat > "${v2_temp}/bin/cringle" <<'EOF'
#!/bin/bash
echo "Cringle version 2.0.0"
EOF
    chmod +x "${v2_temp}/bin/cringle"
    
    cat > "${v2_temp}/bin/cringle-daemon" <<'EOF'
#!/bin/bash
echo "Cringle daemon 2.0.0"
EOF
    chmod +x "${v2_temp}/bin/cringle-daemon"
    
    # Create the tar.gz archive
    tar -czf "${v2_archive}" -C "${v2_temp}" .
    rm -rf "${v2_temp}"
    
    # Create SHA256SUMS file
    local checksums_file="${releases_dir}/SHA256SUMS"
    
    # Calculate checksums for both archives
    local v1_checksum=$(sha256sum "${v1_archive}" | awk '{print $1}')
    local v2_checksum=$(sha256sum "${v2_archive}" | awk '{print $1}')
    
    # Create checksums file with both Linux and Windows archives
    cat > "${checksums_file}" <<EOF
${v1_checksum}  cringle-1.0.0-linux.tar.gz
${v2_checksum}  cringle-2.0.0-linux.tar.gz
# Windows checksums would be here in a real release
# ${v1_checksum_win}  cringle-1.0.0-windows.zip
# ${v2_checksum_win}  cringle-2.0.0-windows.zip
EOF
    
    echo "Fake releases created in ${releases_dir}"
}

# Test installation in container
# $1: container image (ubuntu:24.04 or debian:stable)
# $2: test name
test_in_container() {
    local image="$1"
    local test_name="$2"
    
    echo "Testing in ${image} container..."
    
    # Run the test in a container
    docker run --rm \
        --env CRINGLE_INSTALL_ROOT=/opt/cringle \
        --env CRINGLE_RELEASE_BASE_URL=http://localhost:8000 \
        --volume ${TEST_DIR}/releases:/releases \
        --volume ${PWD}/install.sh:/install.sh \
        ${image} bash -c '
            set -euo pipefail
            
            # Copy install.sh to container
            cp /install.sh /tmp/install.sh
            chmod +x /tmp/install.sh
            
            # Start HTTP server to serve releases in background
            cd /releases && python3 -m http.server 8000 &
            sleep 2
            
            # Run installation tests
            echo "Running tests in ${image}"
            
            # Install version 1.0.0
            echo "Installing version 1.0.0..."
            /tmp/install.sh --release 1.0.0 || exit 1
            
            # Verify installation
            if [[ ! -d /opt/cringle/current ]]; then
                echo "ERROR: Current directory not created"
                exit 1
            fi
            
            if [[ ! -f /opt/cringle/current/VERSION ]]; then
                echo "ERROR: VERSION file not found"
                exit 1
            fi
            
            current_version=$(cat /opt/cringle/current/VERSION)
            if [[ "${current_version}" != "1.0.0" ]]; then
                echo "ERROR: Expected version 1.0.0, got ${current_version}"
                exit 1
            fi
            
            echo "Version 1.0.0 installed successfully"
            
            # Test user/group creation
            if ! id -u cringle >/dev/null 2>&1; then
                echo "ERROR: User 'cringle' not created"
                exit 1
            fi
            
            if ! id -g cringle >/dev/null 2>&1; then
                echo "ERROR: Group 'cringle' not created"
                exit 1
            fi
            
            echo "User and group created successfully"
            
            # Test directory creation
            if [[ ! -d /etc/cringle ]]; then
                echo "ERROR: /etc/cringle not created"
                exit 1
            fi
            
            if [[ ! -d /var/lib/cringle ]]; then
                echo "ERROR: /var/lib/cringle not created"
                exit 1
            fi
            
            echo "System directories created successfully"
            
            # Test systemd unit files
            if [[ ! -f /etc/systemd/system/cringle.service ]]; then
                echo "ERROR: Systemd service file not created"
                exit 1
            fi
            
            if [[ ! -f /etc/systemd/system/cringle.target ]]; then
                echo "ERROR: Systemd target file not created"
                exit 1
            fi
            
            # Guard systemctl commands
            systemctl daemon-reload || true
            systemctl enable cringle.service || true
            systemctl start cringle.service || true
            
            echo "Systemd configuration created successfully"
            
            # Test cringle --version as normal user
            su -s /bin/sh -c "cringle --version" cringle || exit 1
            
            echo "Version command works as normal user"
            
            # Switch to version 2.0.0
            echo "Switching to version 2.0.0..."
            /tmp/install.sh --release 2.0.0 || exit 1
            
            current_version=$(cat /opt/cringle/current/VERSION)
            if [[ "${current_version}" != "2.0.0" ]]; then
                echo "ERROR: Expected version 2.0.0, got ${current_version}"
                exit 1
            fi
            
            echo "Version 2.0.0 installed successfully"
            
            # Uninstall
            echo "Uninstalling..."
            /tmp/install.sh --uninstall || exit 1
            
            if [[ -d /opt/cringle ]]; then
                echo "ERROR: Installation directory not removed"
                exit 1
            fi
            
            echo "Uninstall completed successfully"
            
            echo "${test_name} PASSED"
        '
    
    echo "${test_name} completed"
}

# Main test execution
main() {
    # Create temporary directory
    TEST_DIR=$(mktemp -d)
    trap 'rm -rf "${TEST_DIR}"' EXIT
    
    echo "Starting container-based Cringle installer tests..."
    echo "Test directory: ${TEST_DIR}"
    echo ""
    
    # Check docker availability
    check_docker
    echo ""
    
    # Create fake releases
    create_fake_releases
    echo ""
    
    # Test in Ubuntu 24.04
    test_in_container "ubuntu:24.04" "Ubuntu 24.04 test"
    echo ""
    
    # Test in Debian stable
    test_in_container "debian:stable" "Debian stable test"
    echo ""
    
    echo "All container tests completed successfully!"
    exit 0
}

main "$@"
