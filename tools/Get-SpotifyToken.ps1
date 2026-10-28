<#
.SYNOPSIS
  Logs in to Spotify on this PC (OAuth PKCE) and injects the tokens into a Wear OS emulator.

.DESCRIPTION
  An emulator has no paired phone, so the in-app login can't run. This script does the
  login in your PC browser, then sends the access and refresh tokens to the debug build's
  DebugTokenReceiver over adb and restarts the app.

  One-time setup:
    1. Spotify dashboard -> your app -> Edit settings -> add this Redirect URI:
         http://127.0.0.1:8888/callback
    2. spotify.clientId (and sdk.dir) must be in local.properties at the repo root.
    3. Install the DEBUG build first (./gradlew installDebug); release has no receiver.

.PARAMETER Serial
  adb device serial (adb -s), for when several devices are connected.
.PARAMETER NoAdb
  Only log in; print the adb broadcast command instead of running it.
.PARAMETER ShowTokens
  Print the full tokens (hidden by default).

.EXAMPLE
  powershell -File tools\Get-SpotifyToken.ps1
  powershell -File tools\Get-SpotifyToken.ps1 -Serial emulator-5554

  If the execution policy blocks it:
  powershell -ExecutionPolicy Bypass -File tools\Get-SpotifyToken.ps1
#>
param(
    [string]$Serial,
    [switch]$NoAdb,
    [switch]$ShowTokens
)

$ErrorActionPreference = 'Stop'

$RedirectUri = 'http://127.0.0.1:8888/callback'   # Spotify requires a loopback IP, not "localhost"
$Port = 8888
# Keep in sync with SCOPE in SpotifyAuth.kt
$Scope = 'user-modify-playback-state user-read-playback-state'
$TimeoutSeconds = 180

$repoRoot = Split-Path -Parent $PSScriptRoot

