# Usage Publisher registration

A responsive, accessible registration page served by Node.js, integrated with the
local self-registration endpoint in the sibling `backend` folder. No third-party
runtime dependencies or build step are needed. Requires Node.js 22 or newer.

## Run

Start the backend using its README instructions with the `local` profile enabled
and `prod` disabled. Its default address is `http://localhost:8080`.

In this folder, run:

```powershell
npm.cmd start
```

Open **http://127.0.0.1:3000**. On Windows, `npm.cmd` also works when PowerShell
execution policy blocks `npm.ps1`. For automatic server restarts, use
`npm.cmd run dev`.

Optional configuration (set in the same terminal before starting):

```powershell
$env:BACKEND_URL = "http://127.0.0.1:8080"
$env:PORT = "3000"
npm.cmd start
```

The server binds only to `127.0.0.1`. `BACKEND_URL` must be a loopback HTTP(S)
origin with no path or credentials. The browser sends
`POST /api/v1/registrations` to Node, which forwards only that endpoint to the
backend. This same-origin proxy needs no backend CORS changes or admin credentials.
It preserves HTTP status, `Retry-After`, and `X-Request-Id`.

## Run with Docker Compose

Start Docker Desktop (or the Docker Engine) and configure non-empty
`DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and
`LOCAL_KEYSTORE_PASSWORD` in the project root `.env`. The database password must match
the password used when an existing PostgreSQL volume was initialized; changing
the file does not reset that password. Preserve the existing keystore password
and key volume when reusing data.

From the project root:

```powershell
docker compose up --build -d
```

The frontend's `BACKEND_URL` defaults to `http://api:8080`. To change it, add
`BACKEND_URL=http://api:<port>` to the root `.env`, or set `BACKEND_URL` in the
shell before running Compose; shell values take precedence. The port must be
the API's listening port **inside** its container, not its published host port.
After changing the value, rerun the `up` command above to recreate the frontend.
The URL must be an HTTP(S) origin with no path or credentials, using `api` or a
loopback host; loopback refers to the frontend container itself, not the host
machine.

Open **http://127.0.0.1:3000**. Compose starts PostgreSQL, the API, and the
frontend. The API may take a little longer to become ready; check
`http://127.0.0.1:8080/api/v1/health` before submitting a registration. To stop
the stack while preserving the database and keys:

```powershell
docker compose down
```

In this mode, `CONTAINER_MODE=true` makes the frontend listen on container
interfaces and allows the Compose `api` hostname as an upstream. Its published
port is restricted to host loopback, but other containers on the Compose network
can reach it. Use only a trusted local Docker environment; the API's existing
Compose port is not restricted to host loopback. For native runs the original
loopback-only listener and upstream restriction remain in effect.

## Behavior and safety

- **Synthetic local test data only.** This is not production enrollment, consent,
  contact verification, device registration, or authentication.
- All seven backend fields are required. The form validates formats and lengths,
  sends exact snake_case property names and numeric age, and preserves input on
  failure. Gender and activities remain free text.
- Only HTTP 201 with a valid `P-[A-Z0-9]{6}` ID shows success. Copy/save the ID
  before closing or refreshing; there is no recovery endpoint.
- Duplicate contact, oversized request, validation, and rate-limit failures are
  displayed distinctly. The retry button observes `Retry-After`.
- Requests are never automatically retried. A server error, invalid success
  response, timeout, or network failure means the outcome is unknown. A manual
  repeat then requires confirmation: the original request may have succeeded.
- The proxy times out after 15 seconds; the browser after 20 seconds. Neither
  timeout can undo a registration already received by the backend.
- No personal details or IDs are stored in browser storage, analytics, or logs.
  Clicking Copy ID deliberately places the ID on the system clipboard.
- This standalone page does not alter the app's offline Phase 1 flow.

## Verify

```powershell
npm.cmd test
```

Tests use Node's built-in runner and a disposable loopback HTTP stub, not a real
participant database. They cover validation boundaries, response classification,
retry timing, exact proxy integration, and upstream failures. To verify against
the real backend, start it first and submit a unique synthetic contact through
the page; a successful registration displays its returned activation ID.
