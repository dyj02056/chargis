# Android SDK(명령줄 도구, 플랫폼, 빌드 도구, 에뮬레이터, 시스템 이미지)를 설치하고
# 데모용 가로형 태블릿 에뮬레이터(chargis_wide)를 만든다. Android Studio 없이 빌드/실행할 때 쓴다.
#
# 사용법:  powershell -ExecutionPolicy Bypass -File scripts\setup-android-sdk.ps1 [-JavaHome <JDK17 경로>] [-SdkRoot <설치 경로>]
# 실행하면 Android SDK 라이선스에 동의(y)한다.
param(
    [string]$SdkRoot = (Join-Path $HOME 'Android\Sdk'),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    $found = Get-ChildItem (Join-Path $HOME 'tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $found) { throw 'JDK 17을 찾지 못했습니다. -JavaHome 으로 JDK 17 경로를 지정하세요.' }
    $JavaHome = $found.FullName
}
$env:JAVA_HOME = $JavaHome
Write-Host "JDK: $JavaHome"
Write-Host "SDK: $SdkRoot"

$sdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
    Write-Host '명령줄 도구 내려받는 중...'
    $zip = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    $tmp = Join-Path $env:TEMP 'android-cmdline-tools'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile $zip -UseBasicParsing
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip -DestinationPath $tmp
    New-Item -ItemType Directory -Force (Join-Path $SdkRoot 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $SdkRoot 'cmdline-tools\latest')
    Remove-Item $zip, $tmp -Recurse -Force
}

Write-Host '라이선스 동의 중...'
$yes = Join-Path $env:TEMP 'android-sdk-yes.txt'
Set-Content -Path $yes -Value (@('y') * 40) -Encoding ascii
cmd /c "`"$sdkManager`" --sdk_root=`"$SdkRoot`" --licenses < `"$yes`"" | Out-Null
Remove-Item $yes

Write-Host 'SDK 패키지 설치 중 (약 2GB, 몇 분 걸립니다)...'
& $sdkManager "--sdk_root=$SdkRoot" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'build-tools;35.0.0' 'emulator' 'system-images;android-35;google_apis;x86_64'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager 실패 (exit $LASTEXITCODE)" }

$avdManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$avdDir = Join-Path $HOME '.android\avd\chargis_wide.avd'
if (-not (Test-Path $avdDir)) {
    Write-Host '에뮬레이터(chargis_wide) 만드는 중...'
    cmd /c "echo no | `"$avdManager`" create avd -n chargis_wide -k system-images;android-35;google_apis;x86_64 -d pixel_tablet"
}

# 프로젝트가 이 SDK를 쓰도록 local.properties 갱신 (커밋하지 않는 파일)
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkDirValue = ($SdkRoot -replace '\\', '/') -replace ':', '\:'
Set-Content -Path (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkDirValue" -Encoding ascii

Write-Host ''
Write-Host '완료. 에뮬레이터 실행:'
Write-Host "  & `"$SdkRoot\emulator\emulator.exe`" -avd chargis_wide"
