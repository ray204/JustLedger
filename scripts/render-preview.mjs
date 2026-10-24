// Render the committed HTML to README images. No payment data or network service is used.
import { createRequire } from 'node:module';
import { readFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(path.join(root, '.local/preview/package.json'));
const { chromium } = require('playwright');
const html = await readFile(path.join(root, 'design/preview.html'));
const server = createServer((req, res) => {
  if (!new URL(req.url, 'http://localhost').pathname.match(/^\/(preview.html)?$/)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(html);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const executablePath = process.env.CHROME_PATH || ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium'].find(existsSync);
let browser;
try {
  browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const page = await browser.newPage({ viewport: { width: 1360, height: 1060 }, deviceScaleFactor: 1.5, reducedMotion: 'reduce' });
  const failures=[]; page.on('pageerror', e => failures.push(e.message));
  await mkdir(path.join(root, 'docs/images'), { recursive: true });
  for (const [mode, file] of [['main', 'overview.png'], ['recognition', 'recognition.png']]) {
    await page.goto(`http://127.0.0.1:${server.address().port}/preview.html?gallery=${mode}`);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(root, 'docs/images', file), fullPage: true });
    console.log(`Rendered docs/images/${file}`);
  }
  if (failures.length) throw new Error(failures.join('\n'));
} finally { if (browser) await browser.close(); await new Promise(resolve=>server.close(resolve)); }
