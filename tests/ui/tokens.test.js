// node --test tests/ui — a UI só usa cor via tokens.css e nunca usa timers.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const UI = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'ui');
const files = fs.readdirSync(UI, { recursive: true }).filter((f) => /\.(html|css|js)$/.test(f));

test('tokens.css define as dez cores do spec', () => {
  const css = fs.readFileSync(path.join(UI, 'tokens.css'), 'utf8');
  for (const t of ['--bg', '--surface', '--surface-2', '--stroke', '--text', '--text-2', '--accent', '--ok', '--warn', '--danger']) {
    assert.match(css, new RegExp(`${t}:\\s*\\d+ \\d+ \\d+;`), `falta ${t}`);
  }
});

test('nenhuma cor literal fora de tokens.css', () => {
  for (const f of files) {
    if (f.endsWith('tokens.css')) continue;
    const src = fs.readFileSync(path.join(UI, f), 'utf8');
    assert.doesNotMatch(src, /#[0-9a-fA-F]{3,8}\b|rgba?\(\s*\d/, `cor literal em ${f}`);
  }
});

test('zero timers na UI', () => {
  for (const f of files) {
    const src = fs.readFileSync(path.join(UI, f), 'utf8');
    assert.doesNotMatch(src, /\b(setTimeout|setInterval|requestAnimationFrame)\s*\(/, `timer em ${f}`);
  }
});
