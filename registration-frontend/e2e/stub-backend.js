import http from 'node:http';
import { randomBytes } from 'node:crypto';

// Stands in for the Usage API in browser tests. Like the real backend, it rejects
// a second registration that shares a lowercased email or a mobile number.
const port = Number(process.env.STUB_PORT || 3101);
const registeredEmails = new Set();
const registeredMobiles = new Set();
let registrationCount = 0;

const send = (response, status, body) => {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
};

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    send(response, 200, { ok: true });
    return;
  }
  if (request.method === 'GET' && request.url === '/count') {
    send(response, 200, { registrations: registrationCount });
    return;
  }
  if (request.method !== 'POST' || request.url !== '/api/v1/registrations') {
    send(response, 404, { code: 'not_found', message: 'Not found.' });
    return;
  }

  const chunks = [];
  request.on('data', (chunk) => chunks.push(chunk));
  request.on('end', () => {
    const details = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!['student', 'working_professional', 'homemaker'].includes(details.profession)) {
      send(response, 422, { code: 'invalid_request', message: 'The request is invalid.' });
      return;
    }
    const email = details.email.trim().toLowerCase();
    const mobile = details.mobile_number.trim();
    if (registeredEmails.has(email) || registeredMobiles.has(mobile)) {
      send(response, 409, { code: 'registration_exists', message: 'Registration already exists.' });
      return;
    }
    registeredEmails.add(email);
    registeredMobiles.add(mobile);
    registrationCount += 1;
    send(response, 201, { registration_id: `P-${randomBytes(3).toString('hex').toUpperCase()}` });
  });
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Stub backend listening on http://127.0.0.1:${port}`);
});
