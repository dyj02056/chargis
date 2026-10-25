# 공공 충전소 API 중계 서버

배포용(release) 앱에서 공공 충전소 데이터를 쓰기 위한 작은 중계 서버입니다. data.go.kr 서비스 키를 앱에 넣으면 APK에서 꺼낼 수 있으므로, 키는 이 서버에만 두고 앱은 이 서버를 부릅니다.

```
앱 ──GET /getChargerInfo?zcode=51──▶ 중계 서버 ──serviceKey 붙여서──▶ data.go.kr
```

## 하는 일

- `getChargerInfo`(충전기 목록), `getChargerStatus`(충전기 상태) 두 경로만 받습니다. 나머지는 404입니다.
- 정해 둔 파라미터(`pageNo`, `numOfRows`, `zcode`, `zscode`, `period`, `dataType`)만 넘기고, 값의 형식을 검사합니다. 앱이 `serviceKey`를 보내도 버립니다.
- 같은 요청은 목록 10분, 상태 1분 동안 저장해 두고 다시 씁니다. data.go.kr 호출 한도를 아낍니다.
- IP마다 1분에 60번까지만 받습니다.
- 응답, 오류, 로그 어디에도 서비스 키를 넣지 않습니다.
- `APP_TOKEN`을 정하면 `X-Chargis-Token` 헤더가 같은 요청만 받습니다. 앱에 넣은 토큰도 꺼낼 수 있으므로 남용을 줄이는 정도로만 씁니다.

## 실행

Node 18 이상이면 외부 패키지 없이 돌아갑니다.

```bash
CHARGER_API_KEY=발급받은키 PORT=8787 node server/charger-proxy.mjs
```

```bash
node --test server/charger-proxy.test.mjs
```

| 환경 변수 | 필수 | 설명 |
|---|---|---|
| `CHARGER_API_KEY` | 예 | data.go.kr 서비스 키(일반 인증키, 디코딩된 값) |
| `PORT` | 아니오 | 기본 8787 |
| `APP_TOKEN` | 아니오 | 정하면 `X-Chargis-Token` 헤더가 같은 요청만 받음 |

`GET /health`로 살아 있는지 확인합니다.

## 앱 연결

`local.properties`에 다음을 넣고 빌드합니다. 이 파일은 커밋하지 않습니다.

```
chargis.usePublicChargerApi=true
chargis.chargerApiBaseUrl=https://중계서버주소
chargis.chargerApiToken=APP_TOKEN과 같은 값(정했을 때만)
```

배포용 빌드에는 `chargis.chargerApiKey`가 들어가지 않습니다(`app/build.gradle.kts`).

## 배포할 때

- HTTPS 뒤에 둡니다(HTTP로 두면 응답이 중간에 바뀔 수 있습니다).
- 어디에 둘지(클라우드 함수, 작은 VM 등)는 아직 정하지 않았습니다(`docs/OPEN_ISSUES.md`). 이 서버는 상태를 메모리에만 두므로 인스턴스를 여러 개 띄우면 저장과 한도는 인스턴스마다 따로 셉니다.
- data.go.kr 일일 호출 한도는 키마다 다릅니다. 앱 사용자가 늘면 저장 시간을 늘리거나 공용 캐시(예: Redis)를 붙입니다.
