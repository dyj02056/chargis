# 에뮬레이터 부팅이 끝날 때까지 기다린 뒤 앱을 빌드·설치하고 실행한다.
# 먼저 다른 터미널에서 에뮬레이터를 켜 둔다:  & "$HOME\Android\Sdk\emulator\emulator.exe" -avd chargis_wide
param([string]$SdkRoot = (Join-Path $HOME 'Android\Sdk'))
$ErrorActionPreference = 'Stop'
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$projectRoot = Split-Path $PSScriptRoot -Parent

# adb = 컴퓨터에서 안드로이드 기기를 조종하는 도구
# 2초마다 부팅 완료 여부를 확인한다(최대 120번 = 4분).
Write-Host '에뮬레이터 부팅을 기다리는 중...'
& $adb wait-for-device
for ($i = 0; $i -lt 120; $i++) {
    if ((& $adb shell getprop sys.boot_completed 2>$null) -match '1') { break }
    Start-Sleep -Seconds 2
}
if ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { throw '에뮬레이터 부팅이 끝나지 않았습니다.' }

Write-Host '앱 빌드·설치 중...'
Push-Location $projectRoot
try {
    & .\gradlew.bat installDebug
    if ($LASTEXITCODE -ne 0) { throw "installDebug 실패 (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# 설치한 앱의 첫 화면(MainActivity)을 띄운다.
& $adb shell am start -n com.chargis.app/.MainActivity | Out-Null
Write-Host 'Chargis를 실행했습니다. 에뮬레이터 창을 확인하세요.'
