# SPDX-License-Identifier: Apache-2.0
#
# Installer of Cringle for Windows (docs/daemon-service.md). Run it in an administrative PowerShell:
#
#   .\install.ps1 [-Version <version>] [-WithManagement] [-Start]
#   .\install.ps1 -Uninstall [-Purge]
#
#   -Version <version>  install this version (default: the latest release)
#   -WithManagement     also register the service "Cringle Management Server"
#   -Start              start the registered services (default: they start at the next boot only)
#   -Uninstall          stop and remove the services, the PATH entry and the program files; the data stays
#   -Purge              with -Uninstall: also remove the data in %ProgramData%\Cringle
#
# For tests and special setups:
#   -BaseUrl      where the releases are downloaded from: <BaseUrl>/v<version>/<file>, an https URL or a directory
#   -InstallRoot  program files (default %ProgramFiles%\Cringle)
#   -DataRoot     data, CRINGLE_HOME of the services (default %ProgramData%\Cringle)
#   -NoService    only unpack the files and switch "current": no service, no PATH entry, no administrative rights needed
param(
    [string]$Version,
    [switch]$WithManagement,
    [switch]$Start,
    [switch]$Uninstall,
    [switch]$Purge,
    [string]$BaseUrl = 'https://github.com/Tim-Meyran/Cringle/releases/download',
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'Cringle'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'Cringle'),
    [switch]$NoService
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$DefaultBaseUrl = 'https://github.com/Tim-Meyran/Cringle/releases/download'
$LatestApi = 'https://api.github.com/repos/Tim-Meyran/Cringle/releases/latest'
$DaemonPort = 7400
$ManagementPort = 7500
$Services = @(
    @{ Id = 'cringle-daemon'; Name = 'Cringle Daemon'; Script = 'cringle-daemon.bat'
       Arguments = "--port $DaemonPort --combined"; Description = 'Cringle daemon: starts and supervises the engines of this machine' },
    @{ Id = 'cringle-management'; Name = 'Cringle Management Server'; Script = 'cringle-management-server.bat'
       Arguments = "--port $ManagementPort"; Description = 'Cringle management server' }
)

function Write-Info([string]$Message) {
    Write-Host "install.ps1: $Message"
}

function Test-Admin {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
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

function Write-ServiceConfig($Service, [string]$Path) {
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
    if ($Version) { $release = $Version.TrimStart('v') } else { $release = Get-LatestVersion; Write-Info "the latest release is $release" }
    if ($release -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$') {
        throw "'$release' is not a version like 1.2.3 or 1.2.3-rc.1"
    }
    $archive = "cringle-$release-windows.zip"
    $downloads = Join-Path ([IO.Path]::GetTempPath()) ("cringle-install-" + [guid]::NewGuid().ToString('N'))
    $stage = $null
    try {
        New-Item -ItemType Directory -Path $downloads | Out-Null
        Write-Info "downloading Cringle $release"
        Get-ReleaseFile $release $archive (Join-Path $downloads $archive)
        Get-ReleaseFile $release 'SHA256SUMS' (Join-Path $downloads 'SHA256SUMS')
        Assert-Checksum $downloads $archive
        if (-not $NoService) {
            Get-ReleaseFile $release 'winsw.exe' (Join-Path $downloads 'winsw.exe')
            Assert-Checksum $downloads 'winsw.exe'
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
            $managementInstalled = [bool](Get-CringleService $Services[1].Id)
            if ($WithManagement -or $managementInstalled) {
                Install-CringleService $Services[1] (Join-Path $downloads 'winsw.exe')
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
    foreach ($service in $Services) {
        Stop-CringleService $service.Id
        $exe = Join-Path (Join-Path $InstallRoot 'service') "$($service.Id).exe"
        if (Get-CringleService $service.Id) {
            if (Test-Path -LiteralPath $exe) { Invoke-WinSW $exe 'uninstall' } else { & sc.exe delete $service.Id | Out-Null }
        }
    }
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
    if ($Uninstall -and ($Version -or $WithManagement -or $Start)) { throw '-Uninstall cannot be combined with -Version, -WithManagement or -Start' }
    if (-not $NoService -and -not (Test-Admin)) {
        throw 'this needs administrative rights: start PowerShell with "Run as administrator" and run the script again'
    }
    if ($Uninstall) { Invoke-Uninstall } else { Invoke-Install }
    exit 0
} catch {
    [Console]::Error.WriteLine("install.ps1: $($_.Exception.Message)")
    exit 1
}
