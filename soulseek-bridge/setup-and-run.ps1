#Requires -Version 5.1
<#
.SYNOPSIS
    Bootstraps slskd + DriveStream Google Drive Auto-Uploader Bridge (port 5031).
#>
param(
    [switch]$SkipFirewallPrompt
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$BridgeDir = [System.IO.Path]::GetFullPath($PSScriptRoot)
$BinDir = Join-Path $BridgeDir "bin"
$DownloadsDir = Join-Path $BridgeDir "downloads"
$IncompleteDir = Join-Path $BridgeDir "incomplete"
$SharedDir = Join-Path $BridgeDir "shared"
$EnvPath = Join-Path $BridgeDir ".env"
$ConfigPath = Join-Path $BridgeDir "slskd.yml"
$BridgeScript = Join-Path $BridgeDir "drive_uploader_bridge.py"
$SlskdExe = Join-Path $BinDir "slskd.exe"

$SlskdApiKey = "drivestream-slskd-key-2026"
$DefaultDriveFolderId = "189VXpiNB2PxwnR__gNUwAuwU2wnsT1ME"

New-Item -ItemType Directory -Force -Path $BinDir, $DownloadsDir, $IncompleteDir, $SharedDir | Out-Null

function Parse-DotEnv {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($rawLine in Get-Content -Path $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) { continue }
        $eqIdx = $line.IndexOf("=")
        if ($eqIdx -le 0) { continue }
        $k = $line.Substring(0, $eqIdx).Trim()
        $v = $line.Substring($eqIdx + 1).Trim()
        if (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'"))) {
            if ($v.Length -ge 2) { $v = $v.Substring(1, $v.Length - 2) }
        }
        $map[$k] = $v
    }
    return $map
}

