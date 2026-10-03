# Test Script for Cringle Installer

## Overview

The `scripts/test-install.sh` script provides comprehensive testing for the Cringle installer (`install.sh`). It tests all major functionality without requiring root privileges or network access.

## Features

### 1. Fake Release Creation
- Creates fake release archives (`cringle-<version>-linux.tar.gz`)
- Generates SHA256 checksum files
- Creates manifest JSON files
- All files are placed in a configurable release directory

### 2. Checksum Verification Testing
- Tests correct checksum verification (should pass)
- Tests incorrect checksum verification (should fail)
- Uses actual SHA-256 checksum calculations

### 3. Installation Testing
- Tests installation to a temporary directory
- Verifies installation directory creation
- Checks version file existence
- Uses environment variables for path configuration

### 4. Version Switching Testing
- Tests switching between versions (1.0.0 → 2.0.0)
- Verifies version-specific directories are created
- Checks symlink updates for "current" version

### 5. Uninstall and Purge Testing
- Tests uninstall functionality (removes installation)
- Tests purge functionality (removes installation and data)
- Verifies complete cleanup

## Environment Variables

The script supports these environment variables:

- `CRINGLE_INSTALL_ROOT`: Overrides the installation root directory (default: temporary directory)
- `CRINGLE_RELEASE_BASE_URL`: Overrides the release base URL (default: temporary directory)

## Usage

```bash
# Run with default settings
./scripts/test-install.sh

# Run with custom paths
CRINGLE_INSTALL_ROOT=/path/to/install CRINGLE_RELEASE_BASE_URL=/path/to/releases ./scripts/test-install.sh
```

## Exit Codes

- `0`: All tests passed successfully
- `1`: One or more tests failed

## Test Coverage

The script tests all acceptance criteria from issue #58:
- ✅ Fake release file creation
- ✅ Checksum verification (pass and fail)
- ✅ Installation to temporary directory
- ✅ Version switching
- ✅ Uninstall functionality
- ✅ Purge functionality
- ✅ Environment variable support

## Cleanup

The script automatically cleans up temporary directories on exit, ensuring no leftover files.
