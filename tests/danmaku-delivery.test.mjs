import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const result = await build({
  entryPoints: [fileURLToPath(new URL('../src/services/danmaku/danmakuService.ts', import.meta.url))],
  bundle: true, format: 'esm', write: false,
  plugins: [{ name: 'tauri-boundary', setup(builder) {
    builder.onResolve({ filter: /^@tauri-apps\/api\// }, args => ({ path: args.path, namespace: 'tauri-test' }));
    builder.onLoad({ filter: /.*/, namespace: 'tauri-test' }, () => ({ contents: `
      export const invoke = (...args) => globalThis.__danmakuTest.invoke(...args);
      export const convertFileSrc = path => 'asset://localhost/' + path;
      export const emitTo = (...args) => globalThis.__danmakuTest.events.push(args);
    ` }));
  } }],
});
globalThis.localStorage = { getItem: () => null };
const { danmakuService } = await import(`data:text/javascript,${encodeURIComponent(result.outputFiles[0].text)}`);
const attachment = { id: 'att-123456789abc', name: 'animated.gif', mime: 'image/gif', size: 43 };
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');

test('received image attachments resolve to GIF data, never wire JSON, even with octet-stream MIME', async () => {
  const originalFetch = globalThis.fetch, originalReader = globalThis.FileReader;
  const calls = [];
  globalThis.__danmakuTest = { events: [], invoke: async (...args) => { calls.push(args); return 'animated.gif'; } };
  globalThis.fetch = async url => {
    assert.equal(url, 'asset://localhost/animated.gif');
    return new Response(gif, { headers: { 'content-type': 'application/octet-stream' } });
  };
  globalThis.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then(bytes => { this.result = `data:${blob.type};base64,${Buffer.from(bytes).toString('base64')}`; this.onload(); }); }
  };
  try {
    await danmakuService.pushMessage('Player', { playerId: 'peer', type: 'file', attachment, content: JSON.stringify(attachment) });
    assert.deepEqual(calls[0], ['fetch_chat_attachment', { ownerPlayerId: 'peer', attachment }]);
    const [windowName, eventName, payload] = globalThis.__danmakuTest.events[0];
    assert.equal(windowName, 'danmaku');
    assert.equal(eventName, 'danmaku-msg');
    assert.equal(payload.kind, 'image');
    assert.equal(payload.image, `data:image/gif;base64,${gif.toString('base64')}`);
    assert.ok(!payload.text.includes(attachment.id));
  } finally { globalThis.fetch = originalFetch; globalThis.FileReader = originalReader; }
});

test('failed media retrieval produces a readable fallback without leaking attachment JSON', async () => {
  globalThis.__danmakuTest = { events: [], invoke: async () => { throw new Error('Peer disconnected'); } };
  await danmakuService.pushMessage('Player', { playerId: 'peer', type: 'file', content: JSON.stringify(attachment) });
  const payload = globalThis.__danmakuTest.events[0][2];
  assert.match(payload.text, /animated.gif/);
  assert.match(payload.detail, /预览暂不可用/);
  assert.ok(!JSON.stringify(payload).includes(attachment.id));
});

test('recall, leave, or mute during attachment fetch suppresses late notifications', async () => {
  let visible = true;
  globalThis.__danmakuTest = { events: [], invoke: async () => { visible = false; throw new Error('Left'); } };
  await danmakuService.pushMessage('Player', { playerId: 'peer', type: 'file', content: JSON.stringify(attachment) }, () => visible);
  assert.equal(globalThis.__danmakuTest.events.length, 0);
});
