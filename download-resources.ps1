$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$resources=Join-Path $PSScriptRoot 'resources'
[void](New-Item -ItemType Directory -Force -Path $resources)
$release=Invoke-RestMethod -Uri 'https://api.github.com/repos/romanvht/ByeByeDPI/releases/tags/v.1.8.2'
$manifest=@{version='1.8.2';source=$release.html_url;certificate='77451075acea406406475d74d459883a49a64051faf32e42f718f3f9777a8dfb';byedpi=@{}}
$env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
$signer=Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0\apksigner.bat'
foreach($abi in 'armeabi-v7a','arm64-v8a'){
    $assets=@($release.assets | Where-Object {$_.name -match ([regex]::Escape($abi) + '.*\.apk$')})
    if($assets.Count -ne 1){throw "Expected one $abi asset"}
    $asset=$assets[0]
    if($asset.digest -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Missing release hash'}
    $path=Join-Path $resources $asset.name
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $path -TimeoutSec 40
    $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -ne $asset.digest.Substring(7)){throw 'Download hash mismatch'}
    $signature=& $signer verify --print-certs $path
    if($LASTEXITCODE -ne 0 -or -not ($signature -match "^Signer #1 certificate SHA-256 digest: $($manifest.certificate)$")){throw 'Certificate verification failed'}
    $manifest.byedpi[$abi]=@{file=$asset.name;sha256=$hash;url=$asset.browser_download_url}
}
[IO.File]::WriteAllText((Join-Path $resources 'assets.json'), ($manifest | ConvertTo-Json -Depth 5))
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/romanvht/ByeByeDPI/v.1.8.2/LICENSE' -OutFile (Join-Path $resources 'ByeByeDPI-LICENSE.txt') -TimeoutSec 30
