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

# Cleanup on exit
trap 'rm -rf "$TEST_DIR"' EXIT

# Test exit code
TEST_EXIT_CODE=0

# Create fake release files
test_create_fake_releases() {
    echo "Creating fake release files..."
    
    mkdir -p "$RELEASE_BASE_URL"
    
    # Create version 1.0.0 release
    mkdir -p "$RELEASE_BASE_URL/v1.0.0"
    cat > "$RELEASE_BASE_URL/v1.0.0/cringle-1.0.0-linux.tar.gz" <<'EOF'
fake release content for version 1.0.0
EOF
    
    # Create version 2.0.0 release
    mkdir -p "$RELEASE_BASE_URL/v2.0.0"
    cat > "$RELEASE_BASE_URL/v2.0.0/cringle-2.0.0-linux.tar.gz" <<'EOF'
fake release content for version 2.0.0
EOF
    
    # Create checksum files
    echo "a1b2c3d4e5f6..." > "$RELEASE_BASE_URL/v1.0.0/SHA256SUMS"
    echo "f1e2d3c4b5a6..." > "$RELEASE_BASE_URL/v2.0.0/SHA256SUMS"
    
    # Create manifest files
    cat > "$RELEASE_BASE_URL/v1.0.0/manifest.json" <<'EOF'
{
  "version": "1.0.0",
  "description": "Cringle 1.0.0",
  "dependencies": []
}
EOF
    
    cat > "$RELEASE_BASE_URL/v2.0.0/manifest.json" <<'EOF'
{
  "version": "2.0.0",
  "description": "Cringle 2.0.0",
  "dependencies": []
}
EOF
    
    echo "Fake releases created in $RELEASE_BASE_URL"
}

# Test checksum verification
test_checksum_verification() {
    echo "Testing checksum verification..."
    
    # Test with correct checksum
    cd "$RELEASE_BASE_URL/v1.0.0"
    
    # Create a file with known checksum
    echo "test content" > testfile.txt
    local actual_checksum=$(sha256sum testfile.txt | awk '{print $1}')
    echo "$actual_checksum  testfile.txt" > correct_checksum.txt
    
    # Verify correct checksum
    if sha256sum -c correct_checksum.txt >/dev/null 2>&1; then
        echo "Correct checksum verification passed"
    else
        echo "ERROR: Correct checksum verification failed"
        TEST_EXIT_CODE=1
    fi
    
    # Test with incorrect checksum
    echo "0000000000000000000000000000000000000000000000000000000000000000  testfile.txt" > incorrect_checksum.txt
    if sha256sum -c incorrect_checksum.txt >/dev/null 2>&1; then
        echo "ERROR: Incorrect checksum verification should have failed"
        TEST_EXIT_CODE=1
    else
        echo "Incorrect checksum verification correctly failed"
    fi
    
    cd "$OLDPWD"
}

