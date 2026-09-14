import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const { chromium } = await import(pathToFileURL(path.join(process.argv[2], 'index.mjs')));
const compiled = await build({
  stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client';
    import {useHoldVoice} from './src/hooks/useHoldVoice';
    window.sent=[]; window.failed=0;
    function App(){const [text,setText]=React.useState(''); const [context,setContext]=React.useState('lobby');
      const voice=useHoldVoice(!text,async(blob,duration)=>window.sent.push({size:blob.size,mime:blob.type,duration,context}),()=>window.failed++,context);
      return <><textarea aria-label="Message" placeholder="Hold to record voice" value={text} onChange={e=>setText(e.target.value)} {...voice.handlers}/>
      <p role="status">{voice.seconds===null?'Idle':(voice.cancelling?'Cancel':'Recording')+' '+voice.seconds}</p>
      <button onClick={()=>setContext('private')}>Private</button><button onClick={voice.cancel}>Cancel</button></>;
    } createRoot(document.getElementById('root')).render(<App/>);`, resolveDir: root, loader: 'jsx' },
  bundle: true, format: 'iife', write: false,
});
const server = createServer((req, res) => {
  res.setHeader('Content-Type', req.url === '/app.js' ? 'text/javascript' : 'text/html');
  res.end(req.url === '/app.js' ? compiled.outputFiles[0].text : '<div id="root"></div><script src="/app.js"></script>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.argv[3], args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const box = await page.getByRole('textbox').boundingBox();
  const hold = async () => { await page.mouse.move(box.x + 40, box.y + 20); await page.mouse.down(); await page.waitForFunction(() => document.querySelector('[role=status]').textContent.startsWith('Recording')); };
  await hold();
  await page.waitForTimeout(850);
  await page.mouse.up();
  await page.waitForFunction(() => window.sent.length === 1);
  assert.ok((await page.evaluate(() => window.sent[0].size)) > 0);
  await hold();
  await page.waitForTimeout(600);
  await page.keyboard.press('Escape');
  await page.mouse.up();
  await page.waitForTimeout(150);
  assert.equal(await page.evaluate(() => window.sent.length), 1);
  await hold();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: 'Private', exact: true }).evaluate(el => el.click());
  await page.mouse.up();
  await page.waitForTimeout(150);
  assert.equal(await page.evaluate(() => window.sent.length), 1);
  await hold();
  await page.waitForTimeout(650);
  await page.mouse.up();
  await page.waitForFunction(() => window.sent.length === 2);
  assert.equal(await page.evaluate(() => window.sent[1].context), 'private');
  await page.getByRole('textbox').fill('text');
  await page.mouse.move(box.x + 40, box.y + 20); await page.mouse.down();
  await page.waitForTimeout(600); await page.mouse.up();
  assert.equal(await page.getByRole('status').innerText(), 'Idle');
  assert.equal(await page.evaluate(() => window.failed), 0);
  assert.deepEqual(errors, []);
  console.log('PASS: real MediaRecorder voice payload, release to send, Escape cancellation, conversation switch cancellation, private context and nonempty input.');
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
