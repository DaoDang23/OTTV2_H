import http from 'k6/http';
import { check, sleep } from 'k6';

const base = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const state = { ready: false, botId: null, submissionId: null, lastViewAt: 0, lastHeartbeatAt: 0 };
const password = __ENV.TEST_PASSWORD || 'ChangeThis_LoadTest_Only!';
const source = `import json,sys
for line in sys.stdin:
 state=json.loads(line); piece=state['yourPieces'][0]; target=state.get('targets',[[4,4]])[0]; r,c=piece['r'],piece['c']
 if r!=target[0]: r+=1 if target[0]>r else -1
 elif c!=target[1]: c+=1 if target[1]>c else -1
 else: r=max(0,r-1)
 print(json.dumps({'pieceId':piece['id'],'to':[r,c]}),flush=True)
`;

export const options = {
  stages: [
    { duration: __ENV.RAMP_UP || '2m', target: Number(__ENV.USERS || 3000) },
    { duration: __ENV.HOLD || '5m', target: Number(__ENV.USERS || 3000) },
    { duration: __ENV.RAMP_DOWN || '1m', target: 0 },
  ],
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<2000'] },
};

function call(path, method = 'GET', body, token) {
  const headers = body === undefined ? {} : { 'Content-Type': 'application/json' };
  if (token) headers[token.headerName] = token.token;
  return http.request(method, `${base}${path}`, body === undefined ? null : JSON.stringify(body), { headers, tags: { route: path } });
}

function csrf() {
  const response = call('/api/auth/csrf');
  check(response, { 'csrf request succeeded': (r) => r.status === 200 });
  return response.status === 200 ? response.json() : null;
}

function initialize() {
  const suffix = String(__VU).padStart(5, '0');
  const email = `${__ENV.EMAIL_PREFIX || 'loadtest'}${suffix}@example.test`;
  const token = csrf();
  if (!token) return false;
  if (__ENV.REGISTER_USERS === 'true') {
    const registered = call('/api/auth/register', 'POST', {
      studentCode: `${__ENV.STUDENT_PREFIX || 'LOAD'}${suffix}`,
      email,
      password,
      displayName: `Load Bot ${suffix}`,
    }, token);
    check(registered, { 'account registered': (r) => r.status === 201 || r.status === 200 });
    if (![200, 201].includes(registered.status)) return false;
  }
  const login = call('/api/auth/login', 'POST', { email, password }, csrf());
  if (!check(login, { 'login succeeded': (r) => r.status === 200 || r.status === 204 })) return false;
  const botsResponse = call('/api/bots');
  if (botsResponse.status !== 200) return false;
  let bots = botsResponse.json();
  let bot = bots[0];
  if (!bot) {
    const created = call('/api/bots', 'POST', { name: `Load-${suffix}` }, csrf());
    if (![200, 201].includes(created.status)) return false;
    bot = created.json();
  }
  state.botId = bot.id;
  if (__ENV.SUBMIT_CODE === 'true') {
    const submitted = call(`/api/bots/${state.botId}/submissions`, 'POST', { language: 'PYTHON', source }, csrf());
    if (![200, 201, 202].includes(submitted.status)) return false;
    state.submissionId = submitted.json().id;
  }
  return true;
}

export default function () {
  if (!state.ready) state.ready = initialize();
  if (!state.ready) { sleep(1); return; }

  const now = Date.now();
  if (state.submissionId && now - state.lastHeartbeatAt >= 60000) {
    const submissions = call(`/api/bots/${state.botId}/submissions`);
    if (submissions.status === 200) {
      const ready = submissions.json().find((item) => item.id === state.submissionId && ['COMPILED', 'ACTIVE'].includes(item.status));
      if (ready) {
        call('/api/ladder/online', 'POST', { botId: state.botId, submissionId: state.submissionId }, csrf());
        state.lastHeartbeatAt = now;
      }
    }
  }
  if (now - state.lastViewAt >= 30000) {
    const ranking = call('/api/ladder/rankings?limit=100');
    check(ranking, { 'ladder ranking returned': (r) => r.status === 200 });
    const history = call('/api/ladder/history');
    check(history, { 'ladder history returned': (r) => r.status === 200 });
    state.lastViewAt = now;
  }
  sleep(1);
}
