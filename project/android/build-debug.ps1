param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $projectRoot '..\..')).Path
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
$build = Join-Path $projectRoot 'app\build\manual'
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
$sourceManifest.Replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wuhene.simpleledger" android:versionCode="3" android:versionName="0.1.2">') | Set-Content -Encoding utf8 $generatedManifest
Run (Join-Path $tools 'aapt2.exe') @('link','-o',(Join-Path $build 'unsigned.apk'),'-I',$androidJar,'--manifest',$generatedManifest,'--min-sdk-version','26','--target-sdk-version','35',(Join-Path $build 'resources.zip'))
Run (Join-Path $JdkPath 'bin\jar.exe') @('cf',(Join-Path $build 'classes.jar'),'-C',$classes,'.')
Run (Join-Path $tools 'd8.bat') @('--min-api','26','--lib',$androidJar,'--output',$dex,(Join-Path $build 'classes.jar'))
Run (Join-Path $JdkPath 'bin\jar.exe') @('uf',(Join-Path $build 'unsigned.apk'),'-C',$dex,'classes.dex')
Run (Join-Path $tools 'zipalign.exe') @('-f','4',(Join-Path $build 'unsigned.apk'),(Join-Path $build 'aligned.apk'))

$key = Join-Path $repoRoot '.tools\debug.keystore'
if (-not (Test-Path $key)) {
    Run (Join-Path $JdkPath 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-alias','androiddebugkey','-storepass','android','-keypass','android','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-keysize','2048','-validity','10000')
}
$apk = Join-Path $projectRoot 'SimpleLedger-debug.apk'
Run (Join-Path $tools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,(Join-Path $build 'aligned.apk'))
Run (Join-Path $tools 'apksigner.bat') @('verify','--verbose',$apk)
Get-FileHash -Algorithm SHA256 $apk | Format-List
