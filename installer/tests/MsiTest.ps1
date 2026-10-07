# SPDX-License-Identifier: Apache-2.0
#
# Test of the Windows installer package (installer/msi, #156). It installs the MSI silently into a temporary folder,
# looks at the result, upgrades and downgrades it, and removes it again. It needs an administrative PowerShell and
# changes the machine PATH while the package is installed (the entry is removed again by the uninstall).
#
#   .\gradlew.bat cringleMsi -PreleaseVersion=0.0.1 --console=plain --no-daemon
#   .\gradlew.bat cringleMsi -PreleaseVersion=0.0.2 --console=plain --no-daemon
#   powershell -NoProfile -ExecutionPolicy Bypass -File installer\tests\MsiTest.ps1 `
#       -Msi build\dist\cringle-0.0.1-x64.msi -HigherMsi build\dist\cringle-0.0.2-x64.msi
#
#   -Msi         the package to test (required)
#   -HigherMsi   a package with a higher version: the upgrade check (SKIPPED without it)
#   -LowerMsi    a package with a lower version: the downgrade check (SKIPPED without it); after the upgrade it is
#                tried on top of the higher version and has to be refused
#
# One line PASS or FAIL per check. The script ends with exit code 1 if a check failed or could not run at all.
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$HigherMsi,
    [string]$LowerMsi
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$script:Failed = 0
$script:Passed = 0
$script:Skipped = 0
function Check([string]$Description, [bool]$Condition) {
    if ($Condition) { $script:Passed++; Write-Host "PASS: $Description" } else { $script:Failed++; Write-Host "FAIL: $Description" }
}
function Skip([string]$Description) { $script:Skipped++; Write-Host "SKIPPED: $Description" }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    [Console]::Error.WriteLine('MsiTest.ps1: this needs administrative rights: start PowerShell with "Run as administrator"')
    exit 1
}

$Work = Join-Path ([IO.Path]::GetTempPath()) ("cringle-msi-test-" + [guid]::NewGuid().ToString('N'))
$Install = Join-Path $Work 'Cringle'
$DataFolder = Join-Path $env:ProgramData 'Cringle'
New-Item -ItemType Directory -Path $Work | Out-Null

function Get-MsiVersion([string]$Path) {
    if ((Split-Path -Leaf $Path) -notmatch '^cringle-(.+)-x64\.msi$') { throw "$Path is not named cringle-<version>-x64.msi" }
    return $Matches[1]
}

# runs msiexec with the verbose log in $Work and returns its exit code; 0 and 3010 (reboot pending) are success
function Invoke-Msi([string]$Mode, [string]$Package, [string[]]$Properties = @()) {
    $log = Join-Path $Work ("msi-" + [guid]::NewGuid().ToString('N') + '.log')
    $arguments = @($Mode, ('"' + (Resolve-Path $Package).Path + '"'), '/qn', '/l*v', ('"' + $log + '"')) + $Properties
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru
    Write-Host "  msiexec $Mode $(Split-Path -Leaf $Package) -> $($process.ExitCode) (log: $log)"
    return $process.ExitCode
}

