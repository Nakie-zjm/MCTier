import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';
import ts from 'typescript';

const entry = fileURLToPath(new URL('../src/services/chat/P2PChatService.ts', import.meta.url));
const ast = ts.createSourceFile(entry, fs.readFileSync(entry, 'utf8'), ts.ScriptTarget.Latest, true);
const stubs = new Map();
for (const node of ast.statements) if (ts.isImportDeclaration(node) && node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings)) {
  stubs.set(node.moduleSpecifier.text, node.importClause.namedBindings.elements.filter(e => !e.isTypeOnly).map(e => `export const ${(e.propertyName ?? e.name).text}=()=>null;`).join('\n'));
}
const bundle = await build({ entryPoints: [entry], bundle: true, format: 'esm', write: false, drop: ['console'], plugins: [{ name: 'chat-fixture', setup(b) {
  b.onResolve({ filter: /.*/ }, args => args.kind === 'entry-point' || /(?:trustBoundary|recallPolicy)$/.test(args.path) ? undefined : { path: args.path, namespace: 'fixture' });
  b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path === '@tauri-apps/api/core'
    ? 'export const invoke=(...args)=>globalThis.chatFixture.invoke(...args);'
    : stubs.get(args.path) || 'export const useAppStore={getState:()=>({})};' }));
} }] });
const { p2pChatService: chat } = await import(`data:text/javascript,${encodeURIComponent(bundle.outputFiles[0].text)}`);
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const message = id => ({ id, player_id: 'remote', player_name: 'Phone', message_type: 'text', content: 'hello', timestamp: 100 });

function setup() {
  let next = 1;
  const timers = new Map(), streams = [], received = [];
  const original = { window: globalThis.window, fetch: globalThis.fetch, clearTimeout: globalThis.clearTimeout };
  globalThis.window = { setTimeout: fn => { const id = next++; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id),
    setInterval: fn => { const id = next++; timers.set(id, fn); return id; }, clearInterval: id => timers.delete(id) };
  globalThis.clearTimeout = globalThis.window.clearTimeout;
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    streams.push(options.signal);
    options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
  });
  globalThis.chatFixture = { invoke: async () => [] };
  chat.reset(); chat.initialize(['10.126.126.2'], 'self', '10.126.126.1'); chat.onMessage(m => received.push(m));
  chat.setChatToken('a'.repeat(64)); chat.startPolling();
  return { timers, streams, received, close: async () => { chat.reset(); await flush(); Object.assign(globalThis, original); } };
}

test('token rotation retains history recovery and delivers once even when SSE is silent', async () => {
  const f = setup();
  try {
    chat.setChatToken('b'.repeat(64));
    assert.ok(chat.historyReconcileTimer);
    assert.ok(chat.streamWatchdog);
    assert.equal(f.streams[0].aborted, true);
    globalThis.chatFixture.invoke = async () => [message('message-one')];
    await chat.reconcileHistory(); await chat.reconcileHistory();
    assert.equal(f.received.length, 1);
  } finally { await f.close(); }
});

test('stalled stream is replaced without waiting for a network close event', async () => {
  const f = setup();
  try {
    chat.lastStreamActivity = Date.now() - 46000;
    f.timers.get(chat.streamWatchdog)();
    assert.equal(f.streams[0].aborted, true);
    assert.equal(f.streams.length, 2);
  } finally { await f.close(); }
});

test('old lobby history completion cannot enter the new lobby or clear its in-flight request', async () => {
  const f = setup();
  try {
    let finishOld, finishNew;
    globalThis.chatFixture.invoke = () => new Promise(resolve => { finishOld = resolve; });
    const old = chat.reconcileHistory();
    chat.reset(); chat.initialize([], 'new-self', '10.126.126.3'); chat.onMessage(m => f.received.push(m));
    chat.setChatToken('c'.repeat(64)); chat.startPolling();
    globalThis.chatFixture.invoke = () => new Promise(resolve => { finishNew = resolve; });
    const current = chat.reconcileHistory();
    finishOld([message('old-message')]); await old;
    assert.equal(f.received.length, 0);
    assert.equal(chat.historyReconcileInFlight, true);
    finishNew([]); await current;
  } finally { await f.close(); }
});

test('periodic full recovery does not let a fast peer clock permanently hide messages', async () => {
  const f = setup();
  try {
    chat.historySince = 999999;
    chat.lastFullHistory = Date.now() - 31000;
    globalThis.chatFixture.invoke = async (_command, args) => { assert.equal(args.since, null); return [message('slow-peer-message')]; };
    await chat.reconcileHistory();
    assert.equal(f.received.length, 1);
  } finally { await f.close(); }
});

test('full history across multiple peers does not replay more than 1000 older messages', async () => {
  const f = setup();
  try {
    globalThis.chatFixture.invoke = async () => Array.from({ length: 1500 }, (_, i) => message(`history-${i}`));
    for (let round = 0; round < 3; round++) {
      chat.lastFullHistory = 0;
      await chat.reconcileHistory();
    }
    assert.equal(f.received.length, 1500);
  } finally { await f.close(); }
});
