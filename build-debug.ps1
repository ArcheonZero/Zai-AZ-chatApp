$ErrorActionPreference = 'Stop'

# JAVA_HOME: берём из окружения; если не задан — пробуем типовые места.
if (-not $env:JAVA_HOME) {
    $candidates = @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-17*'
    )
    foreach ($c in $candidates) {
        $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $env:JAVA_HOME = $found.FullName; break }
    }
}
if (-not $env:JAVA_HOME) {
    Write-Host 'JAVA_HOME не найден. Укажите JDK 17 в переменной окружения JAVA_HOME.' -ForegroundColor Red
    exit 1
}

# Android SDK: путь из local.properties, иначе из ANDROID_HOME/ANDROID_SDK_ROOT.
$localProps = Join-Path $PSScriptRoot 'local.properties'
if (Test-Path $localProps) {
    $sdkLine = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) {
        $env:ANDROID_HOME = ($sdkLine -replace '^sdk\.dir=', '').Replace('/', '\')
        $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    }
}

Set-Location $PSScriptRoot

Write-Host 'Building debug APK...' -ForegroundColor Cyan
& .\gradlew.bat --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $distDir = Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    $distApk = Join-Path $distDir 'AZ-chat-2.0.0-debug.apk'
    Copy-Item -LiteralPath $apk -Destination $distApk -Force

    $item = Get-Item $apk
    Write-Host ''
    Write-Host 'APK ready:' -ForegroundColor Green
    Write-Host $item.FullName
    Write-Host ('Size: {0:N2} MB' -f ($item.Length / 1MB))
    Write-Host "Copy: $distApk" -ForegroundColor Green
} else {
    Write-Host 'APK not found.' -ForegroundColor Red
    exit 1
}
