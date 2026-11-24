import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createApp } from '../server.js';
import { validate, interpretResponse, retryDelay } from '../public/registration.js';

const valid = {
  user_name: 'Example Person',
  mobile_number: '+14155550123',
  email: 'person@example.test',
  age: '25',
  gender: 'example',
  indoor_activity: 'Reading',
  outdoor_activity: 'Walking',
};

test('validates required fields, exact phone format, email, and integer age boundaries', () => {
  assert.deepEqual(validate(valid), {});
  assert.equal(Object.keys(validate({})).length, 7);
  for (const age of ['0', '120']) assert.equal(validate({ ...valid, age }).age, undefined);
  for (const age of ['-1', '121', '1.5', '1e2', ' ']) {
    assert.ok(validate({ ...valid, age }).age, age);
  }
  for (const mobile_number of ['14155550123', '+012', '+1', '+1234567890123456', '+1 4155550123']) {
    assert.ok(validate({ ...valid, mobile_number }).mobile_number, mobile_number);
  }
  assert.ok(validate({ ...valid, email: 'invalid@' }).email);
  assert.ok(validate({ ...valid, gender: '   ' }).gender);
});

test('enforces backend UTF-16 length boundaries, including surrogate pairs', () => {
  for (const [name, max] of Object.entries({
    user_name: 120, gender: 64, indoor_activity: 2000, outdoor_activity: 2000,
  })) {
    assert.equal(validate({ ...valid, [name]: 'a'.repeat(max) })[name], undefined);
    assert.ok(validate({ ...valid, [name]: 'a'.repeat(max + 1) })[name]);
    assert.ok(validate({ ...valid, [name]: '\u{1f600}'.repeat(max / 2 + 1) })[name]);
  }
  assert.equal(validate({ ...valid, email: `${'a'.repeat(241)}@example.test` }).email, undefined);
  assert.ok(validate({ ...valid, email: `${'a'.repeat(242)}@example.test` }).email);
});

test('only a valid 201 succeeds; ambiguous responses never imply success', () => {
  assert.deepEqual(interpretResponse(201, { registration_id: 'P-ABC123' }), { id: 'P-ABC123' });
  for (const body of [null, {}, { registration_id: 'P-abc123' }, { registration_id: 123 }]) {
    assert.equal(interpretResponse(201, body).unknown, true);
  }
  for (const status of [500, 502, 503]) assert.equal(interpretResponse(status, null).unknown, true);
  for (const status of [200, 409, 413, 422, 429, 404]) {
    const result = interpretResponse(status, { registration_id: 'P-ABC123' });
    assert.equal(result.id, undefined);
    assert.ok(result.message);
  }
  assert.equal(new Set([409, 413, 422, 429].map((s) => interpretResponse(s).message)).size, 4);
});

test('respects Retry-After seconds and HTTP dates with a conservative missing-header delay', () => {
  const now = Date.parse('2026-01-01T00:00:00Z');
  assert.equal(retryDelay('60', now), 60_000);
  assert.equal(retryDelay('Thu, 01 Jan 2026 00:02:00 GMT', now), 120_000);
  assert.equal(retryDelay(null, now), 60_000);
  assert.equal(retryDelay('invalid', now), 60_000);
  assert.equal(retryDelay('0', now), 1000);
});

async function listen(server, t) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve) => {
    server.close(resolve);
    server.closeAllConnections();
  }));
  return `http://127.0.0.1:${server.address().port}`;
}

test('container mode permits only the Compose API host in addition to loopback', () => {
  assert.throws(() => createApp({ backendUrl: 'http://api:8080' }), /loopback/);
  assert.doesNotThrow(() => createApp({ backendUrl: 'http://api:8080', containerMode: true }));
  for (const backendUrl of ['http://example.com:8080', 'http://api:8080/path', 'http://user:pass@api:8080']) {
    assert.throws(() => createApp({ backendUrl, containerMode: true }), /BACKEND_URL/);
  }
});

test('proxy forwards exact registration data and status metadata without credentials or retries', async (t) => {
  let requests = 0;
  let upstreamStatus = 201;
  const stub = http.createServer(async (req, res) => {
    requests++;
    assert.equal(req.method, 'POST');
    assert.equal(req.url, '/api/v1/registrations');
    assert.equal(req.headers.authorization, undefined);
    assert.equal(req.headers.cookie, undefined);
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    assert.deepEqual(JSON.parse(Buffer.concat(chunks)), { ...valid, age: 25 });
    res.writeHead(upstreamStatus, {
      'Content-Type': 'application/json',
      'Retry-After': '60',
      'X-Request-Id': '12345678-1234-1234-1234-123456789012',
    });
    res.end(JSON.stringify(upstreamStatus === 201 ? { registration_id: 'P-ABC123' } : { code: 'test_rejection' }));
  });
  const backendUrl = await listen(stub, t);
  const origin = await listen(createApp({ backendUrl }), t);
  for (const status of [201, 409, 413, 422, 429, 503]) {
    upstreamStatus = status;
    const response = await fetch(`${origin}/api/v1/registrations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'not-forwarded', Cookie: 'not-forwarded' },
      body: JSON.stringify({ ...valid, age: 25 }),
    });
    assert.equal(response.status, status);
    assert.equal(response.headers.get('retry-after'), '60');
    assert.equal(response.headers.get('x-request-id'), '12345678-1234-1234-1234-123456789012');
    await response.text();
  }
  assert.equal(requests, 6);
  assert.equal((await fetch(`${origin}/api/v1/admin/registrations`)).status, 404);
  const page = await fetch(origin);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /synthetic details only/);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
});

test('proxy rejects oversized and cross-origin requests before forwarding', async (t) => {
  const origin = await listen(createApp(), t);
  const headers = { 'Content-Type': 'application/json' };
  assert.equal((await fetch(`${origin}/api/v1/registrations`, {
    method: 'POST', headers, body: 'x'.repeat(262_145),
  })).status, 413);
  assert.equal((await fetch(`${origin}/api/v1/registrations`, {
    method: 'POST', headers: { ...headers, Origin: 'https://other.example' }, body: '{}',
  })).status, 403);
});

test('an upstream timeout yields an explicit unknown outcome and is not retried', async (t) => {
  let requests = 0;
  const backendUrl = await listen(http.createServer(() => { requests++; }), t);
  const origin = await listen(createApp({ backendUrl, timeoutMs: 100 }), t);
  const response = await fetch(`${origin}/api/v1/registrations`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(valid),
  });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).code, 'registration_outcome_unknown');
  assert.equal(requests, 1);
});
