// Diffs extension-results.json (Claude-direct word cards) against
// backend-results.json (tiered-pipeline word cards) and writes parity-report.md.
//
// Card shape, normalized for comparison:
//   extension token: { word, lemma, pos, meaning, romanization? }
//   backend word:    { surface, lemma, pos, gloss, romanization?, ipa? }
//
// Usage: node diff.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

const extRuns = JSON.parse(readFileSync(join(here, 'extension-results.json'), 'utf8'));
const beRuns  = JSON.parse(readFileSync(join(here, 'backend-results.json'), 'utf8'));
const byIdExt = new Map(extRuns.map(r => [r.id, r]));
const byIdBe  = new Map(beRuns.map(r => [r.id, r]));

// Normalize POS labels from either source into one vocabulary. The backend has
// been observed emitting Korean POS names (명사) and long English names.
const POS_MAP = new Map(Object.entries({
  'noun': 'noun', '명사': 'noun', '대명사': 'pron', 'pronoun': 'pron', 'pron': 'pron',
  'verb': 'verb', '동사': 'verb', '형용사': 'adj', 'adjective': 'adj', 'adj': 'adj',
  'adverb': 'adv', '부사': 'adv', 'adv': 'adv',
  'particle': 'particle', '조사': 'particle', 'postposition': 'particle',
  'preposition': 'prep', 'prep': 'prep', 'conjunction': 'conj', 'conj': 'conj',
  'determiner': 'det', 'det': 'det', 'article': 'det',
  'numeral': 'num', 'num': 'num', 'number': 'num', '수사': 'num',
  'punctuation': 'punct', 'punct': 'punct', 'auxiliary': 'verb', 'aux': 'verb',
  'interjection': 'other', '감탄사': 'other', 'other': 'other',
}));
const normPos = p => {
  if (!p) return null;
  const k = String(p).trim().toLowerCase();
  return POS_MAP.get(k) ?? POS_MAP.get(k.split(/[ /(]/)[0]) ?? `?${p}`;
};

const normRoman = r => r ? String(r).toLowerCase().replace(/[\s\-'’.]/g, '') : null;
const normGloss = g => g ? String(g).toLowerCase().replace(/^to be |^to |[.,;!]/g, '').trim() : null;

function normalizeExt(run) {
  return (run.response?.tokens ?? []).map(t => ({
    surface: t.word, lemma: t.lemma ?? null, pos: normPos(t.pos), posRaw: t.pos ?? null,
    gloss: t.meaning ?? null, romanization: t.romanization ?? null,
    extras: { particles: t.particles, endings: t.endings },
  }));
}
function normalizeBe(run) {
  return (run.response?.words ?? []).map(w => ({
    surface: w.surface, lemma: w.lemma ?? null, pos: normPos(w.pos), posRaw: w.pos ?? null,
    gloss: w.gloss ?? null, romanization: w.romanization ?? null, extras: {},
  }));
}

// LCS alignment on surface forms so segmentation differences show up as
// one-sided rows instead of shifting every subsequent comparison.
function align(a, b) {
  const n = a.length, m = b.length;
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      dp[i][j] = a[i].surface === b[j].surface
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const rows = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (a[i].surface === b[j].surface) rows.push({ ext: a[i++], be: b[j++] });
    else if (dp[i + 1][j] >= dp[i][j + 1]) rows.push({ ext: a[i++], be: null });
    else rows.push({ ext: null, be: b[j++] });
  }
  while (i < n) rows.push({ ext: a[i++], be: null });
  while (j < m) rows.push({ ext: null, be: b[j++] });
  return rows;
}

const esc = s => s == null ? '—' : String(s).replace(/\|/g, '\\|');
const totals = {
  sentences: 0, extFail: 0, beFail: 0,
  aligned: 0, extOnly: 0, beOnly: 0,
  lemmaDiff: 0, posDiff: 0, glossDiff: 0, romanDiff: 0,
  inputCoverageGapsBe: 0, orderViolationsBe: 0,
};
let md = `# Extension vs Backend Word-Card Parity Report\n\nGenerated ${new Date().toISOString()}\n\n`;

for (const s of JSON.parse(readFileSync(join(here, 'sentences.json'), 'utf8'))) {
  const ext = byIdExt.get(s.id), be = byIdBe.get(s.id);
  totals.sentences++;
  md += `## ${s.id}: ${s.text}\n\n`;

  if (!ext?.ok) { totals.extFail++; md += `**Extension FAILED**: ${ext?.error ?? 'no result'}\n\n`; }
  if (!be?.ok)  { totals.beFail++;  md += `**Backend FAILED**: ${be?.error ?? 'no result'}\n\n`; }
  if (!ext?.ok || !be?.ok) continue;

  md += `- Translation (extension): ${ext.response.translation}\n`;
  md += `- Translation (backend):   ${be.response.translation}\n`;
  md += `- Language (backend): ${be.response.language}\n`;

  const a = normalizeExt(ext), b = normalizeBe(be);
  md += `- Cards: extension ${a.length}, backend ${b.length}\n`;

  // Does the backend's card sequence follow input order? Compare each surface's
  // first index in the input text for monotonicity.
  let lastIdx = -1, orderViol = 0;
  for (const w of b) {
    const idx = s.text.indexOf(w.surface);
    if (idx !== -1) { if (idx < lastIdx) orderViol++; lastIdx = idx; }
  }
  if (orderViol) { totals.orderViolationsBe += orderViol; md += `- **Backend cards out of input order** (${orderViol} violations)\n`; }

  // Input coverage: what fraction of non-space input chars appear in some backend surface?
  const covered = new Set();
  for (const w of b) { let from = 0, at; while ((at = s.text.indexOf(w.surface, from)) !== -1) { for (let k = at; k < at + w.surface.length; k++) covered.add(k); from = at + 1; } }
  const inputChars = [...s.text].filter(c => !/\s|[、。，,.!?]/.test(c)).length;
  let coveredCount = 0;
  for (let k = 0; k < s.text.length; k++) if (covered.has(k) && !/\s|[、。，,.!?]/.test(s.text[k])) coveredCount++;
  if (coveredCount < inputChars) {
    totals.inputCoverageGapsBe++;
    md += `- **Backend dropped input**: covers ${coveredCount}/${inputChars} non-punct chars\n`;
  }

  md += `\n| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |\n`;
  md += `|---|---|---|---|---|---|---|\n`;
  for (const { ext: e, be: w } of align(a, b)) {
    const flags = [];
    if (e && w) {
      totals.aligned++;
      if (e.lemma !== w.lemma) { flags.push('LEMMA'); totals.lemmaDiff++; }
      if (e.pos !== w.pos) { flags.push('POS'); totals.posDiff++; }
      if (normGloss(e.gloss) !== normGloss(w.gloss)) { flags.push('GLOSS'); totals.glossDiff++; }
      if (e.romanization && w.romanization && normRoman(e.romanization) !== normRoman(w.romanization)) { flags.push('ROMAN'); totals.romanDiff++; }
    } else if (e) { flags.push('EXT-ONLY'); totals.extOnly++; }
    else { flags.push('BE-ONLY'); totals.beOnly++; }
    md += `| ${esc(e?.surface)} | ${esc(w?.surface)} | ${esc(e?.lemma)} / ${esc(w?.lemma)} | ${esc(e?.posRaw)} / ${esc(w?.posRaw)} | ${esc(e?.gloss)} / ${esc(w?.gloss)} | ${esc(e?.romanization)} / ${esc(w?.romanization)} | ${flags.join(' ') || 'match'} |\n`;
  }
  md += `\n`;
}

md = md.replace('\n\n', `\n\n## Summary\n\n` +
  `- Sentences: ${totals.sentences} (extension failures: ${totals.extFail}, backend failures: ${totals.beFail})\n` +
  `- Aligned cards (same surface): ${totals.aligned}\n` +
  `- Segmentation: extension-only cards ${totals.extOnly}, backend-only cards ${totals.beOnly}\n` +
  `- Field mismatches on aligned cards — lemma: ${totals.lemmaDiff}, pos: ${totals.posDiff}, gloss: ${totals.glossDiff}, romanization: ${totals.romanDiff}\n` +
  `- Backend sentences with dropped input: ${totals.inputCoverageGapsBe}; out-of-order card violations: ${totals.orderViolationsBe}\n\n`);

writeFileSync(join(here, 'parity-report.md'), md, 'utf8');
console.log('wrote parity-report.md');
console.log(JSON.stringify(totals, null, 2));
