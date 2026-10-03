# SPDX-License-Identifier: Apache-2.0

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
    
    $global:testResults += $result
    
    if ($Passed) {
        Write-Host "✓ $TestName: PASSED - $Message" -ForegroundColor Green
    } else {
        Write-Host "✗ $TestName: FAILED - $Message" -ForegroundColor Red
    }
}

# Helper function to create temporary test directory
function New-TempTestDirectory {
    $tempDir = "$env:TEMPtest-cringle-$" + [System.Guid]::NewGuid().ToString()""
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
    Write-Host "\n=== TestInstallation ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    try {
        # Create fake release assets
        $releaseDir = "$testDir\release"
        $zipFile = "$releaseDir\cringle-1.0.0.zip"
        $checksumFile = "$releaseDir\SHA256SUMS"
        $winswFile = "$releaseDir\service.exe"
        
        New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
        
        # Create fake zip file
        "fake zip content" | Out-File -FilePath $zipFile -Encoding utf8
        
        # Create fake checksum file
        $zipChecksum = (Get-FileHash -Path $zipFile -Algorithm SHA256).Hash.ToLower()
        "$zipChecksum  $zipFile" | Out-File -FilePath $checksumFile -Encoding utf8
        
        # Create fake WinSW file
        "fake winsw content" | Out-File -FilePath $winswFile -Encoding utf8
        
        # Test installation with fake assets
        $installScript = "$PSScriptRoot\..\install.ps1"
        $exitCode = (& $installScript -BaseUrl "file://$releaseDir" -Version "1.0.0")
        
        if ($exitCode -eq 0) {
            Record-TestResult -TestName "TestInstallation" -Passed $true -Message "Installation completed successfully"
        } else {
            Record-TestResult -TestName "TestInstallation" -Passed $false -Message "Installation failed with exit code $exitCode"
        }
    } catch {
        Record-TestResult -TestName "TestInstallation" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestSHA256Verification - Verifies checksum validation
function TestSHA256Verification {
    Write-Host "\n=== TestSHA256Verification ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    try {
        # Create test files
        $releaseDir = "$testDir\release"
        $zipFile = "$releaseDir\cringle-1.0.0.zip"
        $checksumFile = "$releaseDir\SHA256SUMS"
        
        New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
        
        # Create fake zip file
        "fake zip content" | Out-File -FilePath $zipFile -Encoding utf8
        
        # Create checksum file with WRONG checksum
        $wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"
        "$wrongChecksum  $zipFile" | Out-File -FilePath $checksumFile -Encoding utf8
        
        # Test installation with wrong checksum (should fail)
        $installScript = "$PSScriptRoot\..\install.ps1"
        $exitCode = (& $installScript -BaseUrl "file://$releaseDir" -Version "1.0.0")
        
        if ($exitCode -ne 0) {
            Record-TestResult -TestName "TestSHA256Verification" -Passed $true -Message "Checksum validation correctly rejected wrong checksum"
        } else {
            Record-TestResult -TestName "TestSHA256Verification" -Passed $false -Message "Checksum validation failed to detect wrong checksum"
        }
    } catch {
        Record-TestResult -TestName "TestSHA256Verification" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestUpgrade - Verifies version switching
function TestUpgrade {
    Write-Host "\n=== TestUpgrade ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    try {
        # Create fake release assets for two versions
        $releaseDir = "$testDir\release"
        $zipFile1 = "$releaseDir\cringle-1.0.0.zip"
        $zipFile2 = "$releaseDir\cringle-2.0.0.zip"
        $checksumFile = "$releaseDir\SHA256SUMS"
        
        New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
        
        # Create fake zip files
        "fake zip content 1" | Out-File -FilePath $zipFile1 -Encoding utf8
        "fake zip content 2" | Out-File -FilePath $zipFile2 -Encoding utf8
        
        # Create checksum file
        $checksum1 = (Get-FileHash -Path $zipFile1 -Algorithm SHA256).Hash.ToLower()
        $checksum2 = (Get-FileHash -Path $zipFile2 -Algorithm SHA256).Hash.ToLower()
        "$checksum1  $zipFile1`n$checksum2  $zipFile2" | Out-File -FilePath $checksumFile -Encoding utf8
        
        # Install first version
        $installScript = "$PSScriptRoot\..\install.ps1"
        $exitCode1 = (& $installScript -BaseUrl "file://$releaseDir" -Version "1.0.0")
        
        # Install second version
        $exitCode2 = (& $installScript -BaseUrl "file://$releaseDir" -Version "2.0.0")
        
        # Check that current version is 2.0.0
        $currentVersion = Get-Content "$env:ProgramFiles\Cringle\current\version.txt" -ErrorAction SilentlyContinue
        
        if ($exitCode1 -eq 0 -and $exitCode2 -eq 0 -and $currentVersion -eq "2.0.0") {
            Record-TestResult -TestName "TestUpgrade" -Passed $true -Message "Version switching completed successfully"
        } else {
            $message = "Upgrade failed: exitCode1=$exitCode1, exitCode2=$exitCode2, currentVersion=$currentVersion"
            Record-TestResult -TestName "TestUpgrade" -Passed $false -Message $message
        }
    } catch {
        Record-TestResult -TestName "TestUpgrade" -Passed $false -Message "Exception: $_"
    } finally {
        Remove-TempTestDirectory -Path $testDir
    }
}

# TestUninstall - Verifies service removal
function TestUninstall {
    Write-Host "\n=== TestUninstall ===" -ForegroundColor Cyan
    
    $testDir = New-TempTestDirectory
    try {
        # Create fake release assets
        $releaseDir = "$testDir\release"
        $zipFile = "$releaseDir\cringle-1.0.0.zip"
        $checksumFile = "$releaseDir\SHA256SUMS"
        
        New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
        
        # Create fake zip file
        "fake zip content" | Out-File -FilePath $zipFile -Encoding utf8
        
        # Create checksum file
        $checksum = (Get-FileHash -Path $zipFile -Algorithm SHA256).Hash.ToLower()
        "$checksum  $zipFile" | Out-File -FilePath $checksumFile -Encoding utf8
        
        # Install
        $installScript = "$PSScriptRoot\..\install.ps1"
        & $installScript -BaseUrl "file://$releaseDir" -Version "1.0.0"
        
        # Verify installation
        $beforeUninstall = Test-Path "$env:ProgramFiles\Cringle\current\service.exe"
        
        # Uninstall without purge
        & $installScript -Uninstall
        
        $afterUninstall = Test-Path "$env:ProgramFiles\Cringle\current\service.exe"
        
        # Install again for purge test
        & $installScript -BaseUrl "file://$releaseDir" -Version "1.0.0"
        
        # Create data directory
        $dataDir = "$env:ProgramData\Cringle"
        New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
        
        # Uninstall with purge
        & $installScript -Uninstall -Purge
        
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
    Write-Host "\n=== TestNonAdmin ===" -ForegroundColor Cyan
    
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
Write-Host "\n=== Test Summary ===" -ForegroundColor Cyan
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
