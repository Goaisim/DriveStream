#Requires -Version 5.1
<#
.SYNOPSIS
    Bootstraps slskd + DriveStream Google Drive Auto-Uploader Bridge (port 5031).
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$BridgeDir = $PSScriptRoot
$BinDir = Join-Path $BridgeDir "bin"
$DownloadsDir = Join-Path $BridgeDir "downloads"
$IncompleteDir = Join-Path $BridgeDir "incomplete"
$EnvPath = Join-Path $BridgeDir ".env"
$ConfigPath = Join-Path $BridgeDir "slskd.yml"
$BridgeScript = Join-Path $BridgeDir "drive_uploader_bridge.py"
$SlskdExe = Join-Path $BinDir "slskd.exe"

$SlskdApiKey = "drivestream-slskd-key-2026"
$DefaultDriveFolderId = "89VXpiNB2PxwnR__gNUwAuwU2wnsT1ME"

New-Item -ItemType Directory -Force -Path $BinDir, $DownloadsDir, $IncompleteDir | Out-Null

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

function Get-PrimaryLanIp {
    try {
        $addrs = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object {
                $_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up -and
                $_.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback
            } |
            ForEach-Object {
                $_.GetIPProperties().UnicastAddresses | Where-Object {
                    $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
                    -not [System.Net.IPAddress]::IsLoopback($_.Address)
                }
            } |
            Select-Object -ExpandProperty Address

        foreach ($ip in $addrs) {
            $s = $ip.IPAddressToString
            if ($s -match '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') {
                return $s
            }
        }
        if ($addrs) { return ($addrs | Select-Object -First 1).IPAddressToString }
    } catch {}
    return "127.0.0.1"
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
    $suggested = Join-Path $env:USERPROFILE "Videos"
    $promptLabel = "Enter a local folder path to share on Soulseek (prevents leech bans) [Default: $suggested]"
    $s = (Read-Host $promptLabel).Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($s)) { $s = $suggested }
    if (-not (Test-Path $s)) {
        New-Item -ItemType Directory -Force -Path $s | Out-Null
    }
    $envValues["SOULSEEK_SHARE_DIR"] = $s
    $dirtyEnv = $true
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

# 3. Generate slskd.yml
$shareYamlPath = $envValues["SOULSEEK_SHARE_DIR"].Replace("\", "/")
$yamlContent = @"
directories:
  incomplete: ./incomplete
  downloads: ./downloads

soulseek:
  username: $(Escape-YamlString $envValues["SOULSEEK_USERNAME"])
  password: $(Escape-YamlString $envValues["SOULSEEK_PASSWORD"])

shares:
  directories:
    - $(Escape-YamlString $shareYamlPath)

web:
  port: 5030
  authentication:
    api_keys:
      drivestream:
        key: $(Escape-YamlString $SlskdApiKey)
        role: administrator
        cidr: 0.0.0.0/0,::/0
"@
Set-Content -Path $ConfigPath -Value $yamlContent -Encoding UTF8
Write-Host "[3/4] Generated $ConfigPath" -ForegroundColor Green

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
