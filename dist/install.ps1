# SPDX-License-Identifier: Apache-2.0

<#

Cringle Windows Service Installer

This script installs Cringle as a Windows service using WinSW.

Parameters:
  -Uninstall    : Uninstall the service and remove installation files
  -Purge       : Also remove data directory (use with -Uninstall)
  -Version     : Specific version to install (default: latest)

Examples:
  .\install.ps1 -Version 1.0.0
  .\install.ps1 -Uninstall
  .\install.ps1 -Uninstall -Purge
#>

# Check if running as administrator

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
    [switch]$Purge
)

# Constants
$PROGRAM_FILES = "$env:ProgramFiles"
$PROGRAM_DATA = "$env:ProgramData"
$CRINGLE_BASE = "$PROGRAM_FILES\Cringle"
$CRINGLE_CURRENT = "$CRINGLE_BASE\current"
$CRINGLE_SERVICE = "$CRINGLE_CURRENT\service.exe"
$WINSW_URL = "https://github.com/winsw/winsw/releases/download/v2.12.1/WinSW.exe"

# Download and install WinSW if not present
function Install-WinSW {
    if (-not (Test-Path $CRINGLE_CURRENT)) {
        Write-Host "Installing WinSW service wrapper..." -ForegroundColor Cyan
        $winSWPath = "$CRINGLE_CURRENT\service.exe"
        Invoke-WebRequest -Uri $WINSW_URL -OutFile $winSWPath
        Write-Host "WinSW installed at $winSWPath" -ForegroundColor Green
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
    
    $baseUrl = "https://github.com/Tim-Meyran/Cringle/releases/download/v$Version"
    $zipFile = "$CRINGLE_BASE\cringle-$Version-windows.zip"
    $shaFile = "$CRINGLE_BASE\SHA256SUMS"
    $zipPath = "$CRINGLE_BASE\$Version"
    
    Write-Host "Downloading Cringle version $Version..." -ForegroundColor Cyan
    
    # Download distribution
    if (-not (Invoke-WebRequestWithRetry -Uri "$baseUrl/cringle-$Version-windows.zip" -OutFile $zipFile)) {
        Write-Host "ERROR: Failed to download distribution" -ForegroundColor Red
        exit 1
    }
    
    # Download SHA256SUMS
    if (-not (Invoke-WebRequestWithRetry -Uri "$baseUrl/SHA256SUMS" -OutFile $shaFile)) {
        Write-Host "ERROR: Failed to download SHA256SUMS" -ForegroundColor Red
        exit 1
    }
    
    # Extract checksum for the zip file
    $zipChecksum = (Get-Content $shaFile | Where-Object { $_ -like "cringle-$Version-windows.zip *" }).Trim().Split(" ")[0]
    
    # Verify checksum
    if (-not (Verify-SHA256Checksum -FilePath $zipFile -ExpectedChecksum $zipChecksum)) {
        exit 1
    }
    
    # Extract distribution
    Write-Host "Extracting distribution to $zipPath..." -ForegroundColor Cyan
    Expand-Archive -Path $zipFile -DestinationPath $zipPath -Force
    
    # Clean up
    Remove-Item $zipFile -Force
    Remove-Item $shaFile -Force
    
    Write-Host "Distribution extracted successfully" -ForegroundColor Green
    return $zipPath
}

# Create directory junction
function New-DirectoryJunction {
    param([string]$TargetPath)
    
    if (Test-Path $CRINGLE_CURRENT) {
        # Remove existing junction
        $junction = Get-Item $CRINGLE_CURRENT
        $junction.Delete()
    }
    
    # Create junction
    New-Item -ItemType Junction -Target $TargetPath -Name "current" -Path $CRINGLE_BASE
    Write-Host "Created junction: $CRINGLE_CURRENT -> $TargetPath" -ForegroundColor Green
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
    param([string]$Version)
    
    $serviceName = "Cringle Daemon"
    $serviceExe = "$CRINGLE_CURRENT\cringle-daemon.exe"
    $binPath = "$CRINGLE_CURRENT\bin"
    
    # Configure service
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
""
    
    Set-Content -Path "$CRINGLE_CURRENT\cringle-daemon.xml" -Value $serviceConfig
    
    # Install service
    Write-Host "Installing service $serviceName..." -ForegroundColor Cyan
    & "$CRINGLE_CURRENT\cringle-daemon.exe" install
    
    # Start service
    Write-Host "Starting service..." -ForegroundColor Cyan
    Start-Service -Name $serviceName
    
    # Add to PATH
    Add-ToPath -PathToAdd $binPath
    
    Write-Host "Service installed and started successfully" -ForegroundColor Green
}

# Uninstall service
function Uninstall-Service {
    param([switch]$Purge)
    
    $serviceName = "Cringle Daemon"
    
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        Write-Host "Stopping service $serviceName..." -ForegroundColor Cyan
        Stop-Service -Name $serviceName -Force
        
        Write-Host "Uninstalling service..." -ForegroundColor Cyan
        & "$CRINGLE_CURRENT\cringle-daemon.exe" uninstall
        
        Write-Host "Service uninstalled successfully" -ForegroundColor Green
    }
    
    # Remove PATH entry
    $binPath = "$CRINGLE_CURRENT\bin"
    $systemPath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($systemPath -like "*$binPath*") {
        Write-Host "Removing $binPath from system PATH..." -ForegroundColor Cyan
        $newPath = $systemPath -replace "[;]$binPath[;]|",$""; -replace "[;]$binPath$",$""; -replace "^$binPath[;]|",$"";
        [Environment]::SetEnvironmentVariable("Path", $newPath, "Machine")
        Write-Host "PATH updated successfully" -ForegroundColor Green
    }
    
    # Remove Cringle installation
    if (Test-Path $CRINGLE_BASE) {
        Write-Host "Removing $CRINGLE_BASE..." -ForegroundColor Cyan
        Remove-Item $CRINGLE_BASE -Recurse -Force
        Write-Host "Cringle installation removed" -ForegroundColor Green
    }
    
    # Purge data directory
    if ($Purge) {
        $dataPath = "$PROGRAM_DATA\Cringle"
        if (Test-Path $dataPath) {
            Write-Host "Purging data directory $dataPath..." -ForegroundColor Cyan
            Remove-Item $dataPath -Recurse -Force
            Write-Host "Data directory purged" -ForegroundColor Green
        }
    }
}

