param([Parameter(Mandatory=$true)][string]$Upstream)
$ErrorActionPreference='Stop'
$Upstream=(Resolve-Path -LiteralPath $Upstream).Path
$vendor=Join-Path $PSScriptRoot 'vendor\cleanyourtv'
if(Test-Path -LiteralPath $vendor){throw 'Vendor source already exists. Preparation only runs on a new project.'}
[void](New-Item -ItemType Directory -Force -Path (Join-Path $vendor 'tools'))
foreach($file in Get-ChildItem -LiteralPath (Join-Path $Upstream 'tools') -File){
    if($file.Extension -in '.py','.ps1','.md','.sh','.txt'){
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $vendor 'tools')
    }
}
foreach($name in 'examples','docs'){
    Copy-Item -LiteralPath (Join-Path $Upstream $name) -Destination (Join-Path $vendor $name) -Recurse
}
foreach($name in 'LICENSE','README.md','PROMPT.md'){
    Copy-Item -LiteralPath (Join-Path $Upstream $name) -Destination (Join-Path $vendor $name)
}
$repoArgument=$Upstream.Replace('\','/')
$commit=& git -c "safe.directory=$repoArgument" -C $Upstream rev-parse HEAD
[IO.File]::WriteAllText((Join-Path $vendor 'UPSTREAM_COMMIT.txt'), "$commit")
$launcher=Join-Path $PSScriptRoot 'launcher'
[void](New-Item -ItemType Directory -Path $launcher)
foreach($name in 'app','gradle'){
    $src=Join-Path $Upstream "launcher\$name"
    $files=Get-ChildItem -LiteralPath $src -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]build[\\/]|[\\/]\.gradle[\\/]' }
    foreach($file in $files){
        $relative=$file.FullName.Substring((Join-Path $Upstream 'launcher').Length+1)
        $to=Join-Path $launcher $relative
        [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to))
        Copy-Item -LiteralPath $file.FullName -Destination $to
    }
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $Upstream 'launcher') -File){
    if($file.Name -ne 'local.properties') { Copy-Item -LiteralPath $file.FullName -Destination $launcher }
}
# Start from the committed generic defaults, excluding the first TV's local dock choices.
$config=& git -c "safe.directory=$repoArgument" -C $Upstream show 'HEAD:launcher/app/src/main/res/values/config.xml'
[IO.File]::WriteAllLines((Join-Path $launcher 'app\src\main\res\values\config.xml'),[string[]]$config)
[void](New-Item -ItemType Directory -Path (Join-Path $vendor 'launcher'))
Copy-Item -LiteralPath (Join-Path $Upstream 'launcher\build-and-install.ps1') -Destination (Join-Path $vendor 'launcher')
$platform=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools'
$target=Join-Path $PSScriptRoot 'vendor\platform-tools'
[void](New-Item -ItemType Directory -Path $target)
foreach($name in 'adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties'){
    Copy-Item -LiteralPath (Join-Path $platform $name) -Destination $target
}
$runtime=Join-Path $PSScriptRoot 'work\runtime'
Copy-Item -LiteralPath (Join-Path $Upstream 'tools\venv\Lib\site-packages\adb_shell') -Destination (Join-Path $runtime 'site-packages') -Recurse
@('python313.zip','.','site-packages','import site') | Set-Content -LiteralPath (Join-Path $runtime 'python313._pth') -Encoding ascii
