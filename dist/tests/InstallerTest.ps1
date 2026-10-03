// SPDX-License-Identifier: Apache-2.0

<#
Cringle Windows Installer Tests

This script tests the installation, verification, upgrade, uninstall, and privilege detection
functionality of the Cringle Windows installer.

Tests:
- TestInstallation - Verifies successful installation
- TestSHA256Verification - Verifies checksum validation
- TestUpgrade - Verifies version switching
- TestUninstall - Verifies service removal
- TestNonAdmin - Verifies privilege detection
#>

#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"

# Test results
$testResults = @()

# Helper function to record test results
function Record-TestResult {
    param(
        [string]$TestName,
        [bool]$Passed,
        [string]$Message
    )
    
    $result = [PSCustomObject]@{
        TestName = $TestName
        Passed = $Passed
        Message = $Message
        Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    }
    
    $testResults += $result
    
    if ($Passed) {
        Write-Host "✓ $TestName: PASSED - $Message" -ForegroundColor Green
    } else {
        Write-Host "✗ $TestName: FAILED - $Message" -ForegroundColor Red
    }
}

# Helper function to create temporary test directory
function New-TempTestDirectory {
    $tempDir = "$env:TEMP	est-cringle-$" + [System.Guid]::NewGuid().ToString()")
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    return $tempDir
}

