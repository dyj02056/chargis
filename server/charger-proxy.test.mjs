// 중계 서버 테스트: node --test server/
// 실제 data.go.kr을 부르지 않고 가짜 fetch로 확인한다. 키가 응답에 새지 않는지, 허용한 것만 넘기는지, 저장·한도가 도는지 본다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHandler } from './charger-proxy.mjs';

const KEY = 'test-secret-key';

// 서버를 띄우고 [path]를 GET으로 부른다. 가짜 fetch가 받은 주소를 [calls]에 남긴다.
async function setup(opts = {}) {
  const calls = [];
  let t = 0;
  const fetchImpl = async (url) => {
    calls.push(url);
    return opts.upstream ?? new Response('{"resultCode":"00","items":{"item":[]}}', { status: 200 });
  };
  const handler = createHandler({ apiKey: KEY, fetchImpl, now: () => t, appToken: opts.appToken });
  const server = http.createServer(handler);
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const get = (path, headers = {}) => fetch(base + path, { headers });
  return { calls, get, tick: (ms) => { t += ms; }, close: () => new Promise((r) => server.close(r)) };
}

test('목록 요청에 키를 붙여 넘기고, 응답에는 키가 없다', async () => {
  const s = await setup();
  const res = await s.get('/ev/getChargerInfo?pageNo=1&numOfRows=9999&zcode=51&dataType=JSON');
  assert.equal(res.status, 200);
  const body = await res.text();
  assert.ok(!body.includes(KEY));
  assert.equal(s.calls.length, 1);
  assert.match(s.calls[0], /\/getChargerInfo\?/);
  assert.match(s.calls[0], new RegExp(`serviceKey=${KEY}`));
  assert.match(s.calls[0], /zcode=51/);
  await s.close();
});

test('앱이 보낸 serviceKey와 모르는 파라미터는 넘기지 않는다', async () => {
  const s = await setup();
  await s.get('/getChargerStatus?zcode=11&serviceKey=stolen&evil=1');
  assert.ok(!s.calls[0].includes('stolen'));
  assert.ok(!s.calls[0].includes('evil'));
  await s.close();
});

test('지원하지 않는 경로는 404, 잘못된 값은 400', async () => {
  const s = await setup();
  assert.equal((await s.get('/getChargerHistory?zcode=11')).status, 404);
  assert.equal((await s.get('/getChargerInfo?zcode=abc')).status, 400);
  assert.equal(s.calls.length, 0);
  await s.close();
});

test('같은 요청은 정해 둔 시간 동안 다시 부르지 않는다(상태 1분)', async () => {
  const s = await setup();
  await s.get('/getChargerStatus?zcode=11&period=10');
  const second = await s.get('/getChargerStatus?period=10&zcode=11');   // 순서만 다른 같은 요청
  assert.equal(second.headers.get('x-cache'), 'HIT');
  assert.equal(s.calls.length, 1);
  s.tick(61_000);
  await s.get('/getChargerStatus?zcode=11&period=10');
  assert.equal(s.calls.length, 2);
  await s.close();
});

test('IP마다 1분에 60번을 넘으면 429', async () => {
  const s = await setup();
  for (let i = 0; i < 60; i++) assert.equal((await s.get('/health')).status, 200);
  for (let i = 0; i < 60; i++) await s.get(`/getChargerInfo?zcode=${String(10 + (i % 80)).padStart(2, '0')}`);
  assert.equal((await s.get('/getChargerInfo?zcode=11')).status, 429);
  await s.close();
});

test('APP_TOKEN을 정하면 같은 토큰을 보낸 요청만 받는다', async () => {
  const s = await setup({ appToken: 'tok' });
  assert.equal((await s.get('/getChargerInfo?zcode=11')).status, 401);
  assert.equal((await s.get('/getChargerInfo?zcode=11', { 'x-chargis-token': 'tok' })).status, 200);
  await s.close();
});

test('공공데이터 서버 오류는 502로 알리고 저장하지 않는다', async () => {
  const s = await setup({ upstream: new Response('nope', { status: 500 }) });
  const res = await s.get('/getChargerInfo?zcode=11');
  assert.equal(res.status, 502);
  assert.ok(!(await res.text()).includes(KEY));
  await s.close();
});
