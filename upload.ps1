param([switch]$DryRun)
# Publishes this folder to GitHub without the GitHub CLI. Started by upload.bat.
#   1. Checks that only this project's own files go up (no game data, no APK/pak in git, no keys, nothing personal).
#   2. Uses the GitHub sign-in that Git for Windows already keeps (Git Credential Manager; a sign-in window opens the
#      first time), creates the public repository if it isn't there yet, and pushes the code.
#   3. Puts release\Gauntlet-Odin3.apk on a GitHub Release (replaced if that release already has one).
# -DryRun: only the checks, nothing is uploaded.
# Optional, never uploaded: upload_private_words.txt next to this file, one word or path per line; the upload stops if
# any file that goes up contains one of them (for example your name or your own folder names).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$repoName = 'Gauntlet-Odin3-Port'
$apk = Join-Path $here 'release\Gauntlet-Odin3.apk'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
function Stop-Here($why) { Write-Host ""; Write-Host "STOPPED: $why" -ForegroundColor Red; Write-Host "Nothing more was uploaded."; exit 1 }
function Git-Run { & git @args; if ($LASTEXITCODE -ne 0) { Stop-Here "git $($args -join ' ') failed (see above)" } }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Stop-Here "Git is not installed - get it from https://git-scm.com/download/win" }
if (-not (Test-Path $apk)) { Stop-Here "release\Gauntlet-Odin3.apk is missing" }

Write-Host "[1/5] Getting the files ready..."
if (-not (Test-Path '.git')) {
    & git init -q -b main; if ($LASTEXITCODE -ne 0) { Stop-Here "could not start a git repository here" }
}
Git-Run add -A
$files = @(& git ls-files --cached)
Write-Host "      $($files.Count) files"

Write-Host "[2/5] Checking that nothing from the game or your PC is in it..."
$allowed = '^(README\.md|BUILDING\.md|THIRD_PARTY\.md|LICENSE|\.gitignore|\.gitattributes|upload\.bat|upload\.ps1|tools/[^/]+\.(py|txt)|android/[^/]+\.(py|sh)|android/src/.+\.java|android/assets_public/[^/]+\.(json|png|jpg|desktop))$'
$other = $files | Where-Object { $_ -notmatch $allowed }
if ($other) { $other | Select-Object -First 20 | ForEach-Object { "   $_" }; Stop-Here "files other than the project's own code, docs and artwork are in the folder (listed above)" }
foreach ($f in $files) {
    if ((Get-Item -LiteralPath $f).Length -gt 2MB) { Stop-Here "$f is unexpectedly large" }
}
$words = @()
$wordFile = Join-Path $here 'upload_private_words.txt'
if (Test-Path $wordFile) { $words = @(Get-Content $wordFile | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
foreach ($f in ($files | Where-Object { $_ -notmatch '\.(png|jpg)$' })) {
    $t = [IO.File]::ReadAllText((Join-Path $here $f))
    if ($t -match '(?m)^\s*[A-Za-z0-9_]*(password|passwd|storepass|keypass|token|secret)\s*[:=]\s*["'']?[A-Za-z0-9+/_\-]{8,}') { Stop-Here "$f looks like it has a password or token in it" }
    foreach ($w in $words) { if ($t -match ('(?i)(?<![A-Za-z0-9])' + [regex]::Escape($w) + '(?![A-Za-z0-9])')) { Stop-Here "$f mentions '$w' (from upload_private_words.txt)" } }
}
# the APK must be the public build: no button sets or pictures taken from the game inside it
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $bad = $z.Entries | Where-Object { $_.FullName -match '^assets/gauntlet/(odin_buttons|xbox_buttons|ps_textures)|\.(pak|bundle|stream)$|binaries_x86|gauntlet\.exe' }
    if ($bad) { $bad | Select-Object -First 10 | ForEach-Object { "   $($_.FullName)" }; Stop-Here "the APK has game files in it - build the public APK (GAUNTLET_PUBLIC=1)" }
} finally { $z.Dispose() }
Write-Host "      clean"
if ($DryRun) { Write-Host ""; Write-Host "Check done (DryRun) - nothing was uploaded."; exit 0 }

Write-Host "[3/5] Your GitHub sign-in (a sign-in window may open the first time)..."
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$bash = 'C:\Program Files\Git\bin\bash.exe'
if (Test-Path $bash) { $cred = & $bash -c "printf 'protocol=https\nhost=github.com\n\n' | git credential fill" }
else { $cred = "protocol=https`nhost=github.com`n`n" | & git credential fill }
$token = ($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
if (-not $token) { Stop-Here "no GitHub sign-in - Git for Windows (with its Credential Manager) asks for one here" }
$h = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'gauntlet-odin3-upload' }
try { $me = Invoke-RestMethod -Headers $h -Uri 'https://api.github.com/user' } catch { Stop-Here "GitHub didn't accept the sign-in ($($_.Exception.Message))" }
$login = $me.login
# keep the sign-in so git push below doesn't ask again
if (Test-Path $bash) { $env:GCRED = ($cred -join "`n"); & $bash -c 'printf "%s\n\n" "$GCRED" | git credential approve'; Remove-Item Env:GCRED }
Write-Host "      signed in as $login"

$repo = "$login/$repoName"
$info = $null
try { $info = Invoke-RestMethod -Headers $h -Uri "https://api.github.com/repos/$repo" } catch { $info = $null }
if (-not $info) {
    Write-Host "      creating the public repository $repo"
    $req = @{ name = $repoName; description = 'Unofficial AYN Odin 3 port of Gauntlet Slayer Edition (GOG) - one-icon Android app, bring your own game'; private = $false; has_wiki = $false } | ConvertTo-Json
    try { $info = Invoke-RestMethod -Method Post -Headers $h -Uri 'https://api.github.com/user/repos' -Body ([Text.Encoding]::UTF8.GetBytes($req)) -ContentType 'application/json' }
    catch { Stop-Here "could not create the repository ($($_.Exception.Message))" }
}

Write-Host "[4/5] Uploading the code..."
$url = "https://github.com/$repo.git"
$old = & git config --get remote.origin.url
if (-not $old) { Git-Run remote add origin $url } else { Git-Run remote set-url origin $url }
& git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    $msg = if (& git rev-parse -q --verify HEAD) { "Update $(Get-Date -Format yyyy-MM-dd)" } else { 'Gauntlet Slayer Edition port for the AYN Odin 3' }
    Git-Run -c "user.name=$login" -c "user.email=$login@users.noreply.github.com" commit -q -m $msg
} else { Write-Host "      nothing new since the last upload" }
Git-Run push -u origin HEAD:main

