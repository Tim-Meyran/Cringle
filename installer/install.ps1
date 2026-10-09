# SPDX-License-Identifier: Apache-2.0
#
# Installer of Cringle for Windows (docs/daemon-service.md). Run it in an administrative PowerShell:
#
#   .\install.ps1 [-Version <version>] [-DaemonOnly] [-Bind <loopback|all|address>] [-Port <n>] [-WebPort <n>] [-Start]
#   .\install.ps1 -FromBuild <dir> [-Version <version>] [-DaemonOnly] [-Start]
#   .\install.ps1 -Uninstall [-Purge]
#
#   -Version <version>  install this version (default: the latest release)
#   -FromBuild <dir>    install a Cringle that was built locally instead of a release: <dir> is the output of
#                       `.\gradlew.bat cringleDist` (build\dist). It holds cringle-<version>-windows.zip and SHA256SUMS; the
#                       version is the one of the archive (give -Version if there are several). winsw.exe is taken from <dir>
#                       if it is there and downloaded otherwise (pinned version and checksum, as in a release).
#                       `.\gradlew.bat cringleInstallLocal` builds and runs this.
#   -DaemonOnly         run only the daemon; by default it also runs the management server (port 7500, web interface 8443,
#                       user logins) and the repository (port 7600) as programs it supervises
#   -Bind <value>       where the servers listen: loopback (default), all (every network interface) or an address
#   -Port <n>           port of the management server (default 7500)
#   -WebPort <n>        port of the web interface (default 8443)
#   -RepositoryPort <n> port of the repository (default 7600)
#   -DaemonPort <n>     port of the daemon (default 7400)
#                       The values are kept as <env> elements in the service file of the daemon and stay when the script is run
#                       again without them; change them later with `cringle setup`.
#   -WithManagement     no longer needed (the default); accepted for old scripts
#   -Start              start the registered services (default: they start at the next boot only)
#   -Uninstall          stop and remove the services, the PATH entry and the program files; the data stays
#   -Purge              with -Uninstall: also remove the data in %ProgramData%\Cringle
#
# For tests and special setups:
#   -BaseUrl      where the releases are downloaded from: <BaseUrl>/v<version>/<file>, an https URL or a directory
#   -InstallRoot  program files (default %ProgramFiles%\Cringle)
#   -DataRoot     data, CRINGLE_HOME of the services (default %ProgramData%\Cringle)
#   -NoService    only unpack the files and switch "current": no service, no PATH entry, no administrative rights needed
#   -NoElevate    do not ask for administrative rights: stop with an error instead (the default asks, see below)
#
# Registering services needs administrative rights. In a normal PowerShell the script asks for them (the Windows
# confirmation dialog), runs itself again in an elevated window with the same arguments, shows its output and ends with
# its exit code. If the dialog is declined the script ends with exit code 1.
param(
    [string]$Version,
    [switch]$WithManagement,
    [switch]$DaemonOnly,
    [string]$Bind,
    [string]$Port,
    [string]$WebPort,
    [string]$RepositoryPort,
    [string]$DaemonPort,
    [switch]$Start,
    [switch]$Uninstall,
    [switch]$Purge,
    [string]$FromBuild,
    [string]$BaseUrl = 'https://github.com/Tim-Meyran/Cringle/releases/download',
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'Cringle'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'Cringle'),
    [switch]$NoService,
    [switch]$NoElevate,
    [string]$ElevatedLog
)

