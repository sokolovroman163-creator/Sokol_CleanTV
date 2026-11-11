$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot' }
if (-not (Test-Path -LiteralPath $env:JAVA_HOME)) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'Sokol_CleanTV-gradle'
$env:ANDROID_USER_HOME = Join-Path $project 'work\android-phone'
[void](New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME)
$env:SOKOL_SIGNING_STORE = Join-Path $env:ANDROID_USER_HOME 'sokol-phone-release.p12'
$passwordFile = Join-Path $env:ANDROID_USER_HOME 'signing.password'
if (-not (Test-Path -LiteralPath $passwordFile)) {
    if (Test-Path -LiteralPath $env:SOKOL_SIGNING_STORE) { throw 'Signing password is missing. Restore it before building updates.' }
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random)
    $rng.Dispose()
    [IO.File]::WriteAllText($passwordFile,[Convert]::ToBase64String($random))
}
$env:SOKOL_SIGNING_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
if (-not (Test-Path -LiteralPath $env:SOKOL_SIGNING_STORE)) {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -storetype PKCS12 -keystore $env:SOKOL_SIGNING_STORE -alias sokol-cleantv -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 9125 -dname 'CN=Sokol_CleanTV, OU=Android, O=Sokol, C=RU' -storepass:file $passwordFile -keypass:file $passwordFile
    if ($LASTEXITCODE -ne 0) { throw 'Could not create private signing key.' }
}
$payload = Join-Path $PSScriptRoot 'app\src\main\assets\payloads'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD_PARTY.md') -Destination (Join-Path $PSScriptRoot 'app\src\main\assets\notices\THIRD_PARTY.md') -Force
[void](New-Item -ItemType Directory -Force -Path $payload)
$tvApk = Join-Path $project 'launcher\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $tvApk)) { throw 'Build the sibling launcher project first. See android-app/README.md.' }
Copy-Item -LiteralPath $tvApk -Destination (Join-Path $payload 'tvhome.apk') -Force
$metadata = Get-Content -LiteralPath (Join-Path $project 'resources\assets.json') -Raw | ConvertFrom-Json
foreach ($abi in 'armeabi-v7a','arm64-v8a') {
    $info = $metadata.byedpi.$abi
    $source = Join-Path $project ('resources\' + $info.file)
    if (-not (Test-Path -LiteralPath $source)) { Invoke-WebRequest -Uri $info.url -OutFile $source }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $info.sha256) { throw "ByeByeDPI checksum mismatch: $abi" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $payload "byedpi-$abi.apk") -Force
}
$hashes = [ordered]@{}
foreach ($file in Get-ChildItem -LiteralPath $payload -Filter '*.apk') { $hashes[$file.Name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$encoding = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText((Join-Path $payload 'manifest.json'),($hashes | ConvertTo-Json),$encoding)
$catalog = @()
foreach ($file in Get-ChildItem -Path (Join-Path $project 'vendor\cleanyourtv\examples\*\tv.json')) {
    $record = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $catalog += [ordered]@{ brand=$record.brand; tv=$record.tv; os=$record.os }
}
if ($catalog.Count -eq 0) { throw 'Run the root bootstrap.ps1 first to prepare the public TV catalogue.' }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'app\src\main\assets\catalog.json'),(ConvertTo-Json -InputObject $catalog -Depth 5),$encoding)
$stage = Join-Path $env:TEMP 'Sokol_CleanTV-android-build'
[void](New-Item -ItemType Directory -Force -Path $stage)
# Android Gradle Plugin requires an ASCII build path on Windows.
& robocopy $PSScriptRoot $stage /E /XD .gradle build /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -gt 7) { throw 'Failed to copy Android sources into the build directory.' }
Push-Location $stage
try {
    & .\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Android build failed. Sources and reports: $stage" }
    $out = Join-Path $PSScriptRoot 'app\build'
    [void](New-Item -ItemType Directory -Force -Path $out)
    Copy-Item -LiteralPath (Join-Path $stage 'app\build\outputs') -Destination $out -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $stage 'app\build\reports') -Destination $out -Recurse -Force
    $apk = Join-Path $out 'outputs\apk\release\app-release.apk'
    $deliver = Join-Path $PSScriptRoot 'Sokol_CleanTV-Android.apk'
    Copy-Item -LiteralPath $apk -Destination $deliver -Force
    (Get-FileHash -LiteralPath $deliver -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath ($deliver + '.sha256') -Encoding ASCII
    Write-Host "APK: $deliver"
} finally { Pop-Location }