# --- local.properties -------------------------------------------------------
function Get-LocalProperty([string]$name) {
    $file = Join-Path $repoRoot 'local.properties'
    if (-not (Test-Path $file)) { return $null }
    foreach ($line in Get-Content $file) {
        if ($line -match '^\s*#') { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        if ($line.Substring(0, $idx).Trim() -eq $name) {
            $value = $line.Substring($idx + 1).Trim()
            # Java .properties escaping: C\:\\Users\\x -> C:\Users\x
            return ($value -replace '\\(.)', '$1')
        }
    }
    return $null
}

$clientId = Get-LocalProperty 'spotify.clientId'
if ([string]::IsNullOrEmpty($clientId)) {
    throw 'spotify.clientId not found in local.properties.'
}

# --- adb --------------------------------------------------------------------
$adb = 'adb'
$sdkDir = Get-LocalProperty 'sdk.dir'
if ($sdkDir) {
    $candidate = Join-Path $sdkDir 'platform-tools\adb.exe'
    if (Test-Path $candidate) { $adb = $candidate }
}
$adbPrefix = @()
if ($Serial) { $adbPrefix = @('-s', $Serial) }
# Fail before the browser login, not after it: the adb calls later run under 'Continue',
# where a missing adb would surface as a misleading "tokens were not saved".
if (-not $NoAdb -and -not (Get-Command $adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Set sdk.dir in local.properties or put platform-tools on PATH."
}

# --- PKCE -------------------------------------------------------------------
function ConvertTo-Base64Url([byte[]]$bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$buf = New-Object byte[] 64
$rng.GetBytes($buf)
$verifier = ConvertTo-Base64Url $buf            # 64 bytes -> 86 chars
$sha = [System.Security.Cryptography.SHA256]::Create()
$challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
$buf = New-Object byte[] 16
$rng.GetBytes($buf)
$state = ConvertTo-Base64Url $buf

$authorizeUrl = 'https://accounts.spotify.com/authorize?' +
    'client_id=' + [Uri]::EscapeDataString($clientId) +
    '&response_type=code' +
    '&redirect_uri=' + [Uri]::EscapeDataString($RedirectUri) +
    '&scope=' + [Uri]::EscapeDataString($Scope) +
    '&state=' + [Uri]::EscapeDataString($state) +
    '&code_challenge_method=S256' +
    '&code_challenge=' + [Uri]::EscapeDataString($challenge)

# --- Wait for the redirect ----------------------------------------------------
# TcpListener, not HttpListener: HttpListener needs an admin URL ACL even for 127.0.0.1.
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse('127.0.0.1'), $Port)
$listener.Start()
$code = $null
$authError = $null
try {
    Write-Host 'Opening the Spotify login in your browser...'
    Start-Process $authorizeUrl

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($null -eq $code -and $null -eq $authError) {
        if ((Get-Date) -gt $deadline) { throw "Timed out after $TimeoutSeconds s waiting for the Spotify login." }
        if (-not $listener.Pending()) { Start-Sleep -Milliseconds 200; continue }

        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 5000
            $stream = $client.GetStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $requestLine = $reader.ReadLine()   # e.g. GET /callback?code=...&state=... HTTP/1.1
            if ($null -eq $requestLine) { continue }   # connection closed with no data

            $parts = @()
            if ($requestLine) { $parts = $requestLine.Split(' ') }
            if ($parts.Count -lt 2 -or -not ($parts[1] -match '^/callback(\?|$)')) {
                # Not ours (e.g. /favicon.ico): answer 404 and keep listening.
                $bytes = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 404 Not Found`r`nContent-Length: 0`r`nConnection: close`r`n`r`n")
                $stream.Write($bytes, 0, $bytes.Length)
                continue
            }

            $query = @{}
            $qs = ''
            if ($parts[1].Contains('?')) { $qs = $parts[1].Substring($parts[1].IndexOf('?') + 1) }
            foreach ($pair in $qs.Split('&')) {
                if ($pair -eq '') { continue }
                $kv = $pair.Split('=', 2)
                $val = ''
                if ($kv.Count -gt 1) { $val = [Uri]::UnescapeDataString($kv[1].Replace('+', ' ')) }
                $query[$kv[0]] = $val
            }

            $html = '<html><body style="font-family:sans-serif"><h3>You can close this tab.</h3></body></html>'
            $body = [Text.Encoding]::UTF8.GetBytes($html)
            $head = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($head, 0, $head.Length)
            $stream.Write($body, 0, $body.Length)

            if ($query.ContainsKey('error')) {
                $authError = $query['error']
            } elseif ($query['state'] -ne $state) {
                $authError = 'state_mismatch'
            } elseif ($query.ContainsKey('code')) {
                $code = $query['code']
            } else {
                $authError = 'no_code_in_callback'
            }
        } catch [System.IO.IOException] {
            # Speculative browser preconnect (sends nothing, times out) or a dropped socket: ignore, keep listening.
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}

if ($authError -eq 'access_denied') { throw 'Login cancelled: access was denied on the Spotify consent page.' }
if ($authError -eq 'state_mismatch') { throw 'State mismatch: the callback did not come from this login attempt. Aborting.' }
if ($authError) { throw "Spotify returned an error: $authError" }

# --- Exchange the code --------------------------------------------------------
try {
    $tokens = Invoke-RestMethod -Method Post -Uri 'https://accounts.spotify.com/api/token' -Body @{
        grant_type    = 'authorization_code'
        code          = $code
        redirect_uri  = $RedirectUri
        client_id     = $clientId
        code_verifier = $verifier
    }
} catch {
    # PS 5.1 hides Spotify's JSON error body (e.g. "Invalid redirect URI") inside the response stream.
    $detail = $_.Exception.Message
    if ($_.Exception.Response) {
        try {
            $errReader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $errJson = $errReader.ReadToEnd() | ConvertFrom-Json
            $detail = "$($errJson.error): $($errJson.error_description)"
        } catch { }
    }
    throw "Token exchange failed: $detail"
}
$access = $tokens.access_token
$refresh = $tokens.refresh_token

$expiry = (Get-Date).AddSeconds([int]$tokens.expires_in)
Write-Host "Logged in. Access token expires at $($expiry.ToString('HH:mm:ss')) (in $([int]$tokens.expires_in) s)."
if ($ShowTokens) {
    Write-Host "access:  $access"
    Write-Host "refresh: $refresh"
}

# --- Inject -------------------------------------------------------------------
$broadcastArgs = @('shell', 'am', 'broadcast', '-a', 'com.cuepoint.DEBUG_SET_TOKEN', '--include-stopped-packages',
    '-n', 'com.cuepoint/.DebugTokenReceiver', '--es', 'access', $access)
if ($refresh) { $broadcastArgs += @('--es', 'refresh', $refresh) }

if ($NoAdb) {
    $serialPart = ''
    if ($Serial) { $serialPart = " -s $Serial" }
    $shownAccess = '<access>'
    $shownRefresh = '<refresh>'
    if ($ShowTokens) { $shownAccess = $access; $shownRefresh = [string]$refresh }
    $shown = "adb$serialPart shell am broadcast -a com.cuepoint.DEBUG_SET_TOKEN --include-stopped-packages -n com.cuepoint/.DebugTokenReceiver --es access $shownAccess"
    if ($refresh) { $shown += " --es refresh $shownRefresh" }
    Write-Host 'Skipping adb (-NoAdb). Run this yourself (re-run with -ShowTokens to get the real values):'
    Write-Host $shown
    return
}

# adb writes progress to stderr (e.g. "* daemon not running; starting now"). Under 'Stop' with 2>&1,
# PS 5.1 turns that into a terminating error, so relax it for the adb calls and use exit codes instead.
$ErrorActionPreference = 'Continue'

$out = (& $adb @adbPrefix @broadcastArgs 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "adb failed (exit $LASTEXITCODE). Is the emulator running?`n$out"
}
# The receiver sets result -1 (RESULT_OK) on success. "result=0" means nobody handled the broadcast
# (release build or app not installed), or the receiver's write failed.
if ($out -notmatch 'result=-1') {
    throw "Tokens were not saved. Is the DEBUG build installed (./gradlew installDebug)? Did the receiver fail? Check: adb logcat -s DebugTokenReceiver`n$out"
}
Write-Host 'Tokens saved by the app.'

& $adb @adbPrefix shell am force-stop com.cuepoint | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning "force-stop failed (exit $LASTEXITCODE); restart the app manually." }
& $adb @adbPrefix shell am start -n com.cuepoint/.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning "Starting the app failed (exit $LASTEXITCODE); start it manually." }
Write-Host 'App restarted.'