# the arguments as given, to run the script again with the same ones in an elevated PowerShell
$GivenArguments = $PSBoundParameters

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$DefaultBaseUrl = 'https://github.com/Tim-Meyran/Cringle/releases/download'
$LatestApi = 'https://api.github.com/repos/Tim-Meyran/Cringle/releases/latest'
# the service wrapper of a release (docs/releasing.md); a local build downloads it from here when it is not in the build folder
$WinSwUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'
$WinSwSha256 = 'b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f'
# the settings of the services: <env> elements of the service file of the daemon, which the arguments refer to as %KEY% (cringle setup changes them)
$SettingDefaults = [ordered]@{ CRINGLE_BIND = 'loopback'; CRINGLE_DAEMON_PORT = '7400'; CRINGLE_MANAGEMENT_PORT = '7500'; CRINGLE_WEB_PORT = '8443'; CRINGLE_REPOSITORY_PORT = '7600' }
$SettingGiven = @{ CRINGLE_BIND = $Bind; CRINGLE_DAEMON_PORT = $DaemonPort; CRINGLE_MANAGEMENT_PORT = $Port; CRINGLE_WEB_PORT = $WebPort; CRINGLE_REPOSITORY_PORT = $RepositoryPort }
$DaemonArguments = '--port %CRINGLE_DAEMON_PORT% --combined'
if (-not $DaemonOnly) { $DaemonArguments += ' --with-management %CRINGLE_MANAGEMENT_PORT% --web-port %CRINGLE_WEB_PORT% --with-repository %CRINGLE_REPOSITORY_PORT%' }
$Services = @(
    @{ Id = 'cringle-daemon'; Name = 'Cringle Daemon'; Script = 'cringle-daemon.bat'
       Arguments = $DaemonArguments; Description = 'Cringle daemon: starts and supervises the engines of this machine' },
    @{ Id = 'cringle-management'; Name = 'Cringle Management Server'; Script = 'cringle-management-server.bat'
       Arguments = '--port %CRINGLE_MANAGEMENT_PORT%'; Description = 'Cringle management server' }
)

function Write-Info([string]$Message) {
    Write-Host "install.ps1: $Message"
}

