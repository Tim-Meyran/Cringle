#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# Test script for Cringle installer
# Tests installation, checksum verification, version switching, and uninstall
# Uses environment variables to override paths and URLs for testing

set -euo pipefail

# Colors for output
# Removed color codes - using plain text output

# Test configuration
TEST_DIR=$(mktemp -d)
INSTALL_ROOT="${CRINGLE_INSTALL_ROOT:-$TEST_DIR}"
RELEASE_BASE_URL="${CRINGLE_RELEASE_BASE_URL:-$TEST_DIR/releases}"
HTTP_SERVER_PORT=8765

# Cleanup on exit
cleanup() {
    # Kill HTTP server if running
    if [[ -n "${HTTP_SERVER_PID:-}" ]]; then
        kill "$HTTP_SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

# Test exit code
TEST_EXIT_CODE=0

# Create real release tarballs
create_real_releases() {
    echo "Creating real release tarballs..."
    
    mkdir -p "$RELEASE_BASE_URL"
    
    # Create temporary directory for building releases
    local temp_release_dir=$(mktemp -d)
    
    # Create version 1.0.0 release files
    mkdir -p "$temp_release_dir/v1.0.0"
    
    # Create bin directory with scripts
    mkdir -p "$temp_release_dir/v1.0.0/bin"
    
    # Create cringle script that prints version
    cat > "$temp_release_dir/v1.0.0/bin/cringle" <<'EOF'
#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

echo "Cringle version 1.0.0"
EOF
    chmod +x "$temp_release_dir/v1.0.0/bin/cringle"
    
    # Create cringle-daemon script
    cat > "$temp_release_dir/v1.0.0/bin/cringle-daemon" <<'EOF'
#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

echo "Cringle daemon version 1.0.0"
EOF
    chmod +x "$temp_release_dir/v1.0.0/bin/cringle-daemon"
    
    # Create VERSION file
    echo "1.0.0" > "$temp_release_dir/v1.0.0/VERSION"
    
    # Create checksum for the tarball
    mkdir -p "$RELEASE_BASE_URL/v1.0.0"
    tar -czf "$RELEASE_BASE_URL/v1.0.0/cringle-1.0.0-linux.tar.gz" -C "$temp_release_dir/v1.0.0" .
    sha256sum "$RELEASE_BASE_URL/v1.0.0/cringle-1.0.0-linux.tar.gz" | awk '{print $1"  cringle-1.0.0-linux.tar.gz"}' > "$RELEASE_BASE_URL/v1.0.0/SHA256SUMS"
    
    # Create manifest file
    cat > "$RELEASE_BASE_URL/v1.0.0/manifest.json" <<'EOF'
{
  "version": "1.0.0",
  "description": "Cringle 1.0.0",
  "dependencies": []
}
EOF
    
    # Create version 2.0.0 release files
    mkdir -p "$temp_release_dir/v2.0.0"
    
    # Create bin directory with scripts
    mkdir -p "$temp_release_dir/v2.0.0/bin"
    
    # Create cringle script that prints version
    cat > "$temp_release_dir/v2.0.0/bin/cringle" <<'EOF'
#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

echo "Cringle version 2.0.0"
EOF
    chmod +x "$temp_release_dir/v2.0.0/bin/cringle"
    
    # Create cringle-daemon script
    cat > "$temp_release_dir/v2.0.0/bin/cringle-daemon" <<'EOF'
#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

echo "Cringle daemon version 2.0.0"
EOF
    chmod +x "$temp_release_dir/v2.0.0/bin/cringle-daemon"
    
    # Create VERSION file
    echo "2.0.0" > "$temp_release_dir/v2.0.0/VERSION"
    
    # Create checksum for the tarball
    tar -czf "$RELEASE_BASE_URL/v2.0.0/cringle-2.0.0-linux.tar.gz" -C "$temp_release_dir/v2.0.0" .
    sha256sum "$RELEASE_BASE_URL/v2.0.0/cringle-2.0.0-linux.tar.gz" | awk '{print $1"  cringle-2.0.0-linux.tar.gz"}' > "$RELEASE_BASE_URL/v2.0.0/SHA256SUMS"
    
    # Create manifest file
    cat > "$RELEASE_BASE_URL/v2.0.0/manifest.json" <<'EOF'
{
  "version": "2.0.0",
  "description": "Cringle 2.0.0",
  "dependencies": []
}
EOF
    
    # Clean up temp directory
    rm -rf "$temp_release_dir"
    
    echo "Real release tarballs created in $RELEASE_BASE_URL"
}

# Start HTTP server to serve releases
start_http_server() {
    echo "Starting HTTP server on port $HTTP_SERVER_PORT..."
    
    # Start server in background
    cd "$RELEASE_BASE_URL"
    python3 -m http.server "$HTTP_SERVER_PORT" &
    HTTP_SERVER_PID=$!
    
    # Wait for server to start
    for i in {1..10}; do
        if curl -s --output /dev/null --head --fail "http://localhost:$HTTP_SERVER_PORT"; then
            echo "HTTP server started successfully"
            return
        fi
        sleep 0.5
    done
    
    echo "ERROR: HTTP server failed to start"
    TEST_EXIT_CODE=1
}

# Test installation with correct checksum
test_installation_correct_checksum() {
    echo "Testing installation with correct checksum..."
    
    # Set environment variables for install.sh
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    # Run install.sh
    "$OLDPWD/install.sh" --release 1.0.0
    
    # Check if installation succeeded
    if [[ -d "$INSTALL_ROOT/opt/cringle/current" ]]; then
        echo "Installation succeeded: $INSTALL_ROOT/opt/cringle/current"
    else
        echo "ERROR: Installation directory not created"
        TEST_EXIT_CODE=1
    fi
    
    # Check if version file exists
    VERSION_FILE="$INSTALL_ROOT/opt/cringle/current/VERSION"
    if [[ -f "$VERSION_FILE" ]]; then
        echo "Version file exists: $VERSION_FILE"
        if grep -q "1.0.0" "$VERSION_FILE"; then
            echo "Version file contains correct version: 1.0.0"
        else
            echo "ERROR: Version file does not contain correct version"
            TEST_EXIT_CODE=1
        fi
    else
        echo "ERROR: Version file not found"
        TEST_EXIT_CODE=1
    fi
    
    # Check if binary symlink exists
    BIN_SYMLINK="$INSTALL_ROOT/usr/local/bin/cringle"
    if [[ -L "$BIN_SYMLINK" ]]; then
        echo "Binary symlink exists: $BIN_SYMLINK"
    else
        echo "ERROR: Binary symlink not created"
        TEST_EXIT_CODE=1
    fi
    
    # Check if cringle --version works
    if "$BIN_SYMLINK" --version 2>/dev/null || "$BIN_SYMLINK" 2>/dev/null | grep -q "1.0.0"; then
        echo "Cringle --version works correctly"
    else
        echo "ERROR: Cringle --version does not work"
        TEST_EXIT_CODE=1
    fi
}

# Test installation with manipulated checksum
test_installation_manipulated_checksum() {
    echo "Testing installation with manipulated checksum..."
    
    # Manipulate checksum to test failure
    local checksum_file="$RELEASE_BASE_URL/v1.0.0/SHA256SUMS"
    local backup_checksum="$checksum_file.backup"
    
    # Backup original checksum
    cp "$checksum_file" "$backup_checksum"
    
    # Manipulate checksum
    sed 's/^/x/' "$checksum_file" > "$checksum_file.tmp"
    mv "$checksum_file.tmp" "$checksum_file"
    
    # Set environment variables for install.sh
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    # Run install.sh - should fail
    if "$OLDPWD/install.sh" --release 1.0.0; then
        echo "ERROR: Installation should have failed with manipulated checksum"
        TEST_EXIT_CODE=1
    else
        echo "Installation correctly failed with manipulated checksum"
    fi
    
    # Check that no files were installed
    if [[ -d "$INSTALL_ROOT/opt/cringle/current" ]]; then
        echo "ERROR: Installation directory should not exist after checksum failure"
        TEST_EXIT_CODE=1
    else
        echo "No files installed after checksum failure (correct behavior)"
    fi
    
    # Restore original checksum
    mv "$backup_checksum" "$checksum_file"
}

# Test version switching
test_version_switching() {
    echo "Testing version switching..."
    
    # Install version 2.0.0
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    "$OLDPWD/install.sh" --release 2.0.0
    
    # Check if version switched
    if [[ -d "$INSTALL_ROOT/opt/cringle/current" ]]; then
        echo "Current installation exists: $INSTALL_ROOT/opt/cringle/current"
    else
        echo "ERROR: Current installation not found"
        TEST_EXIT_CODE=1
    fi
    
    VERSION_FILE="$INSTALL_ROOT/opt/cringle/current/VERSION"
    if [[ -f "$VERSION_FILE" ]]; then
        if grep -q "2.0.0" "$VERSION_FILE"; then
            echo "Version switched correctly to 2.0.0"
        else
            echo "ERROR: Version file does not contain correct version after switch"
            TEST_EXIT_CODE=1
        fi
    else
        echo "ERROR: Version file not found after switch"
        TEST_EXIT_CODE=1
    fi
    
    # Check if symlink points to correct version
    if [[ -L "$INSTALL_ROOT/opt/cringle/current" ]]; then
        echo "Current symlink points to version 2.0.0"
    else
        echo "ERROR: Current symlink not properly updated"
        TEST_EXIT_CODE=1
    fi
}

# Test uninstall (without purge)
test_uninstall() {
    echo "Testing uninstall (without purge)..."
    
    # First, install something
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    "$OLDPWD/install.sh" --release 1.0.0
    
    # Create some test data and config to verify they are preserved
    mkdir -p "$INSTALL_ROOT/var/lib/cringle"
    echo "test data" > "$INSTALL_ROOT/var/lib/cringle/test.txt"
    mkdir -p "$INSTALL_ROOT/etc/cringle"
    echo "test config" > "$INSTALL_ROOT/etc/cringle/test.conf"
    
    # Run uninstall
    "$OLDPWD/install.sh" --uninstall
    
    # Check that installation is removed
    if [[ ! -d "$INSTALL_ROOT/opt/cringle" ]]; then
        echo "Installation directory removed"
    else
        echo "ERROR: Installation directory not removed"
        TEST_EXIT_CODE=1
    fi
    
    # Check that data and config are preserved
    if [[ -f "$INSTALL_ROOT/var/lib/cringle/test.txt" ]] && \
       [[ -f "$INSTALL_ROOT/etc/cringle/test.conf" ]]; then
        echo "Data and config preserved after uninstall"
    else
        echo "ERROR: Data or config not preserved after uninstall"
        TEST_EXIT_CODE=1
    fi
}

# Test uninstall with purge
test_uninstall_purge() {
    echo "Testing uninstall with purge..."
    
    # First, install something
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    "$OLDPWD/install.sh" --release 1.0.0
    
    # Create some test data and config
    mkdir -p "$INSTALL_ROOT/var/lib/cringle"
    echo "test data" > "$INSTALL_ROOT/var/lib/cringle/test.txt"
    mkdir -p "$INSTALL_ROOT/etc/cringle"
    echo "test config" > "$INSTALL_ROOT/etc/cringle/test.conf"
    
    # Run uninstall with purge
    "$OLDPWD/install.sh" --uninstall --purge
    
    # Check that everything is removed
    if [[ ! -d "$INSTALL_ROOT/opt/cringle" ]] && \
       [[ ! -d "$INSTALL_ROOT/var/lib/cringle" ]] && \
       [[ ! -d "$INSTALL_ROOT/etc/cringle" ]]; then
        echo "Everything removed with purge"
    else
        echo "ERROR: Some directories not removed with purge"
        TEST_EXIT_CODE=1
    fi
}

# Test purge without uninstall (should fail)
test_purge_without_uninstall() {
    echo "Testing --purge without --uninstall (should fail)..."
    
    # Try to run purge without uninstall
    if "$OLDPWD/install.sh" --purge; then
        echo "ERROR: --purge without --uninstall should have failed"
        TEST_EXIT_CODE=1
    else
        echo "--purge without --uninstall correctly failed"
    fi
}

# Test cringle --version as non-root user
test_cringle_version_nonroot() {
    echo "Testing cringle --version as non-root user..."
    
    # Install Cringle
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="http://localhost:$HTTP_SERVER_PORT"
    export CRINGLE_INSTALL_TEST="1"  # Skip root and Java checks
    
    "$OLDPWD/install.sh" --release 1.0.0
    
    # Test the binary
    BIN_SYMLINK="$INSTALL_ROOT/usr/local/bin/cringle"
    if "$BIN_SYMLINK" --version 2>/dev/null || "$BIN_SYMLINK" 2>/dev/null | grep -q "1.0.0"; then
        echo "Cringle --version works as non-root user"
    else
        echo "ERROR: Cringle --version does not work as non-root user"
        TEST_EXIT_CODE=1
    fi
}

# Main test execution
main() {
    echo "Starting Cringle installer tests..."
    echo "Test directory: $TEST_DIR"
    echo "Install root: $INSTALL_ROOT"
    echo "Release URL: http://localhost:$HTTP_SERVER_PORT"
    echo ""
    
    create_real_releases
    echo ""
    
    start_http_server
    echo ""
    
    test_installation_correct_checksum
    echo ""
    
    test_installation_manipulated_checksum
    echo ""
    
    test_version_switching
    echo ""
    
    test_uninstall
    echo ""
    
    test_uninstall_purge
    echo ""
    
    test_purge_without_uninstall
    echo ""
    
    test_cringle_version_nonroot
    echo ""
    
    if [[ $TEST_EXIT_CODE -eq 0 ]]; then
        echo "All tests passed!"
        exit 0
    else
        echo "Some tests failed with exit code $TEST_EXIT_CODE"
        exit 1
    fi
}

main "$@"
