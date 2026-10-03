# SPDX-License-Identifier: Apache-2.0

<#

Cringle Windows Service Installer

This script installs Cringle as a Windows service using WinSW.

Parameters:
  -Uninstall    : Uninstall the service and remove installation files
  -Purge       : Also remove data directory (use with -Uninstall)
  -Version     : Specific version to install (default: latest)
  -BaseUrl     : Base URL for testing (overrides default repository)
  -WithManagement : Install management server

Examples:
  .\install.ps1 -Version 1.0.0
  .\install.ps1 -Uninstall
  .\install.ps1 -Uninstall -Purge
#>

# Check if running as administrator
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: This script requires administrator privileges." -ForegroundColor Red
    exit 1
}

$ErrorActionPreference = "Stop"

# Parameters
param(
    [string]$Version = "latest",
    [switch]$Uninstall,
    [switch]$Purge,
    [string]$BaseUrl,
    [switch]$WithManagement
)

# Constants
$PROGRAM_FILES = "$env:ProgramFiles"
$PROGRAM_DATA = "$env:ProgramData"
$CRINGLE_BASE = "$PROGRAM_FILES\Cringle"
$SERVICE_DIR = "$CRINGLE_BASE\service"
$CRINGLE_CURRENT = "$CRINGLE_BASE\current"
$CRINGLE_SERVICE = "$CRINGLE_CURRENT\service.exe"
# WinSW should be downloaded from the same release as the distribution
$WINSW_URL = "$BaseUrl/cringle-$Version-windows-winsw.zip"
$WINSW_CHECKSUM_URL = "$BaseUrl/SHA256SUMS"

