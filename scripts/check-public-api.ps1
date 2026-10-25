# 공공 충전소 API(data.go.kr, 한국환경공단 전기자동차 충전소 정보)를 실제 키로 한 번 불러,
# 앱 어댑터(PublicChargerData)가 가정한 응답 필드가 실제로 오는지 점검한다(docs/OPEN_ISSUES.md).
#   - 목록(getChargerInfo): chgerType 코드 분포, kind/kindDetail, limitYn/limitDetail, useTime, parkingFree, busiId, lastTedt
#   - 상태(getChargerStatus): stat 분포, lastTedt, nowTsdt
# 키는 local.properties의 chargis.chargerApiKey에서 읽고, 화면과 로그에 출력하지 않는다.
# 사용:  Set-ExecutionPolicy -Scope Process Bypass -Force; .\scripts\check-public-api.ps1 -Zcode 51
param(
    [string]$Zcode = '51',   # 시도 코드. 51 = 강원(특별자치도 코드). 옛 코드 42로도 불러 보고 비교한다
    [int]$Rows = 500
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$props = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path $props)) { throw 'local.properties가 없습니다.' }
$keyLine = Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*chargis\.chargerApiKey\s*=' } | Select-Object -First 1
if (-not $keyLine) { throw 'local.properties에 chargis.chargerApiKey가 없습니다. data.go.kr에서 키를 받아 넣어 주세요.' }
$key = ($keyLine -split '=', 2)[1].Trim()
$base = 'https://apis.data.go.kr/B552584/EvCharger'

function Get-Items([string]$op, [string]$zcode) {
    $query = "serviceKey=$([uri]::EscapeDataString($key))&pageNo=1&numOfRows=$Rows&zcode=$zcode&dataType=JSON"
    if ($op -eq 'getChargerStatus') { $query += '&period=10' }
    try {
        $res = Invoke-RestMethod -Uri "$base/$op`?$query" -TimeoutSec 20
    } catch {
        # 오류 메시지에 주소(키 포함)를 넣지 않는다.
        throw "$op 호출 실패: HTTP 오류 또는 네트워크 문제"
    }
    if ($res.resultCode -and $res.resultCode -ne '00') { throw "$op 결과 코드 $($res.resultCode): $($res.resultMsg)" }
    $list = $res.items.item
    if ($null -eq $list) { $list = $res.items }
    return @($list)
}

function Show-Field($items, [string]$name) {
    $present = @($items | Where-Object { $null -ne $_.$name -and "$($_.$name)" -ne '' }).Count
    $values = $items | ForEach-Object { "$($_.$name)" } | Where-Object { $_ -ne '' } | Group-Object | Sort-Object Count -Descending | Select-Object -First 8
    $top = ($values | ForEach-Object { "$($_.Name)×$($_.Count)" }) -join ', '
    Write-Host ("  {0,-12} 있음 {1,4}/{2}  {3}" -f $name, $present, $items.Count, $top)
}

foreach ($z in @($Zcode, '42')) {
    Write-Host "== 시도 코드 $z =="
    $info = Get-Items 'getChargerInfo' $z
    Write-Host "getChargerInfo: $($info.Count)건"
    foreach ($f in 'chgerType', 'output', 'stat', 'kind', 'kindDetail', 'limitYn', 'limitDetail', 'useTime', 'parkingFree', 'busiId', 'lastTedt', 'note') {
        Show-Field $info $f
    }
    $status = Get-Items 'getChargerStatus' $z
    Write-Host "getChargerStatus: $($status.Count)건"
    foreach ($f in 'stat', 'statUpdDt', 'lastTsdt', 'lastTedt', 'nowTsdt') {
        Show-Field $status $f
    }
}
Write-Host ''
Write-Host '확인할 것: chgerType 08~10이 오는지, lastTedt가 비어 있지 않은지, kindDetail C001~C003이 휴게소·쉼터에 붙는지,'
Write-Host '시도 코드 51과 42 중 어느 쪽이 결과를 주는지. 결과는 docs/OPEN_ISSUES.md에 적고 어댑터 가정과 다르면 고친다.'
