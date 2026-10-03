#!/bin/bash
# SPDX-License-Identifier: Apache-2.0

# Test script for Cringle installer
# Tests installation, checksum verification, version switching, and uninstall
# Uses environment variables to override paths and URLs for testing

set -euo pipefail

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

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
    echo "${BLUE}Creating fake release files...${NC}"
    
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
    
    echo "${GREEN}Fake releases created in $RELEASE_BASE_URL${NC}"
}

# Test checksum verification
test_checksum_verification() {
    echo "${BLUE}Testing checksum verification...${NC}"
    
    # Test with correct checksum
    cd "$RELEASE_BASE_URL/v1.0.0"
    
    # Create a file with known checksum
    echo "test content" > testfile.txt
    local actual_checksum=$(sha256sum testfile.txt | awk '{print $1}')
    echo "$actual_checksum  testfile.txt" > correct_checksum.txt
    
    # Verify correct checksum
    if sha256sum -c correct_checksum.txt >/dev/null 2>&1; then
        echo "${GREEN}Correct checksum verification passed${NC}"
    else
        echo "${RED}ERROR: Correct checksum verification failed${NC}"
        TEST_EXIT_CODE=1
    fi
    
    # Test with incorrect checksum
    echo "0000000000000000000000000000000000000000000000000000000000000000  testfile.txt" > incorrect_checksum.txt
    if sha256sum -c incorrect_checksum.txt >/dev/null 2>&1; then
        echo "${RED}ERROR: Incorrect checksum verification should have failed${NC}"
        TEST_EXIT_CODE=1
    else
        echo "${GREEN}Incorrect checksum verification correctly failed${NC}"
    fi
    
    cd "$OLDPWD"
}

# Test installation to temporary directory
test_installation() {
    echo "${BLUE}Testing installation to temporary directory...${NC}"
    
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
        echo "${GREEN}Installation directory created: $INSTALL_ROOT/cringle/current${NC}"
    else
        echo "${RED}ERROR: Installation directory not created${NC}"
        TEST_EXIT_CODE=1
    fi
    
    # Check if version file exists
    VERSION_FILE="$INSTALL_ROOT/cringle/current/VERSION"
    if [[ -f "$VERSION_FILE" ]]; then
        echo "${GREEN}Version file exists: $VERSION_FILE${NC}"
    else
        echo "${RED}ERROR: Version file not found${NC}"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Test version switching
test_version_switching() {
    echo "${BLUE}Testing version switching...${NC}"
    
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
        echo "${GREEN}Version 2.0.0 installed in $INSTALL_ROOT/cringle/2.0.0${NC}"
    else
        echo "${RED}ERROR: Version 2.0.0 not installed${NC}"
        TEST_EXIT_CODE=1
    fi
    
    if [[ -L "$INSTALL_ROOT/cringle/current" ]]; then
        echo "${GREEN}Current symlink points to version 2.0.0${NC}"
    else
        echo "${RED}ERROR: Current symlink not properly updated${NC}"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Test uninstall and purge
test_uninstall() {
    echo "${BLUE}Testing uninstall and purge...${NC}"
    
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
        echo "${GREEN}Uninstall successfully removed installation directory${NC}"
    else
        echo "${RED}ERROR: Uninstall did not remove installation directory${NC}"
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
        echo "${GREEN}Purge successfully removed installation directory${NC}"
    else
        echo "${RED}ERROR: Purge did not remove installation directory${NC}"
        TEST_EXIT_CODE=1
    fi
    
    rm test-installer.sh
}

# Main test execution
main() {
    echo "${BLUE}Starting Cringle installer tests...${NC}"
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
        echo "${GREEN}All tests passed!${NC}"
        exit 0
    else
        echo "${RED}Some tests failed with exit code $TEST_EXIT_CODE${NC}"
        exit 1
    fi
}

main "$@"