# Download and install WinSW if not present
function Install-WinSW {
    # Create service directory if it doesn't exist
    if (-not (Test-Path $SERVICE_DIR)) {
        New-Item -ItemType Directory -Path $SERVICE_DIR -Force | Out-Null
    }
    
    # Download WinSW from release assets
    $tempDir = New-TemporaryFile | Select-Object -ExpandProperty DirectoryName
    try {
        $winSWZip = "$tempDir\cringle-$Version-windows-winsw.zip"
        $checksumFile = "$tempDir\SHA256SUMS"
        
        # Download WinSW package
        if (-not (Invoke-WebRequestWithRetry -Uri $WINSW_URL -OutFile $winSWZip)) {
            Write-Host "ERROR: Failed to download WinSW" -ForegroundColor Red
            exit 1
        }
        
        # Download checksums
        if (-not (Invoke-WebRequestWithRetry -Uri $WINSW_CHECKSUM_URL -OutFile $checksumFile)) {
            Write-Host "ERROR: Failed to download SHA256SUMS for WinSW" -ForegroundColor Red
            exit 1
        }
        
        # Extract checksum for WinSW
        $checksumLine = Get-Content $checksumFile | Where-Object { $_ -like "cringle-$Version-windows-winsw.zip *" }
        if (-not $checksumLine) {
            Write-Host "ERROR: SHA256SUMS line for WinSW not found" -ForegroundColor Red
            exit 1
        }
        $winSWChecksum = $checksumLine.Trim().Split(" ")[0]
        
        # Verify checksum
        if (-not (Verify-SHA256Checksum -FilePath $winSWZip -ExpectedChecksum $winSWChecksum)) {
            exit 1
        }
        
        # Extract WinSW to service directory
        Write-Host "Extracting WinSW to $SERVICE_DIR..." -ForegroundColor Cyan
        Expand-Archive -Path $winSWZip -DestinationPath $SERVICE_DIR -Force
        
        # Rename WinSW executable
        $winSWExe = "$SERVICE_DIR\cringle-daemon.exe"
        if (Test-Path "$SERVICE_DIR\WinSW.exe") {
            Rename-Item "$SERVICE_DIR\WinSW.exe" $winSWExe
        }
        
        Write-Host "WinSW installed at $winSWExe" -ForegroundColor Green
    } finally {
        # Clean up temp files
        if (Test-Path $tempDir) {
            Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Download file with retry logic
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

# Verify SHA-256 checksum
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

# Download and verify distribution
function Download-Distribution {
    param([string]$Version)
    
    $actualBaseUrl = if ($BaseUrl) { $BaseUrl } else { "https://github.com/Tim-Meyran/Cringle/releases/download/v$Version" }
    $zipPath = "$CRINGLE_BASE\$Version"
    
    Write-Host "Downloading Cringle version $Version..." -ForegroundColor Cyan
    
    # Create temp directory for verification
    $tempDir = New-TemporaryFile | Select-Object -ExpandProperty DirectoryName
    try {
        $zipFile = "$tempDir\cringle-$Version-windows.zip"
        $shaFile = "$tempDir\SHA256SUMS"
        
        # Download distribution
        if (-not (Invoke-WebRequestWithRetry -Uri "$actualBaseUrl/cringle-$Version-windows.zip" -OutFile $zipFile)) {
            Write-Host "ERROR: Failed to download distribution" -ForegroundColor Red
            exit 1
        }
        
        # Download SHA256SUMS
        if (-not (Invoke-WebRequestWithRetry -Uri "$actualBaseUrl/SHA256SUMS" -OutFile $shaFile)) {
            Write-Host "ERROR: Failed to download SHA256SUMS" -ForegroundColor Red
            exit 1
        }
        
        # Extract checksum for the zip file
        $checksumLine = Get-Content $shaFile | Where-Object { $_ -like "cringle-$Version-windows.zip *" }
        if (-not $checksumLine) {
            Write-Host "ERROR: SHA256SUMS line for cringle-$Version-windows.zip not found" -ForegroundColor Red
            exit 1
        }
        $zipChecksum = $checksumLine.Trim().Split(" ")[0]
        
        # Verify checksum
        if (-not (Verify-SHA256Checksum -FilePath $zipFile -ExpectedChecksum $zipChecksum)) {
            exit 1
        }
        
        # Extract distribution to version directory
        Write-Host "Extracting distribution to $zipPath..." -ForegroundColor Cyan
        Expand-Archive -Path $zipFile -DestinationPath $zipPath -Force
        
        Write-Host "Distribution extracted successfully" -ForegroundColor Green
        return $zipPath
    } finally {
        # Clean up temp files
        if (Test-Path $tempDir) {
            Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Create directory junction
function New-DirectoryJunction {
    param([string]$TargetPath)
    
    $serviceName = "Cringle Daemon"
    
    # Stop service before switching junction
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        Write-Host "Stopping service $serviceName before switching junction..." -ForegroundColor Cyan
        Stop-Service -Name $serviceName -Force
    }
    
    if (Test-Path $CRINGLE_CURRENT) {
        # Remove existing junction
        $junction = Get-Item $CRINGLE_CURRENT
        $junction.Delete()
    }
    
    # Create junction
    New-Item -ItemType Junction -Target $TargetPath -Name "current" -Path $CRINGLE_BASE
    Write-Host "Created junction: $CRINGLE_CURRENT -> $TargetPath" -ForegroundColor Green
    
    # Reinstall service after switching junction
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        Write-Host "Reinstalling service after switching junction..." -ForegroundColor Cyan
        & "$SERVICE_DIR\cringle-daemon.exe" install
        Start-Service -Name $serviceName
    }
}

# Add to PATH
function Add-ToPath {
    param([string]$PathToAdd)
    
    $systemPath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($systemPath -notlike "*$PathToAdd*") {
        Write-Host "Adding $PathToAdd to system PATH..." -ForegroundColor Cyan
        $newPath = "$systemPath;$PathToAdd"
        [Environment]::SetEnvironmentVariable("Path", $newPath, "Machine")
        Write-Host "PATH updated successfully" -ForegroundColor Green
    }
}

# Install service
function Install-Service {
    param([string]$Version, [switch]$WithManagement)
    
    $serviceName = "Cringle Daemon"
    $serviceExe = "$SERVICE_DIR\cringle-daemon.exe"
    $binPath = "$CRINGLE_CURRENT\bin"
    
    # Configure service
    if ($WithManagement) {
        $serviceConfig = @"
<service>
  <id>$serviceName</id>
  <name>Cringle Daemon</name>
  <description>Cringle Engine Service with Management</description>
  <executable>$serviceExe</executable>
  <arguments>-management</arguments>
  <environment>
    <variable name="CRINGLE_HOME">$PROGRAM_DATA\Cringle</variable>
  </environment>
  <logmode>rotate</logmode>
  <onfailure>restart</onfailure>
</service>
"@
    } else {
        $serviceConfig = @"
<service>
  <id>$serviceName</id>
  <name>Cringle Daemon</name>
  <description>Cringle Engine Service</description>
  <executable>$serviceExe</executable>
  <environment>
    <variable name="CRINGLE_HOME">$PROGRAM_DATA\Cringle</variable>
  </environment>
  <logmode>rotate</logmode>
  <onfailure>restart</onfailure>
</service>
"@
    }
    
    Set-Content -Path "$SERVICE_DIR\cringle-daemon.xml" -Value $serviceConfig
    
    # Install service
    Write-Host "Installing service $serviceName..." -ForegroundColor Cyan
    & "$SERVICE_DIR\cringle-daemon.exe" install
    
    # Start service
    Write-Host "Starting service..." -ForegroundColor Cyan
    Start-Service -Name $serviceName
    
# Main script execution
if ($Uninstall) {
    Uninstall-Service -Purge:$Purge
    Write-Host "Uninstallation completed" -ForegroundColor Green
} else {
    Install-Cringle -Version $Version
}