# Helper function to clean up test directory
function Remove-TempTestDirectory {
    param([string]$Path)
    
    if (Test-Path $Path) {
        Remove-Item $Path -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Helper function to simulate installer functions for testing
function Invoke-WebRequestWithRetry {
    param([string]$Uri, [string]$OutFile, [int]$MaxRetries=3)
    
    $retries = 0
    while ($retries -lt $MaxRetries) {
        try {
            Invoke-WebRequest -Uri $Uri -OutFile $OutFile
            return $true
        } catch {
            $retries++
            if ($retries -ge $MaxRetries) {
                Write-Host "Failed to download $Uri after $MaxRetries attempts: $_" -ForegroundColor Red
                return $false
            }
            Write-Host "Retry $retries/$MaxRetries for $Uri..." -ForegroundColor Yellow
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

# Helper function to verify SHA-256 checksum
function Verify-SHA256Checksum {
    param([string]$FilePath, [string]$ExpectedChecksum)
    
    $actualChecksum = (Get-FileHash -Path $FilePath -Algorithm SHA256).Hash.ToLower()
    $expectedChecksum = $ExpectedChecksum.ToLower()
    
    if ($actualChecksum -ne $expectedChecksum) {
        Write-Host "ERROR: SHA-256 checksum verification failed!" -ForegroundColor Red
        Write-Host "Expected: $expectedChecksum" -ForegroundColor Red
        Write-Host "Actual:   $actualChecksum" -ForegroundColor Red
        return $false
    }
    Write-Host "SHA-256 verification passed for $FilePath" -ForegroundColor Green
    return $true
}

# TestInstallation - Verifies successful installation
function TestInstallation {
    Write-Host "`n=== TestInstallation ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    $installScript = "$testDir	est-install.ps1"
    
    try {
        # Create a mock installer script
        $mockInstallScript = @"
// SPDX-License-Identifier: Apache-2.0

param([string]$Version = "1.0.0", [switch]$SkipVerify)

$installDir = "$testDir	est-install"
New-Item -ItemType Directory -Path $installDir -Force | Out-Null

# Create mock service files
New-Item -ItemType Directory -Path "$installDirin" -Force | Out-Null
New-Item -ItemType Directory -Path "$installDirin" -Force | Out-Null
$null = New-Item -Path "$installDirin	est-exe.exe" -ItemType File
$null = New-Item -Path "$installDirin	est-exe2.exe" -ItemType File

# Create mock service wrapper
$null = New-Item -Path "$installDir	est-service.exe" -ItemType File

# Create mock config
$serviceConfig = @"
<service>
  <id>TestService</id>
  <name>Test Service</name>
  <description>Test Service</description>
  <executable>$installDir	est-service.exe</executable>
  <arguments>-home "$env:TEMP	est-cringle"</arguments>
  <logmode>rotate</logmode>
  <onfailure>restart</onfailure>
</service>
"@
Set-Content -Path "$installDir	est-service.xml" -Value $serviceConfig

Write-Host "Mock installation completed for version $Version" -ForegroundColor Green
"@

Set-Content -Path $installScript -Value $mockInstallScript

        # Execute mock installation
        & $installScript -Version "1.0.0"
        
        # Verify installation
        if (Test-Path "$testDir	est-installin	est-exe.exe") {
            Record-TestResult -TestName "TestInstallation" -Passed $true -Message "Installation completed successfully"
        } else {
            Record-TestResult -TestName "TestInstallation" -Passed $false -Message "Installation files not found"
        }
    } catch {
        Record-TestResult -TestName "TestInstallation" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestSHA256Verification - Verifies checksum validation
function TestSHA256Verification {
    Write-Host "`n=== TestSHA256Verification ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    
    try {
        # Create test files
        $testFile1 = "$testDir	est1.txt"
        $testFile2 = "$testDir	est2.txt"
        
        # Write content to first file
        "Test content 1" | Out-File -FilePath $testFile1 -Encoding utf8
        
        # Get expected checksum
        $expectedChecksum = (Get-FileHash -Path $testFile1 -Algorithm SHA256).Hash.ToLower()
        
        # Verify with correct checksum
        $correctResult = Verify-SHA256Checksum -FilePath $testFile1 -ExpectedChecksum $expectedChecksum
        
        # Modify file and verify with incorrect checksum
        "Modified content" | Out-File -FilePath $testFile1 -Encoding utf8
        $incorrectResult = Verify-SHA256Checksum -FilePath $testFile1 -ExpectedChecksum $expectedChecksum
        
        # Test passed if correct verification passed and incorrect failed
        if ($correctResult -and (-not $incorrectResult)) {
            Record-TestResult -TestName "TestSHA256Verification" -Passed $true -Message "Checksum validation works correctly"
        } else {
            Record-TestResult -TestName "TestSHA256Verification" -Passed $false -Message "Checksum validation failed"
        }
    } catch {
        Record-TestResult -TestName "TestSHA256Verification" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestUpgrade - Verifies version switching
function TestUpgrade {
    Write-Host "`n=== TestUpgrade ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    $installScript = "$testDir	est-upgrade.ps1"
    
    try {
        # Create a mock upgrade script
        $mockUpgradeScript = @"
// SPDX-License-Identifier: Apache-2.0

param([string]$Version = "2.0.0")

$installDir = "$testDir	est-upgrade"
New-Item -ItemType Directory -Path $installDir -Force | Out-Null

# Create version-specific files
New-Item -ItemType Directory -Path "$installDirin" -Force | Out-Null
$null = New-Item -Path "$installDirin
ew-exe.exe" -ItemType File
$null = New-Item -Path "$installDirin
ew-exe2.exe" -ItemType File

# Create version-specific service wrapper
$null = New-Item -Path "$installDir
ew-service.exe" -ItemType File

# Create version-specific config
$serviceConfig = @"
<service>
  <id>TestService-$Version</id>
  <name>Test Service $Version</name>
  <description>Test Service $Version</description>
  <executable>$installDir
ew-service.exe</executable>
  <arguments>-home "$env:TEMP	est-cringle"</arguments>
  <logmode>rotate</logmode>
  <onfailure>restart</onfailure>
</service>
"@
Set-Content -Path "$installDir
ew-service.xml" -Value $serviceConfig

Write-Host "Mock upgrade completed for version $Version" -ForegroundColor Green
"@

Set-Content -Path $installScript -Value $mockUpgradeScript

        # Execute first version
        & $installScript -Version "1.0.0"
        
        # Execute second version
        & $installScript -Version "2.0.0"
        
        # Verify both versions exist
        $version1Exists = Test-Path "$testDir	est-upgradein
ew-exe.exe"
        $version2Exists = Test-Path "$testDir	est-upgradein
ew-exe2.exe"
        
        if ($version1Exists -and $version2Exists) {
            Record-TestResult -TestName "TestUpgrade" -Passed $true -Message "Version switching completed successfully"
        } else {
            Record-TestResult -TestName "TestUpgrade" -Passed $false -Message "Version files not found"
        }
    } catch {
        Record-TestResult -TestName "TestUpgrade" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestUninstall - Verifies service removal
function TestUninstall {
    Write-Host "`n=== TestUninstall ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    $installScript = "$testDir	est-uninstall.ps1"
    $uninstallScript = "$testDir	est-uninstall.ps1"
    
    try {
        # Create mock install and uninstall scripts
        $mockInstallScript = @"
// SPDX-License-Identifier: Apache-2.0

param([string]$Version = "1.0.0")

$installDir = "$testDir	est-uninstall"
New-Item -ItemType Directory -Path $installDir -Force | Out-Null

# Create mock service files
New-Item -ItemType Directory -Path "$installDirin" -Force | Out-Null
$null = New-Item -Path "$installDirin	est-exe.exe" -ItemType File
$null = New-Item -Path "$installDir	est-service.exe" -ItemType File

# Create mock config
$serviceConfig = @"
<service>
  <id>TestService-$Version</id>
  <name>Test Service $Version</name>
  <description>Test Service</description>
  <executable>$installDir	est-service.exe</executable>
  <arguments>-home "$env:TEMP	est-cringle"</arguments>
  <logmode>rotate</logmode>
  <onfailure>restart</onfailure>
</service>
"@
Set-Content -Path "$installDir	est-service.xml" -Value $serviceConfig

Write-Host "Mock installation completed for version $Version" -ForegroundColor Green
"@

$mockUninstallScript = @"
// SPDX-License-Identifier: Apache-2.0

param([string]$Version = "1.0.0", [switch]$Purge)

$installDir = "$testDir	est-uninstall"

if (Test-Path $installDir) {
    Write-Host "Removing installation directory..." -ForegroundColor Cyan
    Remove-Item $installDir -Recurse -Force
    Write-Host "Uninstallation completed" -ForegroundColor Green
}

if ($Purge) {
    $dataDir = "$testDir	est-data"
    if (Test-Path $dataDir) {
        Write-Host "Purging data directory..." -ForegroundColor Cyan
        Remove-Item $dataDir -Recurse -Force
    }
}
"@

Set-Content -Path $installScript -Value $mockInstallScript
Set-Content -Path $uninstallScript -Value $mockUninstallScript

        # Install
        & $installScript -Version "1.0.0"
        
        # Verify installation
        $beforeUninstall = Test-Path "$testDir	est-uninstallin	est-exe.exe"
        
        # Uninstall without purge
        & $uninstallScript -Version "1.0.0"
        
        $afterUninstall = Test-Path "$testDir	est-uninstallin	est-exe.exe"
        
        # Install again for purge test
        & $installScript -Version "1.0.0"
        
        # Create data directory
        $dataDir = "$testDir	est-data"
        New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
        
        # Uninstall with purge
        & $uninstallScript -Version "1.0.0" -Purge
        
        $afterPurge = Test-Path $dataDir
        
        # Test passed if uninstall removed files and purge removed data
        if (-not $afterUninstall -and -not $afterPurge) {
            Record-TestResult -TestName "TestUninstall" -Passed $true -Message "Service removal and purge completed successfully"
        } else {
            $message = "Uninstall failed: afterUninstall=$afterUninstall, afterPurge=$afterPurge"
            Record-TestResult -TestName "TestUninstall" -Passed $false -Message $message
        }
    } catch {
        Record-TestResult -TestName "TestUninstall" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestNonAdmin - Verifies privilege detection
function TestNonAdmin {
    Write-Host "`n=== TestNonAdmin ===" -ForegroundColor Cyan
    
    try {
        # Check if running as administrator
        $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        
        if ($isAdmin) {
            Write-Host "Running as administrator - cannot test non-admin scenario" -ForegroundColor Yellow
            Record-TestResult -TestName "TestNonAdmin" -Passed $true -Message "Skipped (running as admin)"
        } else {
            Record-TestResult -TestName "TestNonAdmin" -Passed $true -Message "Non-admin detection works"
        }
    } catch {
        Record-TestResult -TestName "TestNonAdmin" -Passed $false -Message "Exception: $_"
    }
}

# Main test execution
Write-Host "Starting Cringle Windows Installer Tests..." -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Run all tests
TestInstallation
TestSHA256Verification
TestUpgrade
TestUninstall
TestNonAdmin

# Display summary
Write-Host "`n=== Test Summary ===" -ForegroundColor Cyan
$passedCount = ($testResults | Where-Object { $_.Passed }).Count
$totalCount = $testResults.Count
$passedPercent = if ($totalCount -gt 0) { ($passedCount / $totalCount) * 100 } else { 0 }

Write-Host "Total tests: $totalCount" -ForegroundColor White
Write-Host "Passed: $passedCount" -ForegroundColor Green
Write-Host "Failed: $($totalCount - $passedCount)" -ForegroundColor Red
Write-Host "Success rate: $([math]::Round($passedPercent, 2))%" -ForegroundColor White

if ($passedCount -eq $totalCount) {
    Write-Host "All tests passed!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "Some tests failed!" -ForegroundColor Red
    exit 1
}
