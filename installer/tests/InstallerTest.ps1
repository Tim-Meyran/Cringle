# SPDX-License-Identifier: Apache-2.0
#
# Tests of installer/install.ps1. They build a fake release (archives, SHA256SUMS) in a temporary directory and run the
# real script against it, always in a new PowerShell process.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File installer\tests\InstallerTest.ps1
#
# The tests of the services, of PATH and of the check for administrative rights need an administrative PowerShell (they
# are reported as SKIPPED otherwise); the service tests download WinSW (pinned version and checksum, as in the release).
# Everything else runs without administrative rights. The script ends with exit code 1 if a check failed.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Installer = Join-Path $PSScriptRoot '..\install.ps1'
$Work = Join-Path ([IO.Path]::GetTempPath()) ("cringle-installer-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Work | Out-Null
$script:Checks = 0
$script:Failed = 0
$script:Skipped = 0

$WinSwUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'
$WinSwSha = 'b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f'

function Check([string]$Description, [bool]$Condition) {
    $script:Checks++
    if (-not $Condition) { $script:Failed++; Write-Host "  FAIL: $Description" }
}

function Skip([string]$Description) {
    $script:Skipped++
    Write-Host "  SKIPPED: $Description"
}

$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

# runs install.ps1 in a new process; returns @{ Code; Output }
function Invoke-Installer([string[]]$Arguments) {
    # the messages of the script go to stderr, which must not stop this script
    $ErrorActionPreference = 'Continue'
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Installer @Arguments 2>&1 | Out-String
    return @{ Code = $LASTEXITCODE; Output = $output }
}

function New-Roots {
    $base = Join-Path $Work ([guid]::NewGuid().ToString('N'))
    return @{ Install = Join-Path $base 'program'; Data = Join-Path $base 'data'; Base = $base }
}

# a release below $Work\releases\v<version>: windows archive, linux archive (not used), SHA256SUMS and winsw.exe
function New-Release([string]$Version, [string]$WinSw) {
    $dir = Join-Path $Work "releases\v$Version"
    $src = Join-Path $Work ("src-" + [guid]::NewGuid().ToString('N'))
    $top = Join-Path $src "cringle-$Version"
    New-Item -ItemType Directory -Force -Path $dir, (Join-Path $top 'bin'), (Join-Path $top 'lib') | Out-Null
    Set-Content -Path (Join-Path $top 'VERSION') -Value $Version -Encoding ASCII
    Set-Content -Path (Join-Path $top 'lib\app.jar') -Value 'jar' -Encoding ASCII
    Set-Content -Path (Join-Path $top 'bin\cringle.bat') -Encoding ASCII -Value @('@echo off', 'set /p V=<"%~dp0..\VERSION"', 'echo cringle %V%')
    # the daemon of the fake release only waits
    Set-Content -Path (Join-Path $top 'bin\cringle-daemon.bat') -Encoding ASCII -Value @('@echo off', 'ping -n 3600 127.0.0.1 >nul')
    Set-Content -Path (Join-Path $top 'bin\cringle-management-server.bat') -Encoding ASCII -Value @('@echo off', 'ping -n 3600 127.0.0.1 >nul')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = Join-Path $dir "cringle-$Version-windows.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory($src, $zip)
    Set-Content -Path (Join-Path $dir "cringle-$Version-linux.tar.gz") -Value 'tar' -Encoding ASCII
    $files = @("cringle-$Version-linux.tar.gz", "cringle-$Version-windows.zip")
    if ($WinSw) { Copy-Item $WinSw (Join-Path $dir 'winsw.exe'); $files += 'winsw.exe' }
    $lines = foreach ($f in $files) { (Get-FileHash -Algorithm SHA256 (Join-Path $dir $f)).Hash.ToLower() + '  ' + $f }
    Set-Content -Path (Join-Path $dir 'SHA256SUMS') -Value $lines -Encoding ASCII
    Remove-Item -Recurse -Force $src
    return $dir
}

function Get-JunctionTarget([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return [string]$item.Target }
    return $null
}

try {
    $Releases = Join-Path $Work 'releases'

    Write-Host '== both scripts parse'
    foreach ($file in @($Installer, $PSCommandPath)) {
        $errors = $null
        [void][Management.Automation.Language.Parser]::ParseFile((Resolve-Path $file).Path, [ref]$null, [ref]$errors)
        Check "$(Split-Path -Leaf $file) has no syntax errors ($($errors | ForEach-Object { $_.Message }))" ($errors.Count -eq 0)
    }

    Write-Host '== PATH functions'
    $ast = [Management.Automation.Language.Parser]::ParseFile((Resolve-Path $Installer).Path, [ref]$null, [ref]$null)
    $functions = $ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -in 'Add-PathEntry', 'Remove-PathEntry' }, $true)
    foreach ($f in $functions) { . ([scriptblock]::Create($f.Extent.Text)) }
    $bin = 'C:\Program Files\Cringle\current\bin'
    Check 'adds an entry' ((Add-PathEntry 'C:\a;C:\b' $bin) -eq "C:\a;C:\b;$bin")
    Check 'adds to an empty PATH' ((Add-PathEntry '' $bin) -eq $bin)
    Check 'does not add twice' ((Add-PathEntry "C:\a;$bin" $bin) -eq "C:\a;$bin")
    Check 'does not add twice (case, trailing backslash)' ((Add-PathEntry "C:\a;$($bin.ToUpper())\" $bin) -eq "C:\a;$($bin.ToUpper())\")
    Check 'keeps %VARIABLES%' ((Add-PathEntry '%SystemRoot%\system32;C:\a' $bin) -eq "%SystemRoot%\system32;C:\a;$bin")
    Check 'removes the entry only' ((Remove-PathEntry "C:\a;$bin;C:\b" $bin) -eq 'C:\a;C:\b')
    Check 'removes the entry (case, trailing backslash)' ((Remove-PathEntry "C:\a;$($bin.ToUpper())\" $bin) -eq 'C:\a')
    Check 'removing a missing entry changes nothing' ((Remove-PathEntry 'C:\a;C:\b' $bin) -eq 'C:\a;C:\b')

    New-Release '1.0.0' $null | Out-Null
    New-Release '2.0.0' $null | Out-Null
    New-Release '3.0.0-rc.1' $null | Out-Null
    $bad = New-Release '9.9.9' $null
    Add-Content -Path (Join-Path $bad 'cringle-9.9.9-windows.zip') -Value 'tampered'
    $nosum = New-Release '8.8.8' $null
    Set-Content -Path (Join-Path $nosum 'SHA256SUMS') -Value ((Get-Content (Join-Path $nosum 'SHA256SUMS')) | Where-Object { $_ -notmatch 'windows' }) -Encoding ASCII

    Write-Host '== install (files only)'
    $r = New-Roots
    $common = @('-NoService', '-BaseUrl', $Releases, '-InstallRoot', $r.Install, '-DataRoot', $r.Data)
    $res = Invoke-Installer (@('-Version', '1.0.0') + $common)
    Check "install exits with 0 ($($res.Output))" ($res.Code -eq 0)
    Check 'version directory' (Test-Path (Join-Path $r.Install '1.0.0\bin\cringle.bat'))
    Check 'current is a junction to the version' ((Get-JunctionTarget (Join-Path $r.Install 'current')) -eq (Join-Path $r.Install '1.0.0'))
    Check 'data directory' (Test-Path $r.Data)
    Check 'cringle --version through current' ((& (Join-Path $r.Install 'current\bin\cringle.bat')) -eq 'cringle 1.0.0')
    Check 'no staging directory left' (-not (Get-ChildItem $r.Install -Force | Where-Object { $_.Name -like '.install-*' }))

    Write-Host '== a wrong checksum installs nothing'
    foreach ($case in @(@('9.9.9', 'tampered archive'), @('8.8.8', 'missing checksum line'), @('7.7.7', 'unknown version'))) {
        $r2 = New-Roots
        $c2 = @('-NoService', '-BaseUrl', $Releases, '-InstallRoot', $r2.Install, '-DataRoot', $r2.Data)
        $res = Invoke-Installer (@('-Version', $case[0]) + $c2)
        Check "$($case[1]) fails with exit code 1" ($res.Code -eq 1)
        Check "$($case[1]) leaves no program files" (-not (Test-Path $r2.Install))
        Check "$($case[1]) leaves no data" (-not (Test-Path $r2.Data))
    }
    $res = Invoke-Installer (@('-Version', '9.9.9') + $common)
    Check 'the message names the checksum' ($res.Output -match 'checksum')
    Check 'a failed install keeps the installed version' ((Get-JunctionTarget (Join-Path $r.Install 'current')) -eq (Join-Path $r.Install '1.0.0'))

    Write-Host '== a second installation with another version, files of the old version locked'
    Set-Content -Path (Join-Path $r.Data 'state') -Value 'keep' -Encoding ASCII
    $lock = [IO.File]::Open((Join-Path $r.Install '1.0.0\lib\app.jar'), 'Open', 'Read', 'None')
    try {
        $res = Invoke-Installer (@('-Version', '2.0.0') + $common)
        Check "upgrade with a locked file exits with 0 ($($res.Output))" ($res.Code -eq 0)
        Check 'current moved' ((Get-JunctionTarget (Join-Path $r.Install 'current')) -eq (Join-Path $r.Install '2.0.0'))
        Check 'cringle --version after the switch' ((& (Join-Path $r.Install 'current\bin\cringle.bat')) -eq 'cringle 2.0.0')
        $res = Invoke-Installer (@('-Version', '3.0.0-rc.1') + $common)
        Check "second upgrade with a locked file exits with 0 ($($res.Output))" ($res.Code -eq 0)
        Check 'current moved again' ((Get-JunctionTarget (Join-Path $r.Install 'current')) -eq (Join-Path $r.Install '3.0.0-rc.1'))
        Check 'the locked version stays' (Test-Path (Join-Path $r.Install '1.0.0\lib\app.jar'))
    } finally {
        $lock.Dispose()
    }
    Check 'data unchanged' ((Get-Content (Join-Path $r.Data 'state')) -eq 'keep')
    $res = Invoke-Installer (@('-Version', '3.0.0-rc.1') + $common)
    Check 'the same version again' ($res.Code -eq 0)

    Write-Host '== uninstall'
    $res = Invoke-Installer (@('-Uninstall', '-NoService', '-InstallRoot', $r.Install, '-DataRoot', $r.Data))
    Check "uninstall exits with 0 ($($res.Output))" ($res.Code -eq 0)
    Check 'program files removed' (-not (Test-Path $r.Install))
    Check 'data kept' ((Get-Content (Join-Path $r.Data 'state')) -eq 'keep')
    $res = Invoke-Installer (@('-Uninstall', '-Purge', '-NoService', '-InstallRoot', $r.Install, '-DataRoot', $r.Data))
    Check 'uninstalling twice with -Purge exits with 0' ($res.Code -eq 0)
    Check 'data removed by -Purge' (-not (Test-Path $r.Data))

    Write-Host '== a local build (-FromBuild)'
    # a folder like build\dist: cringle-<version>-windows.zip and SHA256SUMS, no v<version> level
    $build = Join-Path $Releases 'v1.0.0'
    $r4 = New-Roots
    $f4 = @('-NoService', '-FromBuild', $build, '-InstallRoot', $r4.Install, '-DataRoot', $r4.Data)
    $res = Invoke-Installer $f4
    Check "installing a build exits with 0 and needs no -Version ($($res.Output))" ($res.Code -eq 0)
    Check 'the version of the build is installed' ((Get-JunctionTarget (Join-Path $r4.Install 'current')) -eq (Join-Path $r4.Install '1.0.0'))
    Check 'cringle --version of the build' ((& (Join-Path $r4.Install 'current\bin\cringle.bat')) -eq 'cringle 1.0.0')
    $res = Invoke-Installer $f4
    Check 'installing the same build again exits with 0' ($res.Code -eq 0)
    $multi = Join-Path $Work 'multi-build'
    New-Item -ItemType Directory -Path $multi | Out-Null
    foreach ($v in '1.0.0', '2.0.0') { Copy-Item (Join-Path $Releases "v$v\cringle-$v-windows.zip") $multi }
    Set-Content -Path (Join-Path $multi 'SHA256SUMS') -Encoding ASCII -Value (Get-ChildItem $multi -Filter '*.zip' | ForEach-Object { (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower() + '  ' + $_.Name })
    $r5 = New-Roots
    $f5 = @('-NoService', '-FromBuild', $multi, '-InstallRoot', $r5.Install, '-DataRoot', $r5.Data)
    $res = Invoke-Installer $f5
    Check 'several versions in the build folder without -Version exit with 1' ($res.Code -eq 1 -and $res.Output -match 'several versions')
    Check 'and install nothing' (-not (Test-Path $r5.Install))
    $res = Invoke-Installer (@('-Version', '2.0.0') + $f5)
    Check 'with -Version one of them is installed' ($res.Code -eq 0 -and (& (Join-Path $r5.Install 'current\bin\cringle.bat')) -eq 'cringle 2.0.0')
    $r6 = New-Roots
    $res = Invoke-Installer @('-NoService', '-FromBuild', $bad, '-InstallRoot', $r6.Install, '-DataRoot', $r6.Data)
    Check 'a build with a wrong checksum exits with 1, names the checksum and installs nothing' ($res.Code -eq 1 -and $res.Output -match 'checksum' -and -not (Test-Path $r6.Install))
    $res = Invoke-Installer @('-NoService', '-FromBuild', (Join-Path $Work 'no-such-build'), '-InstallRoot', $r6.Install, '-DataRoot', $r6.Data)
    Check 'a missing build folder exits with 1 and names gradlew cringleDist' ($res.Code -eq 1 -and $res.Output -match 'cringleDist')
    Check '-FromBuild with -BaseUrl exits with 1' ((Invoke-Installer (@('-BaseUrl', $Releases) + $f4)).Code -eq 1)
    Check '-Uninstall with -FromBuild exits with 1' ((Invoke-Installer @('-Uninstall', '-NoService', '-FromBuild', $build, '-InstallRoot', $r4.Install, '-DataRoot', $r4.Data)).Code -eq 1)

    Write-Host '== arguments'
    $r3 = New-Roots
    $c3 = @('-NoService', '-BaseUrl', $Releases, '-InstallRoot', $r3.Install, '-DataRoot', $r3.Data)
    Check '-Purge alone exits with 1' ((Invoke-Installer (@('-Purge') + $c3)).Code -eq 1)
    Check '-Uninstall with -Version exits with 1' ((Invoke-Installer (@('-Uninstall', '-Version', '1.0.0') + $c3)).Code -eq 1)
    Check 'a bad version exits with 1' ((Invoke-Installer (@('-Version', '1.0/..') + $c3)).Code -eq 1)
    Check 'no version with a -BaseUrl exits with 1' ((Invoke-Installer $c3).Code -eq 1)
    Check 'wrong arguments leave nothing' (-not (Test-Path $r3.Install))

    Write-Host '== administrative rights'
    if ($IsAdmin) {
        Skip 'the start without administrative rights (this PowerShell is administrative)'
    } else {
        $r4 = New-Roots
        # -NoElevate: the default would ask for the rights with the Windows dialog and wait for the answer
        $res = Invoke-Installer @('-Version', '1.0.0', '-NoElevate', '-BaseUrl', $Releases, '-InstallRoot', $r4.Install, '-DataRoot', $r4.Data)
        Check 'exit code 1 without administrative rights (-NoElevate)' ($res.Code -eq 1)
        Check 'the message says what is needed' ($res.Output -match 'administrative')
        Check 'nothing was installed' (-not (Test-Path $r4.Install))
    }

    Write-Host '== services and PATH'
    if (-not $IsAdmin) {
        Skip 'service and PATH tests need an administrative PowerShell'
    } else {
        $winsw = Join-Path $Work 'winsw-download.exe'
        Invoke-WebRequest -UseBasicParsing -Uri $WinSwUrl -OutFile $winsw
        Check 'the pinned WinSW has the pinned checksum' ((Get-FileHash -Algorithm SHA256 $winsw).Hash.ToLower() -eq $WinSwSha)
        New-Release '1.0.0' $winsw | Out-Null
        New-Release '2.0.0' $winsw | Out-Null
        $r5 = New-Roots
        $svc = @('-BaseUrl', $Releases, '-InstallRoot', $r5.Install, '-DataRoot', $r5.Data)
        $binDir = Join-Path $r5.Install 'current\bin'
        $machinePath = { [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames') }
        try {
            $res = Invoke-Installer (@('-Version', '1.0.0') + $svc)
            Check "install with services exits with 0 ($($res.Output))" ($res.Code -eq 0)
            $daemon = Get-Service -Name 'cringle-daemon' -ErrorAction SilentlyContinue
            Check 'the service Cringle Daemon exists' ($null -ne $daemon)
            Check 'the service starts automatically' ((Get-CimInstance Win32_Service -Filter "Name='cringle-daemon'").StartMode -eq 'Auto')
            Check 'no service of its own for the management server' ($null -eq (Get-Service -Name 'cringle-management' -ErrorAction SilentlyContinue))
            Check 'the daemon runs management server and repository' ((Get-Content (Join-Path $r5.Install 'service\cringle-daemon.xml') -Raw).Contains('--with-management %CRINGLE_MANAGEMENT_PORT% --web-port %CRINGLE_WEB_PORT% --with-repository %CRINGLE_REPOSITORY_PORT%'))
            Check 'the settings have their defaults' ((Get-Content (Join-Path $r5.Install 'service\cringle-daemon.xml') -Raw).Contains('<env name="CRINGLE_MANAGEMENT_PORT" value="7500"/>') -and (Get-Content (Join-Path $r5.Install 'service\cringle-daemon.xml') -Raw).Contains('<env name="CRINGLE_BIND" value="loopback"/>'))
            Check 'CRINGLE_HOME is in the service configuration' ((Get-Content (Join-Path $r5.Install 'service\cringle-daemon.xml') -Raw).Contains($r5.Data))
            Check 'PATH has the entry' (((& $machinePath) -split ';') -contains $binDir)
            Start-Service 'cringle-daemon'
            Check 'the service starts' ((Get-Service 'cringle-daemon').Status -eq 'Running')
            Stop-Service 'cringle-daemon'
            (Get-Service 'cringle-daemon').WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
            Check 'the service stops' ((Get-Service 'cringle-daemon').Status -eq 'Stopped')
            Start-Service 'cringle-daemon'
            $res = Invoke-Installer (@('-Version', '2.0.0', '-DaemonOnly') + $svc)
            Check "upgrade with a running service exits with 0 ($($res.Output))" ($res.Code -eq 0)
            Check 'current moved' ((Get-JunctionTarget (Join-Path $r5.Install 'current')) -eq (Join-Path $r5.Install '2.0.0'))
            Check 'the service that was running runs again' ((Get-Service 'cringle-daemon').Status -eq 'Running')
            Check 'daemon only: the daemon does not run the management server' (-not (Get-Content (Join-Path $r5.Install 'service\cringle-daemon.xml') -Raw).Contains('--with-management'))
            Check 'PATH has the entry once' ((((& $machinePath) -split ';') | Where-Object { $_ -eq $binDir }).Count -eq 1)
        } finally {
            $res = Invoke-Installer (@('-Uninstall', '-Purge', '-InstallRoot', $r5.Install, '-DataRoot', $r5.Data))
            Check "uninstall with services exits with 0 ($($res.Output))" ($res.Code -eq 0)
        }
        Check 'the services are removed' (($null -eq (Get-Service -Name 'cringle-daemon' -ErrorAction SilentlyContinue)) -and ($null -eq (Get-Service -Name 'cringle-management' -ErrorAction SilentlyContinue)))
        Check 'the PATH entry is removed' (-not (((& $machinePath) -split ';') -contains $binDir))
        Check 'program files and data are removed' (-not (Test-Path $r5.Install) -and -not (Test-Path $r5.Data))
    }
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Work
}

Write-Host "$script:Checks checks, $script:Failed failed, $script:Skipped skipped"
if ($script:Failed -gt 0) { exit 1 }
exit 0
