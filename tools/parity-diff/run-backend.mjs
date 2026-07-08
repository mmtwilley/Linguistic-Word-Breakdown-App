// Runs each test sentence through the local Spring Boot /api/analyze endpoint
// and writes the raw responses to backend-results.json.
//
// Prereqs: backend running on :8080 (with Postgres/Redis containers up).
// Usage:   node run-backend.mjs
import { fetch } from 'undici';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BACKEND_URL ?? 'http://localhost:8080';
const EMAIL = 'difftest@example.com';
const PASSWORD = 'password123';

const sentences = JSON.parse(readFileSync(join(here, 'sentences.json'), 'utf8'));

async function post(path, body, token) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* keep raw */ }
  return { status: res.status, json, raw: text };
}

async function login() {
  const reg = await post('/api/auth/register', { email: EMAIL, password: PASSWORD });
  if (reg.status !== 200 && reg.status !== 201 && reg.status !== 409) {
    console.warn(`register returned ${reg.status}: ${reg.raw}`);
  }
  const login = await post('/api/auth/login', { email: EMAIL, password: PASSWORD });
  if (!login.json?.accessToken) throw new Error(`login failed (${login.status}): ${login.raw}`);
  return login.json.accessToken;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

const token = await login();
console.log('logged in');

const results = [];
for (const s of sentences) {
  const started = Date.now();
  try {
    const res = await post('/api/analyze', { text: s.text }, token);
    if (res.status !== 200) throw new Error(`HTTP ${res.status}: ${res.raw}`);
    results.push({ ...s, ok: true, ms: Date.now() - started, response: res.json });
    console.log(`ok   ${s.id} (${res.json.words?.length ?? 0} words, ${Date.now() - started}ms)`);
  } catch (err) {
    results.push({ ...s, ok: false, ms: Date.now() - started, error: String(err.message ?? err) });
    console.log(`FAIL ${s.id}: ${err.message}`);
  }
  await sleep(1500); // stay well under the 20 rpm limit and be gentle to Krdict/Jisho
}

writeFileSync(join(here, 'backend-results.json'), JSON.stringify(results, null, 2), 'utf8');
console.log(`wrote backend-results.json (${results.filter(r => r.ok).length}/${results.length} ok)`);
