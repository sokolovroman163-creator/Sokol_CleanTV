param([string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$platform = Join-Path $AndroidSdk 'platform-tools'
if (-not (Test-Path (Join-Path $platform 'adb.exe'))) { throw 'Install Android SDK Platform Tools, or pass -AndroidSdk <SDK folder>.' }
$work = Join-Path $PSScriptRoot 'work'
[void](New-Item -ItemType Directory -Force -Path $work)
$upstream = Join-Path $work 'upstream'
$commit = '3e06c1b2e731aff1b9f5d169c59d9e4d4a3fe4be'
if (-not (Test-Path -LiteralPath $upstream)) {
    & git clone https://github.com/Gathin23/cleanyourtv.git $upstream
    if ($LASTEXITCODE -ne 0) { throw 'Could not clone CleanYourTV.' }
    & git -C $upstream checkout --detach $commit
    if ($LASTEXITCODE -ne 0) { throw 'Could not check out the pinned upstream revision.' }
}
$actual = & git -C $upstream rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or "$actual".Trim() -ne $commit) { throw 'Unexpected upstream revision in work/upstream.' }
$vendor = Join-Path $PSScriptRoot 'vendor\cleanyourtv'
if (-not (Test-Path -LiteralPath $vendor)) {
    [void](New-Item -ItemType Directory -Force -Path (Join-Path $vendor 'tools'))
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $upstream 'tools') -File) {
        if ($file.Extension -in '.py','.ps1','.md','.sh','.txt') {
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $vendor 'tools')
        }
    }
    foreach ($name in 'examples','docs') {
        Copy-Item -LiteralPath (Join-Path $upstream $name) -Destination (Join-Path $vendor $name) -Recurse
    }
    foreach ($name in 'LICENSE','README.md','PROMPT.md') {
        Copy-Item -LiteralPath (Join-Path $upstream $name) -Destination (Join-Path $vendor $name)
    }
    [IO.File]::WriteAllText((Join-Path $vendor 'UPSTREAM_COMMIT.txt'), $commit)
    [void](New-Item -ItemType Directory -Path (Join-Path $vendor 'launcher'))
    Copy-Item -LiteralPath (Join-Path $upstream 'launcher\build-and-install.ps1') -Destination (Join-Path $vendor 'launcher')
}
$tools = Join-Path $PSScriptRoot 'vendor\platform-tools'
[void](New-Item -ItemType Directory -Force -Path $tools)
foreach ($name in 'adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties') {
    Copy-Item -LiteralPath (Join-Path $platform $name) -Destination $tools -Force
}
$runtime = Join-Path $work 'runtime'
if (-not (Test-Path (Join-Path $runtime 'python.exe'))) {
    $zip = Join-Path $work 'python-3.13.17-embed-amd64.zip'
    Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.13.17/python-3.13.17-embed-amd64.zip' -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $runtime
}
# A host Python is needed only by developers. The portable application includes its own runtime.
$hostEnvironment = Join-Path $work 'host-env'
$hostPython = Join-Path $hostEnvironment 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $hostPython)) {
    & py -3 -m venv $hostEnvironment
    if ($LASTEXITCODE -ne 0) { throw 'Install Python with the Windows py launcher.' }
}
$packages = Join-Path $runtime 'site-packages'
$wheels = Join-Path $work 'wheels'
[void](New-Item -ItemType Directory -Force -Path $wheels)
& $hostPython -m pip wheel --no-deps --wheel-dir $wheels adb-shell==0.4.4
if ($LASTEXITCODE -ne 0) { throw 'Could not build the adb-shell Python wheel.' }
$adbWheel = @(Get-ChildItem -LiteralPath $wheels -Filter 'adb_shell-0.4.4-*.whl')
if ($adbWheel.Count -ne 1) { throw 'Expected one adb-shell wheel.' }
& $hostPython -m pip install --only-binary=:all: --python-version 3.13 --implementation cp --abi cp313 --platform win_amd64 --target $packages $adbWheel[0].FullName PySide6==6.8.3 rsa==4.9.1 'cryptography>=46.0.7'
if ($LASTEXITCODE -ne 0) { throw 'Could not install runtime dependencies.' }
@('python313.zip','.','site-packages','import site') | Set-Content -LiteralPath (Join-Path $runtime 'python313._pth') -Encoding ascii
& .\download-resources.ps1
if ($LASTEXITCODE -ne 0) { throw 'Could not download and verify optional APKs.' }
Write-Output 'Dependencies prepared. Run build.ps1 to build the launcher and the portable Windows application.'