Write-Host "[5/5] Uploading the APK (765 MB, this takes a while)..."
$tag = 'v' + (Get-Item $apk).LastWriteTime.ToString('yyyy.MM.dd')
$asset = 'Gauntlet-Odin3.apk'
$rel = $null
try { $rel = Invoke-RestMethod -Headers $h -Uri "https://api.github.com/repos/$repo/releases/tags/$tag" } catch { $rel = $null }
if (-not $rel) {
    $body = @"
**Gauntlet Slayer Edition for the AYN Odin 3** - one icon, straight into the game. No game files are included.

1. On a PC, build ``Gauntlet.pak`` from your own GOG copy: ``python tools/make_pak.py "C:\Games\Gauntlet"``.
2. Install **$asset** on the Odin 3 and open it once.
3. Copy ``Gauntlet.pak`` to ``Android/data/com.gauntlet.odin/files/`` (or pick it with **Choose Gauntlet.pak**), then start the app.

See the README for the details and the long-press icon options (button prompts, 60/120 fps, FPS / performance overlay).
"@
    $req = @{ tag_name = $tag; target_commitish = 'main'; name = "Gauntlet Odin 3 port $tag"; body = $body; make_latest = 'true' } | ConvertTo-Json
    try { $rel = Invoke-RestMethod -Method Post -Headers $h -Uri "https://api.github.com/repos/$repo/releases" -Body ([Text.Encoding]::UTF8.GetBytes($req)) -ContentType 'application/json' }
    catch { Stop-Here "could not create the release ($($_.Exception.Message))" }
} else {
    foreach ($a in $rel.assets) { if ($a.name -eq $asset) { Invoke-RestMethod -Method Delete -Headers $h -Uri $a.url | Out-Null; Write-Host "      (the older $asset on $tag replaced)" } }
}
$up = "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=$asset"
try { Invoke-RestMethod -Method Post -Headers $h -Uri $up -InFile $apk -ContentType 'application/vnd.android.package-archive' -TimeoutSec 7200 | Out-Null }
catch { Stop-Here "the APK didn't upload ($($_.Exception.Message))" }
Write-Host ""
Write-Host "Done! https://github.com/$repo  (download: $($rel.html_url))" -ForegroundColor Green
