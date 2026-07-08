// Runs each test sentence through the Chrome extension's analyzer (lib/analyzer.js),
// exactly as the extension popup would: script detection -> DeepL (if key) -> Claude
// streaming tool call -> local Korean romanization. Writes extension-results.json.
//
// Prereqs: ANTHROPIC_API_KEY (or CLAUDE_API_KEY) in env; DEEPL_API_KEY optional.
// Usage:   node run-extension.mjs
import { fetch } from 'undici';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

// The extension code calls the browser-global fetch; undici's is spec-compliant
// (Response.body is a web ReadableStream, which the SSE reader in analyzer.js needs).
globalThis.fetch = fetch;

const here = dirname(fileURLToPath(import.meta.url));
const EXT_DIR = process.env.EXTENSION_DIR
  ?? 'C:/Users/marvi/OneDrive/Desktop/language-extension/extension';

const { analyzeText } = await import(pathToFileURL(join(EXT_DIR, 'lib', 'analyzer.js')).href);

const apiKey = process.env.ANTHROPIC_API_KEY ?? process.env.CLAUDE_API_KEY;
if (!apiKey) {
  console.error('Set ANTHROPIC_API_KEY (or CLAUDE_API_KEY) in the environment.');
  process.exit(1);
}
const deeplKey = process.env.DEEPL_API_KEY ?? null;
console.log(`DeepL: ${deeplKey ? 'enabled' : 'disabled (Claude will translate)'}`);

const sentences = JSON.parse(readFileSync(join(here, 'sentences.json'), 'utf8'));
const sleep = ms => new Promise(r => setTimeout(r, ms));

const results = [];
for (const s of sentences) {
  const started = Date.now();
  // The analyzer's SSE parser splits on '\n' per read() chunk; under Node/undici
  // chunk boundaries can land mid-line, which surfaces as a JsonError. Retry —
  // in Chrome this happens rarely, here a couple of times per 10 runs.
  let lastErr = null, done = false;
  for (let attempt = 1; attempt <= 3 && !done; attempt++) {
    try {
      const data = await analyzeText(s.text, apiKey, deeplKey);
      results.push({ ...s, ok: true, ms: Date.now() - started, attempts: attempt, response: data });
      console.log(`ok   ${s.id} (${data.tokens?.length ?? 0} tokens, attempt ${attempt}, ${Date.now() - started}ms)`);
      done = true;
    } catch (err) {
      lastErr = err;
      console.log(`retry ${s.id} attempt ${attempt}: ${err.message}`);
      await sleep(1000);
    }
  }
  if (!done) {
    results.push({ ...s, ok: false, ms: Date.now() - started, error: String(lastErr?.message ?? lastErr) });
    console.log(`FAIL ${s.id}: ${lastErr?.message}`);
  }
  await sleep(1000);
}

writeFileSync(join(here, 'extension-results.json'), JSON.stringify(results, null, 2), 'utf8');
console.log(`wrote extension-results.json (${results.filter(r => r.ok).length}/${results.length} ok)`);