# Main installation function
function Install-Cringle {
    param([string]$Version)
    
    # Create base directory if it doesn't exist
    if (-not (Test-Path $CRINGLE_BASE)) {
        New-Item -ItemType Directory -Path $CRINGLE_BASE -Force | Out-Null
    }
    
    # Download and extract distribution
    $distPath = Download-Distribution -Version $Version
    
    # Create junction
    New-DirectoryJunction -TargetPath $distPath
    
    # Install WinSW
    Install-WinSW
    
    # Install service
    Install-Service -Version $Version
    
    Write-Host "Cringle version $Version installed successfully!" -ForegroundColor Green
}

# Main script execution
if ($Uninstall) {
    Uninstall-Service -Purge:$Purge
    Write-Host "Uninstallation completed" -ForegroundColor Green
} else {
    Install-Cringle -Version $Version
}

# Constants
$PROGRAM_FILES = "$env:ProgramFiles"
$PROGRAM_DATA = "$env:ProgramData"
$CRINGLE_BASE = "$PROGRAM_FILES\Cringle"
$CRINGLE_CURRENT = "$CRINGLE_BASE\current"
$CRINGLE_SERVICE = "$CRINGLE_CURRENT\service.exe"
$WINSW_URL = "https://github.com/winsw/winsw/releases/download/v2.12.1/WinSW.exe"

