param([switch]$SkipLauncherBuild)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$repo = Join-Path $PSScriptRoot 'vendor\cleanyourtv'
$runtime = Join-Path $PSScriptRoot 'work\runtime'
if (-not (Test-Path (Join-Path $runtime 'python.exe'))) {
    throw 'Prepare work/runtime with the embeddable Python runtime and the packages listed in requirements.txt.'
}
if (-not $SkipLauncherBuild) {
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $env:GRADLE_USER_HOME = Join-Path ([IO.Path]::GetTempPath()) 'Sokol_CleanTV-gradle'
    $env:ANDROID_USER_HOME = Join-Path $PSScriptRoot 'work\android'
    # Android Gradle Plugin rejects Cyrillic project paths on Windows. Build a source copy
    # in the system temporary directory, then return the APK to the requested project.
    $buildRoot = Join-Path ([IO.Path]::GetTempPath()) ('Sokol_CleanTV-build-' + [guid]::NewGuid().ToString('N'))
    $sourceRoot = Join-Path $PSScriptRoot 'launcher'
    [void](New-Item -ItemType Directory -Path $buildRoot)
    foreach ($file in (Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]build[\\/]|[\\/]\.gradle[\\/]' })) {
        $relative = $file.FullName.Substring($sourceRoot.Length + 1)
        $target = Join-Path $buildRoot $relative
        [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target))
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
    Push-Location $buildRoot
    try {
        & .\gradlew.bat --no-daemon --console=plain testReleaseUnitTest lintRelease assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Launcher validation or build failed.' }
    } finally { Pop-Location }
    $builtApk = Join-Path $buildRoot 'app\build\outputs\apk\release\app-release.apk'
    $localApk = Join-Path $sourceRoot 'app\build\outputs\apk\release'
    [void](New-Item -ItemType Directory -Force -Path $localApk)
    Copy-Item -LiteralPath $builtApk -Destination (Join-Path $localApk 'app-release.apk') -Force
}
$apk = Join-Path $PSScriptRoot 'launcher\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'The built launcher APK is missing.' }
$apkFolder = Join-Path $repo 'launcher\app\build\outputs\apk\release'
[void](New-Item -ItemType Directory -Force -Path $apkFolder)
Copy-Item -LiteralPath $apk -Destination (Join-Path $apkFolder 'app-release.apk') -Force
$pythonFolder = Join-Path $repo 'tools\venv\Scripts'
[void](New-Item -ItemType Directory -Force -Path $pythonFolder)
Copy-Item -Path (Join-Path $runtime '*') -Destination $pythonFolder -Recurse -Force
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /target:winexe /reference:System.Windows.Forms.dll /out:Sokol_CleanTV.exe Launcher.cs
if ($LASTEXITCODE -ne 0) { throw 'Windows launcher compilation failed.' }
$dist = Join-Path $PSScriptRoot 'dist\Sokol_CleanTV'
if (Test-Path -LiteralPath $dist) { throw 'dist/Sokol_CleanTV already exists. Move it aside before packaging a new build.' }
[void](New-Item -ItemType Directory -Path $dist)
foreach ($name in 'app.py','sokol_cleantv','resources','vendor','Sokol_CleanTV.exe','README.md','THIRD_PARTY.md') {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $dist $name) -Recurse
}
Write-Output "Portable application: $dist"
