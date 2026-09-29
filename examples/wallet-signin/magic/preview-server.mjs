import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = new URL('./', import.meta.url);
const host = '127.0.0.1';
const port = 4173;
const backend = 'http://127.0.0.1:8080';
const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/app.mjs', ['app.mjs', 'text/javascript; charset=utf-8']],
  ['/style.css', ['style.css', 'text/css; charset=utf-8']],
  ['/dist/magic-connector.js', ['dist/magic-connector.js', 'text/javascript; charset=utf-8']]
]);
const authPaths = new Set(['/api/v1/auth/wallet/nonce', '/api/v1/auth/wallet/verify']);
const publishable = value => typeof value === 'string' && /^pk_[A-Za-z0-9_-]{8,}$/.test(value) ? value : '';

export function createPreviewServer({ key = process.env.FLOWW_MAGIC_PUBLISHABLE_KEY ?? '' } = {}) {
  return createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  const path = req.url?.split('?')[0];
  if (req.method === 'GET' && path === '/magic-config.mjs') {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' });
    res.end(`export const publishableKey = ${JSON.stringify(publishable(key))};\n`);
    return;
  }
  if (req.method === 'GET' && assets.has(path)) {
    const [file, contentType] = assets.get(path);
    try {
      const body = await readFile(new URL(file, root));
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(body);
    } catch {
      res.writeHead(503); res.end('Run npm run build before preview.');
    }
    return;
  }
  if (req.method === 'POST' && authPaths.has(path)) {
    if (req.headers['content-type'] !== 'application/json') {
      res.writeHead(415); res.end(); return;
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 8192) { res.writeHead(413); res.end(); return; }
      chunks.push(chunk);
    }
    try {
      const upstream = await fetch(backend + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: Buffer.concat(chunks), signal: AbortSignal.timeout(10000)
      });
      const body = await upstream.arrayBuffer();
      res.writeHead(upstream.status, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(Buffer.from(body));
    } catch {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end('{"reasonCode":"BACKEND_UNAVAILABLE"}');
    }
    return;
  }
  res.writeHead(404); res.end();
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  createPreviewServer().listen(port, host, () => {
    process.stdout.write(`Magic preview: http://${host}:${port}/\n`);
  });
}