function Test-Admin {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# runs this script again with the same arguments in an elevated PowerShell (the Windows confirmation dialog), waits for it,
# shows what it wrote and returns its exit code
function Invoke-Elevated {
    $log = Join-Path ([IO.Path]::GetTempPath()) ("cringle-install-" + [guid]::NewGuid().ToString('N') + '.log')
    $tokens = New-Object System.Collections.Generic.List[string]
    foreach ($name in $GivenArguments.Keys) {
        $value = $GivenArguments[$name]
        if ($value -is [Management.Automation.SwitchParameter]) {
            if ($value.IsPresent) { $tokens.Add("-$name") }
        } else {
            $tokens.Add("-$name")
            $tokens.Add("'" + ([string]$value).Replace("'", "''") + "'")
        }
    }
    $tokens.Add('-NoElevate')
    $tokens.Add("-ElevatedLog '" + $log.Replace("'", "''") + "'")
    $command = "& '" + $PSCommandPath.Replace("'", "''") + "' " + ($tokens -join ' ') + " *> '" + $log.Replace("'", "''") + "'; exit `$LASTEXITCODE"
    $shell = (Get-Process -Id $PID).Path
    Write-Info 'administrative rights are needed: confirm the Windows dialog to continue'
    try {
        $process = Start-Process -FilePath $shell -Verb RunAs -WindowStyle Hidden -Wait -PassThru `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command)
    } catch {
        throw "could not start the elevated PowerShell ($($_.Exception.Message)); start PowerShell with `"Run as administrator`" and run the script again"
    }
    if (Test-Path -LiteralPath $log) {
        Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
        Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    }
    return $process.ExitCode
}

# ---- PATH entries (pure functions on the value of PATH) ----

function Add-PathEntry([string]$PathValue, [string]$Entry) {
    $parts = @($PathValue -split ';' | Where-Object { $_ -ne '' })
    $wanted = $Entry.TrimEnd('\')
    if (-not ($parts | Where-Object { $_.TrimEnd('\') -ieq $wanted })) {
        $parts += $Entry
    }
    return ($parts -join ';')
}

function Remove-PathEntry([string]$PathValue, [string]$Entry) {
    $wanted = $Entry.TrimEnd('\')
    $parts = @($PathValue -split ';' | Where-Object { $_ -ne '' -and $_.TrimEnd('\') -ine $wanted })
    return ($parts -join ';')
}

function Update-MachinePath([scriptblock]$Change) {
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $true)
    try {
        # the value as it is, with %VARIABLES% not expanded, so that they stay variables
        $old = [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $new = & $Change $old
        if ($new -ne $old) {
            $key.SetValue('Path', $new, [Microsoft.Win32.RegistryValueKind]::ExpandString)
        }
    } finally {
        $key.Close()
    }
}

# ---- release files ----

function Get-ReleaseFile([string]$Release, [string]$Name, [string]$Destination) {
    if ($FromBuild) {
        $source = Join-Path $FromBuild $Name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "file not found: $source (build it with: .\gradlew.bat cringleDist)"
        }
        Copy-Item -LiteralPath $source -Destination $Destination
        return
    }
    if ($BaseUrl -match '^https?://') {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $url = "$($BaseUrl.TrimEnd('/'))/v$Release/$Name"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Destination
        } catch {
            throw "download failed: $url ($($_.Exception.Message))"
        }
    } else {
        $dir = if ($BaseUrl -match '^file:') { ([Uri]$BaseUrl).LocalPath } else { $BaseUrl }
        $source = Join-Path (Join-Path $dir "v$Release") $Name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "file not found: $source"
        }
        Copy-Item -LiteralPath $source -Destination $Destination
    }
}

# the version of the archive in the build folder $FromBuild
function Get-BuildVersion {
    if (-not (Test-Path -LiteralPath $FromBuild -PathType Container)) {
        throw "-FromBuild: $FromBuild is not a folder (build it with: .\gradlew.bat cringleDist)"
    }
    $versions = @(Get-ChildItem -LiteralPath $FromBuild -Filter 'cringle-*-windows.zip' -File |
        Where-Object { $_.Name -match '^cringle-(.+)-windows\.zip$' } | ForEach-Object { $Matches[1] })
    if ($versions.Count -eq 0) { throw "no cringle-<version>-windows.zip in $FromBuild (build it with: .\gradlew.bat cringleDist)" }
    if ($versions.Count -gt 1) { throw "several versions in ${FromBuild}: $($versions -join ', '); give one with -Version <version>" }
    return $versions[0]
}

# winsw.exe for a local build: the one in the build folder or the pinned download, always checked against the pinned checksum
function Get-BuildWinSw([string]$Destination) {
    $local = Join-Path $FromBuild 'winsw.exe'
    if (Test-Path -LiteralPath $local -PathType Leaf) {
        Copy-Item -LiteralPath $local -Destination $Destination
    } else {
        Write-Info "downloading WinSW from $WinSwUrl"
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $WinSwUrl -OutFile $Destination
        } catch {
            throw "download failed: $WinSwUrl ($($_.Exception.Message))"
        }
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant()
    if ($actual -ne $WinSwSha256) {
        Remove-Item -LiteralPath $Destination -Force
        throw "the checksum of winsw.exe does not match (expected $WinSwSha256, got $actual); nothing was installed"
    }
}

function Get-LatestVersion {
    if ($BaseUrl -ne $DefaultBaseUrl) {
        throw 'give the version with -Version <version> when -BaseUrl is set'
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    try {
        $release = Invoke-RestMethod -UseBasicParsing -Uri $LatestApi
    } catch {
        throw "cannot find the latest release ($($_.Exception.Message)); give a version with -Version <version>"
    }
    return ([string]$release.tag_name).TrimStart('v')
}

# checks the file $Name in $Directory against its line in the file SHA256SUMS of that directory
function Assert-Checksum([string]$Directory, [string]$Name) {
    $expected = $null
    foreach ($line in Get-Content -LiteralPath (Join-Path $Directory 'SHA256SUMS')) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$' -and $Matches[2] -eq $Name) {
            $expected = $Matches[1].ToLowerInvariant()
        }
    }
    if (-not $expected) {
        throw "no checksum for $Name in SHA256SUMS"
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Directory $Name)).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "the checksum of $Name does not match SHA256SUMS (expected $expected, got $actual); nothing was installed"
    }
}

# ---- services (WinSW) ----

function Get-CringleService([string]$Id) {
    return Get-Service -Name $Id -ErrorAction SilentlyContinue
}

function Stop-CringleService([string]$Id) {
    $service = Get-CringleService $Id
    if ($service -and $service.Status -ne 'Stopped') {
        Write-Info "stopping $Id"
        Stop-Service -Name $Id -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
    }
}

function Invoke-WinSW([string]$Exe, [string]$Command) {
    & $Exe $Command | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe $Command failed with exit code $LASTEXITCODE"
    }
}

# the value of every setting: the one given as an option, else the one in the service file that is there, else the default
function Get-Settings([string]$Path) {
    $result = [ordered]@{}
    $existing = if (Test-Path -LiteralPath $Path) { [IO.File]::ReadAllText($Path) } else { '' }
    foreach ($key in $SettingDefaults.Keys) {
        $value = $SettingGiven[$key]
        if (-not $value -and $existing -match ('<env name="' + $key + '" value="([^"]*)"')) { $value = $Matches[1] }
        if (-not $value) { $value = $SettingDefaults[$key] }
        $result[$key] = $value
    }
    return $result
}

function Assert-Settings {
    foreach ($key in $SettingGiven.Keys) {
        $value = $SettingGiven[$key]
        if (-not $value) { continue }
        if ($key -eq 'CRINGLE_BIND') {
            if ($value -notmatch '^[A-Za-z0-9.:_-]+$') { throw "-Bind needs loopback, all or an address, not '$value'" }
        } elseif ($value -notmatch '^[0-9]{1,5}$' -or [int]$value -lt 1 -or [int]$value -gt 65535) {
            throw "a port needs a number from 1 to 65535, not '$value' ($key)"
        }
    }
}

function Write-ServiceConfig($Service, [string]$Path) {
    $settings = Get-Settings $Path
    $envXml = ($settings.Keys | ForEach-Object { '  <env name="' + $_ + '" value="' + [Security.SecurityElement]::Escape($settings[$_]) + '"/>' }) -join "`n"
    $bat = Join-Path (Join-Path (Join-Path $InstallRoot 'current') 'bin') $Service.Script
    $cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'
    $esc = { param($Text) [Security.SecurityElement]::Escape($Text) }
    $xml = @"
<service>
  <id>$(& $esc $Service.Id)</id>
  <name>$(& $esc $Service.Name)</name>
  <description>$(& $esc $Service.Description)</description>
  <executable>$(& $esc $cmd)</executable>
  <arguments>$(& $esc ('/c ""' + $bat + '" ' + $Service.Arguments + '"'))</arguments>
  <env name="CRINGLE_HOME" value="$(& $esc $DataRoot)"/>
$envXml
  <logpath>$(& $esc (Join-Path $DataRoot 'logs'))</logpath>
  <log mode="roll"/>
  <onfailure action="restart" delay="5 sec"/>
  <stoptimeout>60 sec</stoptimeout>
  <startmode>Automatic</startmode>
</service>
"@
    [IO.File]::WriteAllText($Path, $xml, (New-Object Text.UTF8Encoding($false)))
}

# registers the service if it does not exist and (re)writes its configuration
function Install-CringleService($Service, [string]$WinSW) {
    $serviceDir = Join-Path $InstallRoot 'service'
    New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null
    $exe = Join-Path $serviceDir "$($Service.Id).exe"
    $registered = Get-CringleService $Service.Id
    if (-not $registered -or -not (Test-Path -LiteralPath $exe)) {
        Copy-Item -LiteralPath $WinSW -Destination $exe -Force
    }
    Write-ServiceConfig $Service (Join-Path $serviceDir "$($Service.Id).xml")
    if (-not $registered) {
        Write-Info "registering the service $($Service.Name)"
        Invoke-WinSW $exe 'install'
    }
}

# stops and unregisters a service
function Remove-CringleService($Service) {
    Stop-CringleService $Service.Id
    $exe = Join-Path (Join-Path $InstallRoot 'service') "$($Service.Id).exe"
    if (Get-CringleService $Service.Id) {
        if (Test-Path -LiteralPath $exe) { Invoke-WinSW $exe 'uninstall' } else { & sc.exe delete $Service.Id | Out-Null }
    }
}

# ---- install and uninstall ----

# removes a junction or directory link without touching what it points to
function Remove-Junction([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if (-not $item) { return }
    if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "$Path is not a junction; remove or rename it and start again"
    }
    & cmd.exe /c rmdir "`"$Path`"" | Out-Null
    if (Test-Path -LiteralPath $Path) {
        throw "cannot remove the junction $Path"
    }
}

function Remove-Tree([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    for ($try = 1; ; $try++) {
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force
            return
        } catch {
            if ($try -ge 5) {
                throw "cannot remove $Path, a file in it is probably in use ($($_.Exception.Message))"
            }
            Start-Sleep -Seconds 2
        }
    }
}

function Invoke-Install {
    if ($Version) { $release = $Version.TrimStart('v') }
    elseif ($FromBuild) { $release = Get-BuildVersion; Write-Info "the build in $FromBuild is $release" }
    else { $release = Get-LatestVersion; Write-Info "the latest release is $release" }
    if ($release -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$') {
        throw "'$release' is not a version like 1.2.3 or 1.2.3-rc.1"
    }
    $archive = "cringle-$release-windows.zip"
    $downloads = Join-Path ([IO.Path]::GetTempPath()) ("cringle-install-" + [guid]::NewGuid().ToString('N'))
    $stage = $null
    try {
        New-Item -ItemType Directory -Path $downloads | Out-Null
        if ($FromBuild) { Write-Info "installing Cringle $release from the build in $FromBuild" } else { Write-Info "downloading Cringle $release" }
        Get-ReleaseFile $release $archive (Join-Path $downloads $archive)
        Get-ReleaseFile $release 'SHA256SUMS' (Join-Path $downloads 'SHA256SUMS')
        Assert-Checksum $downloads $archive
        if (-not $NoService) {
            if ($FromBuild) {
                Get-BuildWinSw (Join-Path $downloads 'winsw.exe')
            } else {
                Get-ReleaseFile $release 'winsw.exe' (Join-Path $downloads 'winsw.exe')
                Assert-Checksum $downloads 'winsw.exe'
            }
        }

        New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
        $stage = Join-Path $InstallRoot (".install-" + [guid]::NewGuid().ToString('N'))
        Expand-Archive -LiteralPath (Join-Path $downloads $archive) -DestinationPath $stage
        $unpacked = Join-Path $stage "cringle-$release"
        if (-not (Test-Path -LiteralPath (Join-Path $unpacked 'bin\cringle.bat'))) {
            throw "$archive has no cringle-$release\bin\cringle.bat"
        }

        # files of a running version cannot be replaced: stop the services first and start them again afterwards
        $wasRunning = @()
        foreach ($service in $Services) {
            $existing = Get-CringleService $service.Id
            if ($existing -and $existing.Status -ne 'Stopped') { $wasRunning += $service.Id }
            Stop-CringleService $service.Id
        }

        $target = Join-Path $InstallRoot $release
        Remove-Tree $target
        Move-Item -LiteralPath $unpacked -Destination $target
        $current = Join-Path $InstallRoot 'current'
        $previous = $null
        if (Test-Path -LiteralPath $current) { $previous = [string](Get-Item -LiteralPath $current -Force).Target }
        Remove-Junction $current
        New-Item -ItemType Junction -Path $current -Target $target | Out-Null

        New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null
        if (-not $NoService) {
            Install-CringleService $Services[0] (Join-Path $downloads 'winsw.exe')
            # an older installation ran the management server as a service of its own; the daemon runs it now
            if (Get-CringleService $Services[1].Id) {
                Write-Info "removing the service $($Services[1].Name): the daemon runs the management server"
                Remove-CringleService $Services[1]
            }
            $binDir = Join-Path $current 'bin'
            Update-MachinePath { param($old) Add-PathEntry $old $binDir }
            $toStart = @($wasRunning)
            if ($Start) { $toStart = @($Services | Where-Object { Get-CringleService $_.Id } | ForEach-Object { $_.Id }) }
            foreach ($id in ($toStart | Select-Object -Unique)) {
                Write-Info "starting $id"
                Start-Service -Name $id
            }
        }

        # old versions are removed when no file of them is in use; a version that cannot be removed stays
        Get-ChildItem -LiteralPath $InstallRoot -Directory |
            Where-Object { $_.Name -match '^[0-9]+\.[0-9]+\.[0-9]+' -and $_.FullName -ne $target -and $_.FullName -ne $previous } |
            ForEach-Object { try { Remove-Item -LiteralPath $_.FullName -Recurse -Force } catch { } }

        Write-Info "Cringle $release is installed in $InstallRoot\current"
        if (-not $NoService -and -not $Start) {
            Write-Info 'the services start at the next boot; start them now with: Start-Service cringle-daemon'
        }
    } finally {
        if ($stage -and (Test-Path -LiteralPath $stage)) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $downloads) { Remove-Item -LiteralPath $downloads -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

function Invoke-Uninstall {
    Write-Info 'removing the Cringle services and program files'
    foreach ($service in $Services) { Remove-CringleService $service }
    if (-not $NoService) {
        $binDir = Join-Path (Join-Path $InstallRoot 'current') 'bin'
        Update-MachinePath { param($old) Remove-PathEntry $old $binDir }
    }
    Remove-Junction (Join-Path $InstallRoot 'current')
    Remove-Tree $InstallRoot
    if ($Purge) {
        Write-Info "removing the data in $DataRoot"
        Remove-Tree $DataRoot
    } else {
        Write-Info "kept the data in $DataRoot (-Purge removes it)"
    }
}

# ---- main ----

try {
    if ($Purge -and -not $Uninstall) { throw '-Purge works only together with -Uninstall' }
    Assert-Settings
    if ($Uninstall -and ($Bind -or $Port -or $WebPort -or $RepositoryPort -or $DaemonPort)) { throw '-Uninstall cannot be combined with -Bind or the port options' }
    if ($Uninstall -and ($Version -or $WithManagement -or $DaemonOnly -or $Start -or $FromBuild)) { throw '-Uninstall cannot be combined with -Version, -DaemonOnly, -Start or -FromBuild' }
    if ($FromBuild -and $PSBoundParameters.ContainsKey('BaseUrl')) { throw '-FromBuild cannot be combined with -BaseUrl' }
    if (-not $NoService -and -not (Test-Admin)) {
        if ($NoElevate) {
            throw 'this needs administrative rights: start PowerShell with "Run as administrator" and run the script again'
        }
        exit (Invoke-Elevated)
    }
    if ($Uninstall) { Invoke-Uninstall } else { Invoke-Install }
    exit 0
} catch {
    [Console]::Error.WriteLine("install.ps1: $($_.Exception.Message)")
    # the elevated run writes to a log that the first run shows; standard error would be lost there
    if ($ElevatedLog) { Write-Host "install.ps1: $($_.Exception.Message)" }
    exit 1
}
