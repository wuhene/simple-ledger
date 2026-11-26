param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME,
    [switch]$Acceptance,
    [switch]$LedgerUpdate,
    [string]$PrivateLedgerArchive
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $projectRoot '..\..')).Path
$key = Join-Path $repoRoot '.tools\debug.keystore'
if (($Acceptance -and ($PrivateLedgerArchive -or $LedgerUpdate)) -or ($LedgerUpdate -and $PrivateLedgerArchive)) {
    throw '验收版、带账本安装包和无数据更新包请选择一种。'
}
if ($LedgerUpdate -and -not (Test-Path -LiteralPath $key -PathType Leaf)) {
    throw '无数据更新包需要当前安装版本使用的签名文件 .tools\debug.keystore。'
}
if ($PrivateLedgerArchive) {
    $archive = Get-Item -LiteralPath $PrivateLedgerArchive
    if ($archive.PSIsContainer -or $archive.Extension -ne '.zip') { throw '请提供爱记账分月 ZIP 备份。' }
}
if (-not $SdkPath) { $SdkPath = Join-Path $repoRoot '.tools\android-sdk' }
if (-not $JdkPath -or $JdkPath -match 'jdk1\.8|jdk-?8') {
    $localJdk = Get-ChildItem (Join-Path $repoRoot '.tools\jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $JdkPath = $localJdk.FullName }
}
if (-not $JdkPath -or -not (Test-Path (Join-Path $JdkPath 'bin\javac.exe'))) { throw '需要 JDK 17，并设置 JAVA_HOME 或传入 -JdkPath。' }
if (-not (Test-Path (Join-Path $SdkPath 'platforms\android-35\android.jar'))) { throw '需要 Android SDK 35，并设置 ANDROID_HOME 或传入 -SdkPath。' }

$env:JAVA_HOME = $JdkPath
$tools = Join-Path $SdkPath 'build-tools\35.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-35\android.jar'
$build = Join-Path $projectRoot $(if ($PrivateLedgerArchive) { 'app\build\manual-private' } elseif ($LedgerUpdate) { 'app\build\manual-update' } elseif ($Acceptance) { 'app\build\manual-acceptance' } else { 'app\build\manual' })
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null

function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe 失败，退出码 $LASTEXITCODE" }
}

$sources = @(Get-ChildItem (Join-Path $projectRoot 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
Run (Join-Path $JdkPath 'bin\javac.exe') (@('-encoding','UTF-8','-source','17','-target','17','-cp',$androidJar,'-d',$classes) + $sources)
Run (Join-Path $tools 'aapt2.exe') @('compile','--dir',(Join-Path $projectRoot 'app\src\main\res'),'-o',(Join-Path $build 'resources.zip'))
$sourceManifest = Get-Content -Raw (Join-Path $projectRoot 'app\src\main\AndroidManifest.xml')
$generatedManifest = Join-Path $build 'AndroidManifest.xml'
$gradleConfig = Get-Content -Raw (Join-Path $projectRoot 'app\build.gradle')
$versionCode = [regex]::Match($gradleConfig, 'versionCode\s+(\d+)').Groups[1].Value
$versionName = [regex]::Match($gradleConfig, "versionName\s+'([^']+)'").Groups[1].Value
if (-not $versionCode -or -not $versionName) { throw '无法读取 Android 版本号。' }
$applicationId = if ($PrivateLedgerArchive -or $LedgerUpdate) { 'com.wuhene.simpleledger.privateledger' } elseif ($Acceptance) { 'com.wuhene.simpleledger.acceptance' } else { 'com.wuhene.simpleledger' }
$manifestHead = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
$sourceManifest.Replace("$manifestHead>", "$manifestHead package=`"$applicationId`" android:versionCode=`"$versionCode`" android:versionName=`"$versionName`">").Replace('${applicationId}', $applicationId) | Set-Content -Encoding utf8 $generatedManifest
$link = @('link','-o',(Join-Path $build 'unsigned.apk'),'-I',$androidJar,'--manifest',$generatedManifest,'--min-sdk-version','26','--target-sdk-version','35','-A',(Join-Path $projectRoot 'app\src\main\assets'))
$link += @('-A', (Join-Path $projectRoot '..\shared\assets'))
if ($PrivateLedgerArchive) {
    $privateAssets = Join-Path $build 'private-assets'
    New-Item -ItemType Directory -Force -Path $privateAssets | Out-Null
    Copy-Item -LiteralPath $archive.FullName -Destination (Join-Path $privateAssets 'private-ledger.zip')
    $link += @('-A', $privateAssets)
} elseif ($Acceptance) {
    $acceptanceAssets = Join-Path $projectRoot 'app\src\acceptance\assets'
    if (-not (Test-Path (Join-Path $acceptanceAssets 'acceptance-ledger.zip'))) { throw '缺少独立验收包的合成账本。' }
    $link += @('-A', $acceptanceAssets)
}
Run (Join-Path $tools 'aapt2.exe') ($link + (Join-Path $build 'resources.zip'))
Run (Join-Path $JdkPath 'bin\jar.exe') @('cf',(Join-Path $build 'classes.jar'),'-C',$classes,'.')
Run (Join-Path $tools 'd8.bat') @('--min-api','26','--lib',$androidJar,'--output',$dex,(Join-Path $build 'classes.jar'))
Run (Join-Path $JdkPath 'bin\jar.exe') @('uf',(Join-Path $build 'unsigned.apk'),'-C',$dex,'classes.dex')
Run (Join-Path $tools 'zipalign.exe') @('-f','4',(Join-Path $build 'unsigned.apk'),(Join-Path $build 'aligned.apk'))

if (-not (Test-Path $key)) {
    Run (Join-Path $JdkPath 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-alias','androiddebugkey','-storepass','android','-keypass','android','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-keysize','2048','-validity','10000')
}
$apk = if ($LedgerUpdate) { Join-Path $build 'AiLedger-update.apk' } elseif ($PrivateLedgerArchive) { Join-Path $build 'AiLedger-private.apk' } else {
    Join-Path $projectRoot $(if ($Acceptance) { 'AiLedger-acceptance.apk' } else { 'SimpleLedger-debug.apk' })
}
Run (Join-Path $tools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,(Join-Path $build 'aligned.apk'))
Run (Join-Path $tools 'apksigner.bat') @('verify','--verbose',$apk)
Get-FileHash -Algorithm SHA256 $apk | Format-List