function Get-MachinePath { return [Environment]::GetEnvironmentVariable('Path', 'Machine') }
function Count-PathEntries([string]$Folder) {
    return @((Get-MachinePath) -split ';' | Where-Object { $_.TrimEnd('\') -ieq $Folder.TrimEnd('\') }).Count
}

# runs bin\cringle.bat in a clean process: JAVA_HOME removed, only System32 on the PATH, so only the jre\ of the installation can be found
function Invoke-Cringle([string[]]$Arguments) {
    $bat = Join-Path $Install 'bin\cringle.bat'
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $env:SystemRoot 'System32\cmd.exe'
    $info.Arguments = '/c ""' + $bat + '" ' + ($Arguments -join ' ') + '"'
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables.Remove('JAVA_HOME')
    $info.EnvironmentVariables['PATH'] = Join-Path $env:SystemRoot 'System32'
    $process = [Diagnostics.Process]::Start($info)
    $out = $process.StandardOutput.ReadToEnd()
    $err = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return @{ Code = $process.ExitCode; Output = $out; Error = $err }
}

$installed = $false
try {
    $version = Get-MsiVersion $Msi
    $dataMarker = Join-Path $DataFolder 'msi-test-data.txt'
    $pathBefore = Count-PathEntries (Join-Path $Install 'bin')

    # ---- install ----
    $code = Invoke-Msi '/i' $Msi @("INSTALLFOLDER=`"$Install`"")
    $installed = ($code -eq 0 -or $code -eq 3010)
    Check "the installation exits with 0 (found $code)" $installed
    if ($installed) {
        foreach ($file in 'bin\cringle.bat', 'bin\cringle-daemon.bat', 'bin\cringle-management-server.bat', 'jre\bin\java.exe', 'lib', 'conf\README.txt', 'installed-by-msi', 'THIRD-PARTY.txt', 'LICENSE', 'NOTICE') {
            Check "installed: $file" (Test-Path -LiteralPath (Join-Path $Install $file))
        }
        $versionFile = (Get-Content -LiteralPath (Join-Path $Install 'VERSION') -Raw).Trim()
        Check "VERSION is $version (found $versionFile)" ($versionFile -eq $version)
        Check 'the data folder exists' (Test-Path -LiteralPath $DataFolder)
        Check 'bin\ is once on the machine PATH' ((Count-PathEntries (Join-Path $Install 'bin')) -eq 1)

        $result = Invoke-Cringle @('--version')
        Check "cringle --version prints 'cringle $version' with the jre of the installation only (exit $($result.Code): $($result.Output.Trim()) $($result.Error.Trim()))" ($result.Code -eq 0 -and $result.Output.Trim() -eq "cringle $version")

        $result = Invoke-Cringle @('self-update', '--check')
        Check "self-update is refused with exit code 2 and the MSI message (exit $($result.Code))" ($result.Code -eq 2 -and ($result.Output + $result.Error).Contains('this Cringle was installed with the MSI: install a newer MSI to update it'))
    }

    # ---- upgrade and downgrade ----
    if ($installed -and $HigherMsi) {
        $higher = Get-MsiVersion $HigherMsi
        $code = Invoke-Msi '/i' $HigherMsi @("INSTALLFOLDER=`"$Install`"")
        Check "the upgrade to $higher exits with 0 (found $code)" ($code -eq 0 -or $code -eq 3010)
        $versionFile = (Get-Content -LiteralPath (Join-Path $Install 'VERSION') -Raw).Trim()
        Check "VERSION is $higher after the upgrade (found $versionFile)" ($versionFile -eq $higher)
        Check 'bin\ is still once on the machine PATH after the upgrade' ((Count-PathEntries (Join-Path $Install 'bin')) -eq 1)
        $result = Invoke-Cringle @('--version')
        Check "cringle --version prints 'cringle $higher' after the upgrade" ($result.Code -eq 0 -and $result.Output.Trim() -eq "cringle $higher")
        $current = $HigherMsi
    } else {
        if ($installed) { Skip 'the upgrade check: no -HigherMsi given' }
        $current = $Msi
    }
    if ($installed -and $LowerMsi) {
        $before = (Get-Content -LiteralPath (Join-Path $Install 'VERSION') -Raw).Trim()
        $code = Invoke-Msi '/i' $LowerMsi @("INSTALLFOLDER=`"$Install`"")
        Check "installing the lower version $(Get-MsiVersion $LowerMsi) is refused (exit code $code)" ($code -ne 0 -and $code -ne 3010)
        $after = (Get-Content -LiteralPath (Join-Path $Install 'VERSION') -Raw).Trim()
        Check "VERSION is still $before after the refused downgrade (found $after)" ($after -eq $before)
    } else {
        if ($installed) { Skip 'the downgrade check: no -LowerMsi given' }
    }

    # ---- uninstall: the programs and the PATH entry go, the data stays ----
    if ($installed) {
        New-Item -ItemType Directory -Force -Path $DataFolder | Out-Null
        Set-Content -LiteralPath $dataMarker -Value 'data' -Encoding ASCII
        $code = Invoke-Msi '/x' $current
        Check "the uninstall exits with 0 (found $code)" ($code -eq 0 -or $code -eq 3010)
        $installed = $false
        Check 'the program files are gone' (-not (Test-Path -LiteralPath (Join-Path $Install 'bin')) -and -not (Test-Path -LiteralPath (Join-Path $Install 'jre')))
        Check 'bin\ is no longer on the machine PATH' ((Count-PathEntries (Join-Path $Install 'bin')) -eq $pathBefore)
        Check 'the data folder and its content are still there' (Test-Path -LiteralPath $dataMarker)
    }
} finally {
    if ($installed) {
        # a check failed halfway: do not leave the package on the machine
        Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', ('"' + (Resolve-Path $Msi).Path + '"'), '/qn') -Wait | Out-Null
    }
    Remove-Item -LiteralPath (Join-Path $DataFolder 'msi-test-data.txt') -Force -ErrorAction SilentlyContinue
    # the logs of msiexec stay when a check failed
    if ($script:Failed -eq 0) { Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host "$script:Passed passed, $script:Failed failed, $script:Skipped skipped"
if ($script:Failed -gt 0) { exit 1 }
exit 0