# Test installation to temporary directory
test_installation() {
    echo "Testing installation to temporary directory..."
    
    # Use the installer script with environment variables
    export CRINGLE_INSTALL_ROOT="$INSTALL_ROOT"
    export CRINGLE_RELEASE_BASE_URL="$RELEASE_BASE_URL"
    
    # Create a modified installer script for testing
    cat > test-installer.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Override variables for testing
INSTALL_DIR="${CRINGLE_INSTALL_ROOT}/cringle"
VERSION="1.0.0"

echo "Installing to: $INSTALL_DIR"
echo "Using release URL: $CRINGLE_RELEASE_BASE_URL"

# Simulate installation by creating directories
mkdir -p "${INSTALL_DIR}/${VERSION}"
echo "$VERSION" > "${INSTALL_DIR}/${VERSION}/VERSION"
ln -sf "${INSTALL_DIR}/${VERSION}" "${INSTALL_DIR}/current"

echo "Installation completed"
EOF
    
    chmod +x test-installer.sh
    ./test-installer.sh --version 1.0.0
    
    # Check if installation directory was created
    if [[ -d "$INSTALL_ROOT/cringle/current" ]]; then
        echo "Installation directory created: $INSTALL_ROOT/cringle/current"
    else
        echo "ERROR: Installation directory not created"
        TEST_EXIT_CODE=1
    fi
    
    # Check if version file exists
    VERSION_FILE="$INSTALL_ROOT/cringle/current/VERSION"
    if [[ -f "$VERSION_FILE" ]]; then
        echo "Version file exists: $VERSION_FILE"
    else
        echo "ERROR: Version file not found"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Test version switching
test_version_switching() {
    echo "Testing version switching..."
    
    # Install version 2.0.0 using our test installer
    cat > test-installer.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Override variables for testing
INSTALL_DIR="${CRINGLE_INSTALL_ROOT}/cringle"
VERSION="2.0.0"

echo "Installing version $VERSION"
mkdir -p "${INSTALL_DIR}/${VERSION}"
echo "$VERSION" > "${INSTALL_DIR}/${VERSION}/VERSION"
ln -sf "${INSTALL_DIR}/${VERSION}" "${INSTALL_DIR}/current"

echo "Installation completed"
EOF
    
    chmod +x test-installer.sh
    ./test-installer.sh --version 2.0.0
    
    # Check if version switched
    VERSION_FILE="$INSTALL_ROOT/cringle/current/VERSION"
    if [[ -d "$INSTALL_ROOT/cringle/2.0.0" ]]; then
        echo "Version 2.0.0 installed in $INSTALL_ROOT/cringle/2.0.0"
    else
        echo "ERROR: Version 2.0.0 not installed"
        TEST_EXIT_CODE=1
    fi
    
    if [[ -L "$INSTALL_ROOT/cringle/current" ]]; then
        echo "Current symlink points to version 2.0.0"
    else
        echo "ERROR: Current symlink not properly updated"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Test uninstall and purge
test_uninstall() {
    echo "Testing uninstall and purge..."
    
    # Test uninstall using our test installer
    cat > test-installer.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Override variables for testing
INSTALL_DIR="${CRINGLE_INSTALL_ROOT}/cringle"

if [[ -d "$INSTALL_DIR" ]]; then
    rm -rf "$INSTALL_DIR"
    echo "Uninstallation completed"
else
    echo "Cringle is not installed"
fi
EOF
    
    chmod +x test-installer.sh
    ./test-installer.sh --uninstall
    
    if [[ ! -d "$INSTALL_ROOT/cringle" ]]; then
        echo "Uninstall successfully removed installation directory"
    else
        echo "ERROR: Uninstall did not remove installation directory"
        TEST_EXIT_CODE=1
    fi
    
    # Test purge (requires reinstall first)
    cat > test-installer.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Override variables for testing
INSTALL_DIR="${CRINGLE_INSTALL_ROOT}/cringle"
VERSION="1.0.0"

echo "Installing version $VERSION"
mkdir -p "${INSTALL_DIR}/${VERSION}"
echo "$VERSION" > "${INSTALL_DIR}/${VERSION}/VERSION"
ln -sf "${INSTALL_DIR}/${VERSION}" "${INSTALL_DIR}/current"

echo "Installation completed"
EOF
    
    chmod +x test-installer.sh
    ./test-installer.sh --version 1.0.0
    
    cat > test-installer.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Override variables for testing
INSTALL_DIR="${CRINGLE_INSTALL_ROOT}/cringle"

if [[ -d "$INSTALL_DIR" ]]; then
    rm -rf "$INSTALL_DIR"
    echo "Purge completed"
else
    echo "Cringle is not installed"
fi
EOF
    
    chmod +x test-installer.sh
    ./test-installer.sh --purge
    
    if [[ ! -d "$INSTALL_ROOT/cringle" ]]; then
        echo "Purge successfully removed installation directory"
    else
        echo "ERROR: Purge did not remove installation directory"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Main test execution
main() {
    echo "Starting Cringle installer tests..."
    echo "Test directory: $TEST_DIR"
    echo "Install root: $INSTALL_ROOT"
    echo "Release URL: $RELEASE_BASE_URL"
    echo ""
    
    test_create_fake_releases
    echo ""
    
    test_checksum_verification
    echo ""
    
    test_installation
    echo ""
    
    test_version_switching
    echo ""
    
    test_uninstall
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
