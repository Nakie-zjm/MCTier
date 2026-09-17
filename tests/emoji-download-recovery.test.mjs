import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const bundled = await build({
  entryPoints: ['src/services/emoji/emojiLibrary.ts'], bundle: true, format: 'esm', write: false,
  plugins: [{ name: 'native-fixture', setup(b) {
    b.onResolve({ filter: /^(@tauri-apps\/api\/|\.\.\/chat\/imageData)/ }, args => ({ path: args.path, namespace: 'fixture' }));
    b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path.endsWith('/core')
      ? 'export const invoke=(...a)=>globalThis.emojiFixture.invoke(...a); export const convertFileSrc=p=>`asset:${p}`;'
      : args.path.endsWith('/event')
        ? 'export const listen=(name,cb)=>globalThis.emojiFixture.listen(name,cb);'
        : 'export const fileToChatImageDataUrl=()=>null;' }));
  } }],
});

test('failed downloads retry automatically with capped backoff, one shared job and detachable progress', async () => {
  const originalTimeout = globalThis.setTimeout;
  const delays = [], progress = [], closedProgress = [];
  let emit, calls = 0, stopCount = 0, listenerCount = 0;
  globalThis.setTimeout = (fn, ms) => { delays.push(ms); queueMicrotask(fn); return 1; };
  globalThis.emojiFixture = {
    listen: async (_name, cb) => { listenerCount++; emit = cb; return () => stopCount++; },
    invoke: async () => {
      calls++;
      emit({ payload: { downloaded: 4, total: 5 } });
      if (calls <= 8) throw new Error('temporarily offline');
      return Array.from({ length: 5 }, (_, i) => ({ id: `builtin-${i}`, name: String(i), path: `/cache/${i}.gif` }));
    },
  };
  try {
    const { syncBuiltinEmojiItems } = await import(`data:text/javascript,${encodeURIComponent(bundled.outputFiles[0].text)}`);
    const controller = new AbortController();
    const first = syncBuiltinEmojiItems(false, p => closedProgress.push(p), controller.signal);
    controller.abort();
    const second = syncBuiltinEmojiItems(true, p => progress.push(p));
    const [a, b] = await Promise.all([first, second]);
    assert.strictEqual(a, b);
    assert.equal(calls, 9);
    assert.equal(listenerCount, 1);
    assert.equal(stopCount, 1);
    assert.deepEqual(delays, [2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000]);
    assert.equal(closedProgress.length, 1);
    assert.deepEqual(progress.at(-1), { downloaded: 5, total: 5 });
    assert.ok(progress.some(p => p.downloaded === 4 && p.retryAfterSeconds === 60));
    assert.equal(a[0].dataUrl, 'asset:/cache/0.gif');
    await syncBuiltinEmojiItems();
    assert.equal(calls, 9);
  } finally {
    globalThis.setTimeout = originalTimeout;
    delete globalThis.emojiFixture;
  }
});