function Save-DotEnv {
    param([string]$Path, [hashtable]$Values)
    $lines = @(
        "# DriveStream Soulseek Bridge Configuration",
        "SOULSEEK_USERNAME=$($Values['SOULSEEK_USERNAME'])",
        "SOULSEEK_PASSWORD=$($Values['SOULSEEK_PASSWORD'])",
        "SOULSEEK_SHARE_DIR=$($Values['SOULSEEK_SHARE_DIR'])",
        "DRIVE_TARGET_FOLDER_ID=$($Values['DRIVE_TARGET_FOLDER_ID'])"
    )
    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Escape-YamlString {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function To-YamlAbsolutePath {
    param([string]$PathValue)
    $full = [System.IO.Path]::GetFullPath($PathValue).TrimEnd('\', '/')
    return $full.Replace('\', '/')
}

function Test-UsableLanIp {
    param([string]$Ip)
    if ([string]::IsNullOrWhiteSpace($Ip)) { return $false }
    if ($Ip -match '^(127\.|169\.254\.|0\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') { return $false }
    return ($Ip -match '^\d{1,3}(\.\d{1,3}){3}$')
}

function Get-PrimaryLanIp {
    $virtualPattern = 'vEthernet|WSL|Docker|Hyper-V|VMware|VirtualBox|Loopback|Radmin|VPN|Tailscale|ZeroTier|WireGuard|OpenVPN|Teredo|Hamachi|Tunnel|Bluetooth'

    # 1. Prefer real hardware adapter with a default IPv4 gateway and private LAN IP
    try {
        $configs = Get-NetIPConfiguration -ErrorAction Stop | Where-Object {
            $_.IPv4DefaultGateway -and
            $_.IPv4DefaultGateway.Count -gt 0 -and
            $_.InterfaceAlias -notmatch $virtualPattern -and
            $_.InterfaceDescription -notmatch $virtualPattern
        }

        $candidates = @()
        foreach ($cfg in $configs) {
            foreach ($addr in $cfg.IPv4Address) {
                $ip = $addr.IPAddress
                if (Test-UsableLanIp -Ip $ip) {
                    $candidates += $ip
                }
            }
        }
        $rfc192 = $candidates | Where-Object { $_ -like '192.168.*' } | Select-Object -First 1
        if ($rfc192) { return $rfc192 }
        $rfc10 = $candidates | Where-Object { $_ -like '10.*' } | Select-Object -First 1
        if ($rfc10) { return $rfc10 }
    } catch {}

    # 2. Fallback via .NET NetworkInterface filtering gateway + virtual interfaces
    try {
        $candidates = @()
        $nics = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | Where-Object {
            $_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up -and
            $_.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback -and
            $_.Name -notmatch $virtualPattern -and
            $_.Description -notmatch $virtualPattern
        }
        foreach ($nic in $nics) {
            $ipProps = $nic.GetIPProperties()
            $hasGw = $ipProps.GatewayAddresses | Where-Object {
                $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
                $_.Address.IPAddressToString -ne '0.0.0.0'
            }
            if (-not $hasGw) { continue }
            foreach ($uni in $ipProps.UnicastAddresses) {
                if ($uni.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
                    $ip = $uni.Address.IPAddressToString
                    if (Test-UsableLanIp -Ip $ip) { $candidates += $ip }
                }
            }
        }
        $rfc192 = $candidates | Where-Object { $_ -like '192.168.*' } | Select-Object -First 1
        if ($rfc192) { return $rfc192 }
        $rfc10 = $candidates | Where-Object { $_ -like '10.*' } | Select-Object -First 1
        if ($rfc10) { return $rfc10 }
    } catch {}

    return "127.0.0.1"
}

function Ensure-BridgeFirewallRule {
    param([switch]$SkipPrompt)
    $ruleName = "DriveStream Soulseek Bridge (Port 5031)"
    $null = & netsh.exe advfirewall firewall show rule name="$ruleName" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[FW] Windows Firewall rule already allows TCP 5031." -ForegroundColor Green
        return
    }

    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator
    )
    if ($isAdmin) {
        & netsh.exe advfirewall firewall add rule name="$ruleName" dir=in action=allow protocol=TCP localport=5031 profile=any | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[FW] Added Windows Firewall inbound rule for TCP port 5031." -ForegroundColor Green
            return
        }
    } elseif (-not $SkipPrompt) {
        Write-Host "[FW] Requesting elevation once to allow TCP 5031 through Windows Firewall..." -ForegroundColor Yellow
        try {
            $proc = Start-Process -FilePath "netsh.exe" `
                -ArgumentList "advfirewall firewall add rule name=`"$ruleName`" dir=in action=allow protocol=TCP localport=5031 profile=any" `
                -Verb RunAs -WindowStyle Hidden -Wait -PassThru
            if ($proc.ExitCode -eq 0) {
                Write-Host "[FW] Added Windows Firewall inbound rule for TCP port 5031." -ForegroundColor Green
                return
            }
        } catch {}
    }

    Write-Warning "Inbound firewall rule for TCP 5031 could not be added without elevation. If Shield/iPhone cannot connect, run in an Admin PowerShell:`n  netsh advfirewall firewall add rule name=`"$ruleName`" dir=in action=allow protocol=TCP localport=5031 profile=any"
}

# 1. Ensure slskd.exe is downloaded
if (-not (Test-Path $SlskdExe)) {
    Write-Host "[1/4] Fetching latest official slskd release metadata..." -ForegroundColor Cyan
    $headers = @{
        "User-Agent" = "DriveStream-SoulseekBridge/1.0"
        "Accept"     = "application/vnd.github+json"
    }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/slskd/slskd/releases/latest" -Headers $headers -TimeoutSec 30
    $asset = $release.assets | Where-Object { $_.name -like "slskd-*-win-x64.zip" } | Select-Object -First 1
    if (-not $asset) {
        throw "Could not find slskd-*-win-x64.zip asset in latest slskd release."
    }

    $zipPath = Join-Path $BinDir $asset.name
    Write-Host "[1/4] Downloading $($asset.name)..." -ForegroundColor Cyan
    & curl.exe -fL --retry 3 --max-time 180 -o $zipPath $asset.browser_download_url
    if ($LASTEXITCODE -ne 0) {
        throw "Download failed for $($asset.browser_download_url)"
    }
    Write-Host "[1/4] Extracting into $BinDir..." -ForegroundColor Cyan
    Expand-Archive -Path $zipPath -DestinationPath $BinDir -Force
    Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $SlskdExe)) {
        $nested = Get-ChildItem -Path $BinDir -Filter "slskd.exe" -Recurse | Select-Object -First 1
        if ($nested) {
            Move-Item -Path $nested.FullName -Destination $SlskdExe -Force
        }
    }
    if (-not (Test-Path $SlskdExe)) {
        throw "slskd.exe missing after extraction into $BinDir"
    }
} else {
    Write-Host "[1/4] Found existing $SlskdExe" -ForegroundColor Green
}

# 2. Load or prompt for Soulseek credentials & shared directory
$envValues = Parse-DotEnv -Path $EnvPath
$dirtyEnv = $false

if ([string]::IsNullOrWhiteSpace($envValues["SOULSEEK_USERNAME"])) {
    $u = (Read-Host "Enter Soulseek Username").Trim()
    if ([string]::IsNullOrWhiteSpace($u)) { throw "Soulseek username is required." }
    $envValues["SOULSEEK_USERNAME"] = $u
    $dirtyEnv = $true
}

if ([string]::IsNullOrWhiteSpace($envValues["SOULSEEK_PASSWORD"])) {
    $sec = Read-Host "Enter Soulseek Password" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try {
        $p = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    if ([string]::IsNullOrWhiteSpace($p)) { throw "Soulseek password is required." }
    $envValues["SOULSEEK_PASSWORD"] = $p
    $dirtyEnv = $true
}

if ([string]::IsNullOrWhiteSpace($envValues["SOULSEEK_SHARE_DIR"])) {
    $promptLabel = "Enter a local folder path to share on Soulseek (prevents leech bans) [Default: $SharedDir]"
    $s = (Read-Host $promptLabel).Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($s)) { $s = $SharedDir }
    if (-not (Test-Path $s)) {
        New-Item -ItemType Directory -Force -Path $s | Out-Null
    }
    $envValues["SOULSEEK_SHARE_DIR"] = [System.IO.Path]::GetFullPath($s)
    $dirtyEnv = $true
} elseif (-not (Test-Path $envValues["SOULSEEK_SHARE_DIR"])) {
    New-Item -ItemType Directory -Force -Path $envValues["SOULSEEK_SHARE_DIR"] | Out-Null
}

if ([string]::IsNullOrWhiteSpace($envValues["DRIVE_TARGET_FOLDER_ID"])) {
    $envValues["DRIVE_TARGET_FOLDER_ID"] = $DefaultDriveFolderId
    $dirtyEnv = $true
}

if ($dirtyEnv -or -not (Test-Path $EnvPath)) {
    Save-DotEnv -Path $EnvPath -Values $envValues
    Write-Host "[2/4] Saved configuration to $EnvPath" -ForegroundColor Green
} else {
    Write-Host "[2/4] Loaded credentials from $EnvPath" -ForegroundColor Green
}

# 3. Generate slskd.yml with strict absolute forward-slash paths
$incompleteYamlPath = To-YamlAbsolutePath $IncompleteDir
$downloadsYamlPath = To-YamlAbsolutePath $DownloadsDir
$shareYamlPath = To-YamlAbsolutePath $envValues["SOULSEEK_SHARE_DIR"]
$yamlContent = @"
directories:
  incomplete: $(Escape-YamlString $incompleteYamlPath)
  downloads: $(Escape-YamlString $downloadsYamlPath)

soulseek:
  username: $(Escape-YamlString $envValues["SOULSEEK_USERNAME"])
  password: $(Escape-YamlString $envValues["SOULSEEK_PASSWORD"])

shares:
  directories:
    - $(Escape-YamlString $shareYamlPath)

web:
  port: 5030
  https:
    disabled: true
  authentication:
    api_keys:
      drivestream:
        key: $(Escape-YamlString $SlskdApiKey)
        role: administrator
        cidr: 0.0.0.0/0,::/0
"@
[System.IO.File]::WriteAllText($ConfigPath, $yamlContent + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Host "[3/4] Generated $ConfigPath (downloads=$downloadsYamlPath, incomplete=$incompleteYamlPath)" -ForegroundColor Green

Ensure-BridgeFirewallRule -SkipPrompt:$SkipFirewallPrompt

# 4. Locate Python & start both processes
$pythonExe = $null
foreach ($cand in @("python", "python3", "py")) {
    $cmd = Get-Command $cand -ErrorAction SilentlyContinue
    if ($cmd) {
        $pythonExe = $cand
        break
    }
}
if (-not $pythonExe) {
    throw "Python 3 is required to run drive_uploader_bridge.py (not found on PATH)."
}

$lanIp = Get-PrimaryLanIp
Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " DriveStream Soulseek + Google Drive Auto-Uploader Ready" -ForegroundColor Green
Write-Host " slskd Web / API   : http://127.0.0.1:5030"
Write-Host " DriveStream Bridge: http://$($lanIp):5031" -ForegroundColor Yellow
Write-Host " Enter '$($lanIp)' in DriveStream Settings or use Auto-Scan!" -ForegroundColor Yellow
Write-Host " Press Ctrl+C to stop both services." -ForegroundColor Gray
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

$env:SLSKD_URL = "http://127.0.0.1:5030"
$env:SLSKD_API_KEY = $SlskdApiKey
$env:DRIVE_TARGET_FOLDER_ID = $envValues["DRIVE_TARGET_FOLDER_ID"]
$env:BRIDGE_PORT = "5031"
$env:BRIDGE_HOST = $lanIp

$slskdProc = $null
try {
    $slskdProc = Start-Process -FilePath $SlskdExe `
        -ArgumentList @("--app-dir", $BridgeDir, "--config", $ConfigPath) `
        -WorkingDirectory $BridgeDir `
        -PassThru `
        -NoNewWindow

    Write-Host "[4/4] Started slskd.exe (PID $($slskdProc.Id)); starting drive_uploader_bridge.py..." -ForegroundColor Green
    if ($pythonExe -eq "py") {
        & py -3 $BridgeScript
    } else {
        & $pythonExe $BridgeScript
    }
} finally {
    if ($slskdProc -and -not $slskdProc.HasExited) {
        Write-Host "Stopping slskd.exe (PID $($slskdProc.Id))..." -ForegroundColor Yellow
        Stop-Process -Id $slskdProc.Id -Force -ErrorAction SilentlyContinue
    }
}