# Download and install WinSW if not present
function Install-WinSW {
    if (-not (Test-Path $CRINGLE_CURRENT)) {
        Write-Host "Installing WinSW service wrapper..." -ForegroundColor Cyan
        $winSWPath = "$CRINGLE_CURRENT\service.exe"
        Invoke-WebRequest -Uri $WINSW_URL -OutFile $winSWPath
        Write-Host "WinSW installed at $winSWPath" -ForegroundColor Green
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
    param([string]$Version, [switch]$SkipVerify)
    
    $baseUrl = "https://github.com/Cringle/Cringle/releases/download/v$Version"
    $zipFile = "$CRINGLE_BASE\cringle-$Version.zip"
    $shaFile = "$CRINGLE_BASE\SHA256SUMS"
    $zipPath = "$CRINGLE_BASE\$Version"
    
    Write-Host "Downloading Cringle version $Version..." -ForegroundColor Cyan
    
    # Download distribution
    if (-not (Invoke-WebRequestWithRetry -Uri "$baseUrl/cringle-$Version.zip" -OutFile $zipFile)) {
        Write-Host "ERROR: Failed to download distribution" -ForegroundColor Red
        exit 1
    }
    
    # Download SHA256SUMS
    if (-not (Invoke-WebRequestWithRetry -Uri "$baseUrl/SHA256SUMS" -OutFile $shaFile)) {
        Write-Host "ERROR: Failed to download SHA256SUMS" -ForegroundColor Red
        exit 1
    }
    
    # Extract checksum for the zip file
    $zipChecksum = (Get-Content $shaFile | Where-Object { $_ -like "cringle-$Version.zip *" }).Trim().Split(" ")[0]
    
    # Verify checksum
    if (-not $SkipVerify -and -not (Verify-SHA256Checksum -FilePath $zipFile -ExpectedChecksum $zipChecksum)) {
        exit 1
    }
    
    # Extract distribution
    Write-Host "Extracting distribution to $zipPath..." -ForegroundColor Cyan
    Expand-Archive -Path $zipFile -DestinationPath $zipPath -Force
    
    # Clean up
    Remove-Item $zipFile -Force
    Remove-Item $shaFile -Force
    
    Write-Host "Distribution extracted successfully" -ForegroundColor Green
    return $zipPath
}

# Create directory junction
function New-DirectoryJunction {
    param([string]$TargetPath)
    
    if (Test-Path $CRINGLE_CURRENT) {
        # Remove existing junction
        Remove-Item $CRINGLE_CURRENT -Force
    }
    
    # Create junction
    cmd /c "mklink /d "$CRINGLE_CURRENT" "$TargetPath""
    Write-Host "Created junction: $CRINGLE_CURRENT -> $TargetPath" -ForegroundColor Green
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
    param([string]$Version)
    
    $serviceName = "Cringle Daemon"
    $serviceExe = "$CRINGLE_CURRENT\cringle-daemon.exe"
    $binPath = "$CRINGLE_CURRENT\bin"
    
    # Configure service
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
    
    Set-Content -Path "$CRINGLE_CURRENT\service.xml" -Value $serviceConfig
    
    # Install service
    Write-Host "Installing service $serviceName..." -ForegroundColor Cyan
    & "$CRINGLE_CURRENT\service.exe" install
    
    # Start service
    Write-Host "Starting service..." -ForegroundColor Cyan
    Start-Service -Name $serviceName
    
    # Add to PATH
    Add-ToPath -PathToAdd $binPath
    
    Write-Host "Service installed and started successfully" -ForegroundColor Green
}

# Uninstall service
function Uninstall-Service {
    param([string]$Version, [switch]$Purge)
    
    $serviceName = "Cringle-$Version"
    
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        Write-Host "Stopping service $serviceName..." -ForegroundColor Cyan
        Stop-Service -Name $serviceName -Force
        
        Write-Host "Uninstalling service..." -ForegroundColor Cyan
        & "$CRINGLE_CURRENT\service.exe" uninstall
        
        Write-Host "Service uninstalled successfully" -ForegroundColor Green
    }
    
    # Remove version directory
    $versionPath = "$CRINGLE_BASE\$Version"
    if (Test-Path $versionPath) {
        Write-Host "Removing version directory $versionPath..." -ForegroundColor Cyan
        Remove-Item $versionPath -Recurse -Force
        Write-Host "Version directory removed" -ForegroundColor Green
    }
    
    # Remove junction if it points to this version
    if (Test-Path $CRINGLE_CURRENT) {
        $junctionTarget = (cmd /c "fsutil hardlink query $CRINGLE_CURRENT").Trim()
        if ($junctionTarget -like "*\$Version*") {
            Write-Host "Removing junction $CRINGLE_CURRENT..." -ForegroundColor Cyan
            Remove-Item $CRINGLE_CURRENT -Force
            Write-Host "Junction removed" -ForegroundColor Green
        }
    }
    
    # Purge data directory
    if ($Purge) {
        $dataPath = "$PROGRAM_DATA\Cringle"
        if (Test-Path $dataPath) {
            Write-Host "Purging data directory $dataPath..." -ForegroundColor Cyan
            Remove-Item $dataPath -Recurse -Force
            Write-Host "Data directory purged" -ForegroundColor Green
        }
    }
}

# Main script execution
if ($Uninstall) {
    Uninstall-Service -Purge:$Purge
    Write-Host "Uninstallation completed" -ForegroundColor Green
} else {
    Install-Cringle -Version $Version
}
