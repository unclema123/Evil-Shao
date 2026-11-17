# 构建 Android APK（环境全部指向 E 盘，不碰 C 盘）
$ErrorActionPreference = 'Stop'

$env:JAVA_HOME        = 'D:\Dev_Environment\jdk21\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = 'E:\Dev\gradle-home'
$env:ANDROID_HOME     = 'E:\Dev\android-sdk'
$env:ANDROID_SDK_ROOT = 'E:\Dev\android-sdk'

$gradle = 'E:\Dev\gradle-home\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat'
$proj   = 'E:\EvilShao\android'

& $gradle -p $proj assembleDebug --console=plain "-Dorg.gradle.java.home=$env:JAVA_HOME"
if ($LASTEXITCODE -ne 0) { throw "构建失败，exit=$LASTEXITCODE" }

$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
Copy-Item $apk 'E:\EvilShao\android\app-debug.apk' -Force
"APK: $apk  ($([math]::Round((Get-Item $apk).Length/1MB,2)) MB)"
"已同步到 E:\EvilShao\android\app-debug.apk"