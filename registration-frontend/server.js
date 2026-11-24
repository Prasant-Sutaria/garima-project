import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/registration.js', ['registration.js', 'text/javascript; charset=utf-8']],
]);
const publicDirectory = new URL('./public/', import.meta.url);
const maxBodyBytes = 262_144;

export function createApp({ backendUrl = 'http://localhost:8080', timeoutMs = 15_000, containerMode = false } = {}) {
  const backend = new URL(backendUrl);
  const allowedHosts = containerMode ? ['localhost', '127.0.0.1', '[::1]', 'api'] : ['localhost', '127.0.0.1', '[::1]'];
  if (!['http:', 'https:'].includes(backend.protocol) ||
      !allowedHosts.includes(backend.hostname) ||
      backend.username || backend.password || backend.pathname !== '/' ||
      backend.search || backend.hash) {
    throw new Error(containerMode
      ? 'BACKEND_URL must be a loopback or api HTTP(S) origin without a path or credentials.'
      : 'BACKEND_URL must be a loopback HTTP(S) origin, such as http://localhost:8080.');
  }

  return http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy',
      "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");

    const sendError = (status, code, message) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ code, message }));
    };

    if (request.url === '/api/v1/registrations' && request.method === 'POST') {
      // Only browser requests from this origin may use the local proxy.
      if (request.headers['sec-fetch-site'] === 'cross-site' ||
          (request.headers.origin && request.headers.origin !== `http://${request.headers.host}`)) {
        sendError(403, 'origin_rejected', 'Use the registration page on this server.');
        return;
      }
      if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
        sendError(415, 'unsupported_media_type', 'Send JSON registration data.');
        return;
      }
      try {
        const chunks = [];
        let length = 0;
        for await (const chunk of request) {
          length += chunk.length;
          if (length > maxBodyBytes) {
            sendError(413, 'payload_too_large', 'The registration request is too large.');
            return;
          }
          chunks.push(chunk);
        }
        const upstream = await fetch(new URL('/api/v1/registrations', backend), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: Buffer.concat(chunks),
          signal: AbortSignal.timeout(timeoutMs),
          redirect: 'manual',
        });
        const body = await upstream.text();
        for (const header of ['content-type', 'retry-after', 'x-request-id']) {
          if (upstream.headers.has(header)) response.setHeader(header, upstream.headers.get(header));
        }
        response.writeHead(upstream.status);
        response.end(body);
      } catch (error) {
        // Never log submitted details or upstream response bodies.
        console.error('Registration proxy failed:', error.name);
        if (!response.destroyed) {
          sendError(502, 'registration_outcome_unknown',
            'The backend could not confirm the registration outcome.');
        }
      }
      return;
    }

    const asset = assets.get(request.url);
    if (!asset || !['GET', 'HEAD'].includes(request.method)) {
      sendError(404, 'not_found', 'Not found.');
      return;
    }
    try {
      const contents = await readFile(new URL(asset[0], publicDirectory));
      response.writeHead(200, { 'Content-Type': asset[1] });
      response.end(request.method === 'HEAD' ? undefined : contents);
    } catch (error) {
      console.error('Static asset unavailable:', error.code);
      sendError(500, 'asset_unavailable', 'The page could not be loaded.');
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('PORT must be an integer between 1 and 65535.');
  }
  const containerMode = process.env.CONTAINER_MODE === 'true';
  const server = createApp({ backendUrl: process.env.BACKEND_URL, containerMode });
  server.on('error', (error) => {
    console.error('Registration server failed:', error.message);
    process.exitCode = 1;
  });
  server.listen(port, containerMode ? '0.0.0.0' : '127.0.0.1', () => {
    console.log(`Local-test registration UI: http://127.0.0.1:${port}`);
  });
}
