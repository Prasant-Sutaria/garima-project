# Usage Publisher

Local development workspace containing a Spring Boot usage API, a registration
web page, and a separate Python model component. The model component is not
connected to the API yet; the Android application described in the plans is also
not part of this workspace.

## Start with Docker Compose

Start Docker Desktop (or Docker Engine with Compose), then set non-empty
`DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `LOCAL_KEYSTORE_PASSWORD`
in the root `.env`. Keep this file private. When reusing existing volumes, use
their existing database and keystore passwords.

From the project root:

```powershell
docker compose up --build -d
```

This starts PostgreSQL, the backend, the registration frontend, and a
synthetic-only model dashboard. Open **http://127.0.0.1:3000** for registration
and **http://127.0.0.1:8091** for the dashboard. The dashboard starts after its
one-shot generation job completes; its charts require internet access for
Chart.js. Set `SYNTHETIC_DASHBOARD_HOST_PORT` to change its host port.
Check backend readiness at
**http://127.0.0.1:8080/api/v1/health** before submitting a registration. The
backend can take longer to start than the frontend. Stop the services without
deleting their data:

```powershell
docker compose down
```

The Compose project name remains `backend` to reuse volumes from the previous
backend Compose setup. Keep both database and key volumes; losing the keys can
make existing encrypted data unreadable. The API and database ports are exposed
on host interfaces, so use this local stack only on a trusted network.

## Start with local scripts

### Requirements

- Java 21
- Maven 3.9 or newer
- Node.js 22 or newer (includes npm)
- `curl`
- Docker Desktop with Docker Compose, recommended for PostgreSQL

**Database startup is required.** Start Docker Desktop before running the
launcher; it starts the PostgreSQL container and waits until it is ready before
starting the backend. Alternatively, use an installed native PostgreSQL
service: create the `usage` database and `usage_api` user, and configure their
credentials as described in [backend/README.md](./backend/README.md). The
launcher starts the native service when available; on Windows this may require
running the terminal as Administrator.

### Windows

From the project root, run:

```powershell
.\start-all.bat
```

### macOS or Linux

From the project root, run:

```bash
./start-all.sh
```

The script starts PostgreSQL, starts the backend and waits for its health check,
then launches the registration frontend. When Docker is available, PostgreSQL
runs through Compose. On the first run, the script creates the root `.env` with
local credentials and secrets; keep this file private and do not commit it.

Open the registration page at **http://127.0.0.1:3000**. The API listens at
**http://127.0.0.1:8080** and its health check is
**http://127.0.0.1:8080/api/v1/health**. Database migrations run automatically.

Stop the frontend with Ctrl+C. The backend runs separately: on Windows, close
its console window; on macOS/Linux, use the PID command printed by the startup
script. Keep the `postgres_data` and `local_keys` Docker volumes between runs;
removing the key volume can make existing local encrypted data unreadable.

## Run components separately

- For backend configuration, direct startup, API docs, and tests, see
  [backend/README.md](./backend/README.md).
- For frontend configuration and tests, see
  [registration-frontend/README.md](./registration-frontend/README.md), including
  how to run it with Docker Compose.
- The model is an offline, synthetic-data pipeline with a separate optional
  health API. Follow [model/README.md](./model/README.md) for dashboard and
  model setup; it is not started by `start-all`.

## Local-development only

Use synthetic test data only. The registration page and local API are not
approved for real participants or production deployment. Do not expose these
services to an untrusted network; see the component READMEs for their specific
limitations.
