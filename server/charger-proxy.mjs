// Chargis 공공 충전소 API 중계 서버(프록시).
//
// 왜 필요한가: data.go.kr 서비스 키를 앱(APK)에 넣으면 누구나 꺼내 쓸 수 있다. 그래서 배포용(release) 빌드에는 키를 넣지 않고,
// 앱은 이 서버를 부르고 이 서버가 키를 붙여 data.go.kr로 넘긴다(app/build.gradle.kts의 chargis.chargerApiBaseUrl).
//
// 하는 일
//   - 두 오퍼레이션만 받는다: getChargerInfo(충전기 목록), getChargerStatus(충전기 상태). 나머지 경로는 404.
//   - 정해 둔 파라미터만 넘긴다(pageNo, numOfRows, zcode, zscode, period, dataType). 값은 숫자·길이를 검사한다.
//   - 서비스 키는 환경 변수 CHARGER_API_KEY에서 읽어 붙인다. 응답·로그·오류 어디에도 키를 넣지 않는다.
//   - 같은 요청은 잠시 저장해 두고 재사용한다(목록 10분, 상태 1분). data.go.kr 호출 한도를 아끼고 응답을 빠르게 한다.
//   - IP마다 1분에 60번까지만 받는다(넘으면 429).
//   - APP_TOKEN을 정해 두면 X-Chargis-Token 헤더가 같은 요청만 받는다(앱에 넣은 값은 꺼낼 수 있으므로 남용을 줄이는 정도다).
//
// 실행: CHARGER_API_KEY=... node server/charger-proxy.mjs   (Node 18 이상, 외부 패키지 없음)
// 앱 설정(local.properties): chargis.usePublicChargerApi=true, chargis.chargerApiBaseUrl=https://<이 서버 주소>

import http from 'node:http';

const OPERATIONS = new Set(['getChargerInfo', 'getChargerStatus']);
// 파라미터별 허용 값. 정규식에 맞지 않으면 400.
const PARAMS = {
  pageNo: /^\d{1,4}$/,
  numOfRows: /^\d{1,4}$/,     // 앱은 9999까지 부른다
  zcode: /^\d{2}$/,           // 시도 코드 2자리
  zscode: /^\d{5}$/,          // 시군구 코드 5자리
  period: /^\d{1,2}$/,        // 상태 갱신 기간(분)
  dataType: /^(JSON|XML)$/,
};
const TTL_MS = { getChargerInfo: 10 * 60_000, getChargerStatus: 60_000 };
const RATE_PER_MIN = 60;
const MAX_CACHE = 200;

/**
 * 요청 처리기를 만든다. 테스트에서 [fetchImpl]과 [now]를 바꿔 끼운다.
 * @param {{apiKey: string, upstream?: string, appToken?: string, fetchImpl?: typeof fetch, now?: () => number}} opts
 */
export function createHandler({ apiKey, upstream = 'https://apis.data.go.kr/B552584/EvCharger', appToken, fetchImpl = fetch, now = Date.now }) {
  if (!apiKey) throw new Error('CHARGER_API_KEY가 비어 있습니다');
  const cache = new Map();   // 정리한 요청 → { at, status, body }
  const hits = new Map();    // IP → { windowStart, count }

  return async function handle(req, res) {
    const url = new URL(req.url, 'http://local');
    if (req.method !== 'GET') return send(res, 405, { error: 'GET만 받습니다' });
    if (url.pathname === '/health') return send(res, 200, { ok: true });

    // 경로 끝의 오퍼레이션 이름만 본다. 앱이 https://host/ev 처럼 앞부분을 붙여도 된다.
    const op = url.pathname.split('/').filter(Boolean).pop();
    if (!OPERATIONS.has(op)) return send(res, 404, { error: '지원하지 않는 경로' });
    if (appToken && req.headers['x-chargis-token'] !== appToken) return send(res, 401, { error: '인증 실패' });

    const ip = req.socket.remoteAddress ?? 'unknown';
    if (!allow(hits, ip, now())) return send(res, 429, { error: '요청이 너무 많습니다' });

    const params = new URLSearchParams();
    for (const [k, v] of url.searchParams) {
      if (k === 'serviceKey') continue;                 // 앱이 키를 보내도 무시한다
      const rule = PARAMS[k];
      if (!rule) continue;                              // 모르는 파라미터는 넘기지 않는다
      if (!rule.test(v)) return send(res, 400, { error: `잘못된 값: ${k}` });
      params.set(k, v);
    }
    if (!params.has('dataType')) params.set('dataType', 'JSON');
    params.sort();
    const cacheKey = `${op}?${params}`;
    const cached = cache.get(cacheKey);
    if (cached && now() - cached.at < TTL_MS[op]) return sendRaw(res, cached.status, cached.body, 'HIT');

    params.set('serviceKey', apiKey);
    let upstreamRes;
    try {
      upstreamRes = await fetchImpl(`${upstream}/${op}?${params}`, { signal: AbortSignal.timeout(10_000) });
    } catch {
      return send(res, 502, { error: '공공데이터 서버에 연결하지 못했습니다' });
    }
    const body = await upstreamRes.text();
    if (!upstreamRes.ok) return send(res, 502, { error: `공공데이터 서버 오류 ${upstreamRes.status}` });
    if (cache.size >= MAX_CACHE) cache.delete(cache.keys().next().value);
    cache.set(cacheKey, { at: now(), status: 200, body });
    return sendRaw(res, 200, body, 'MISS');
  };
}

// IP마다 1분 창에서 RATE_PER_MIN번까지 허용한다.
function allow(hits, ip, t) {
  const h = hits.get(ip);
  if (!h || t - h.windowStart >= 60_000) {
    hits.set(ip, { windowStart: t, count: 1 });
    return true;
  }
  h.count += 1;
  return h.count <= RATE_PER_MIN;
}

function send(res, status, obj) {
  sendRaw(res, status, JSON.stringify(obj));
}

function sendRaw(res, status, body, cacheState) {
  const headers = { 'content-type': 'application/json; charset=utf-8' };
  if (cacheState) headers['x-cache'] = cacheState;
  res.writeHead(status, headers);
  res.end(body);
}

// 직접 실행했을 때만 서버를 띄운다(테스트에서 import할 때는 띄우지 않는다).
if (import.meta.url === new URL(process.argv[1] ?? '', 'file:').href || process.argv[1]?.endsWith('charger-proxy.mjs')) {
  const port = Number(process.env.PORT ?? 8787);
  const handler = createHandler({ apiKey: process.env.CHARGER_API_KEY, appToken: process.env.APP_TOKEN || undefined });
  http.createServer(handler).listen(port, () => console.log(`charger-proxy listening on :${port}`));
}
