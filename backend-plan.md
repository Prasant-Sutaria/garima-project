# Backend plan: authentication, usage ingest, and storage

This file covers the server side of the Android usage publisher. The app side is in [app-plan.md](./app-plan.md) (see Security feature), and the payload contract and registry fields are in [data-plan.md](./data-plan.md).

## 0. Decisions and conflicts

- **Database: PostgreSQL.** The registry and usage tables use the DDL in [data-plan.md](./data-plan.md) section 4 as the single source. This file adds the auth tables (section 5). Managed PostgreSQL is preferred, with storage encryption from the hosting provider.
- **Language and framework: Java with Spring Boot.** Chosen to match the Java Android app, so one team can own both. Use Spring Web for the REST endpoints, Spring Security for bearer token checks, a Postgres driver through Spring Data JPA or JDBC (decide in B1), and Testcontainers for integration tests. The JWT signing and verification use a KMS-backed key (section 3).
- **Model component:** separate Python/FastAPI component, with offline training and a scheduled nightly Python scoring job. The job writes results to PostgreSQL; Spring Boot serves saved results to the app. See [model-plan.md](./model-plan.md). The app does not call FastAPI.
- **API documentation: OpenAPI 3.1.** The Spring Boot API uses springdoc-openapi (section 8). The generated file is committed and checked in CI.
- **Hosting: cloud, low-tier paid subscription.** The app container runs on a small managed container service or VM. PostgreSQL is managed, and the signing and encryption keys use the provider's managed KMS. The provider is still open (see section 10). Build the service as a Docker image so it can move between providers.
- **Hosting region:** choose a region that fits the study's data-residency and ethics approval. This is an open question (see section 10).

## 1. Scope

In scope:
- Device registration, nonce challenge, and token issue (the flow in app-plan Security feature).
- Usage snapshot upload with idempotency.
- Public key publishing (JWKS) for app-side verification and key rotation.
- Admin endpoints for the study team: registration import, device reset, withdrawal, and export.
- Serving saved model results and recommendation feedback to the app, after the model integration and required approvals are in place.
- Encryption at rest and in transit.

Out of scope for the app-facing API: web dashboards, analysis tooling, and model training and scoring. The separate model component uses the admin export or suitably restricted data access; its input/output linkage and FastAPI interface remain to be designed.

## 2. Endpoint list

### 2.1 App-facing endpoints (called by the Android app)

| # | Method and path | Auth | Purpose |
|---|---|---|---|
| E1 | `POST /api/v1/devices/register` | None (registration ID is checked against the registry) | Register the device's public key for a registration ID. |
| E2 | `POST /api/v1/auth/challenge` | None (rate limited) | Issue a single-use nonce. Also returns `server_time` so the app can detect clock skew. |
| E3 | `POST /api/v1/auth/token` | Signed nonce | Verify the device signature and issue a 10-minute access token (ES256 JWT). |
| E4 | `POST /api/v1/usage/snapshots` | Bearer token | Store one usage snapshot. Duplicate `snapshot_id` returns 200 with `status: duplicate`. |
| E5 | `GET /api/v1/usage/snapshots/{snapshot_id}` | Bearer token | Return the stored status of one snapshot. Optional. Useful if the app reinstalls and needs to confirm an upload. |
| E6 | `GET /.well-known/jwks.json` | None | Publish the active and recently retired public signing keys (`kid`, `alg`, `x`, `y`). |
| E7 | `GET /api/v1/health` | None | Liveness and readiness (checks database and KMS reachability). |
| Proposed | `GET /api/v1/recommendations` | Bearer token | Return the authenticated participant's saved risk information and permitted suggestions. |
| Proposed | `POST /api/v1/recommendations/{id}/feedback` | Bearer token | Record the authenticated participant's recommendation engagement. |

These proposed endpoints require a participant-scoped authorization design, including suitable token audience and scopes, ownership and device-status checks, and enforcement of the monitoring-only arm. The current upload-only token claims do not yet define this access.

### 2.2 Admin endpoints (study team only, not called by the app)

| # | Method and path | Purpose |
|---|---|---|
| A1 | `POST /api/v1/admin/registrations` | Import study-issued registration IDs with encrypted age and gender. Accepts a batch. |
| A2 | `PATCH /api/v1/admin/registrations/{registration_id}` | Change status (`active`, `withdrawn`), as in data-plan section 4. |
| A3 | `POST /api/v1/admin/registrations/{registration_id}/reset-device` | Revoke the current device so the participant can register again after a data clear or new phone (app-plan J10). Snapshots are kept. |
| A4 | `POST /api/v1/admin/devices/{device_id}/revoke` | Revoke one device. Takes effect on the next upload, because the device status is checked on each request. |
| A5 | `POST /api/v1/admin/signing-keys/rotate` | Create a new signing key in KMS and publish it in JWKS. The old key stays for verification until its tokens expire. |
| A6 | `GET /api/v1/admin/snapshots` | Query snapshots by registration ID, date range, and trigger. Paginated. |
| A7 | `GET /api/v1/admin/export` | Export de-identified usage data for analysis (no registry fields, no `payload_enc`). |
| A8 | `DELETE /api/v1/admin/registrations/{registration_id}/data` | Delete all data for a withdrawn participant (usage, devices, and registry row), as required by the study's consent terms. Writes a receipt to the audit log. |
| A9 | `GET /api/v1/admin/audit-log` | Read the audit trail (append-only). |

Admin endpoints use a separate identity provider (for example OIDC with MFA for study staff) and a separate network path or IP allow-list. They are never reachable with an app token.

### 2.3 Endpoints that are not needed

- **Token refresh.** Not needed. The app signs a new challenge to get a new token, which is simpler and also proves the device still holds its key.
- **Password login or security-code login on the server.** Not allowed. The security code and answer stay on the device (see data-plan section 0).
- **User profile or account endpoints.** Not needed. The participant has no account on the server.

## 3. Authentication flow (server side)

### E1. Device registration
Request:
```json
{
  "registration_id": "P-7K3Q9X",
  "public_key": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." },
  "app_version": "1.0.0"
}
```
Rules:
- The registration must exist in `registrations` with status `active`. Unknown or withdrawn returns `403`.
- If an active device already exists for this registration with the same key, return `200` with the existing `device_id`. This makes a retried request safe.
- If an active device exists with a different key, return `409`. A new device needs A3 (reset-device) first.
- Only P-256 keys are accepted. The server rejects any other curve or algorithm.

Response `201`: `{ "device_id": "d_5f1c...", "status": "active" }`

### E2. Challenge
Request: `{ "registration_id": "P-7K3Q9X" }`

Response `200`:
```json
{ "nonce": "base64url-32-bytes", "expires_at": "2026-11-20T23:56:02Z", "server_time": "2026-11-20T23:55:02Z" }
```
Rules:
- The nonce is 32 random bytes from a cryptographic random source.
- It is stored only as a SHA-256 hash, with `expires_at` set 60 seconds after issue. A scheduled job deletes expired rows.
- Rate limit: 10 per registration per minute, and 100 per IP per minute.

### E3. Token
Request:
```json
{
  "registration_id": "P-7K3Q9X",
  "nonce": "base64url-32-bytes",
  "timestamp": "2026-11-20T23:55:03Z",
  "signature": "base64-DER-ecdsa-signature"
}
```

The signed string is built exactly as follows, with `\n` as the separator:
```
usage-auth-v1
<nonce>
<registration_id>
<timestamp>
```
The app must sign this exact string with SHA256withECDSA. The server accepts DER signatures, which is the format Android returns.

Server checks, in order:
1. Registration is `active`, and the device is `active`.
2. `timestamp` is within ±60 seconds of server time.
3. The nonce hash exists, belongs to this registration, is not used, and is not expired. Mark it used in one atomic statement: `UPDATE auth_challenges SET used_at = now() WHERE nonce_hash = $1 AND registration_id = $2 AND used_at IS NULL AND expires_at > now() RETURNING nonce_hash`. No row returned means reject. This prevents replay under concurrent requests.
4. The signature verifies with the device's stored public key.

If any check fails, return `401` with a generic message (no detail about which check failed). A failed signature check counts toward the rate limit.

Response `200`:
```json
{ "access_token": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTEifQ...", "token_type": "Bearer", "expires_in": 600 }
```

Token (JWT, ES256, signed by a KMS asymmetric key, never by a key in the app or the database):
- Header: `alg: ES256`, `kid`.
- Claims: `iss` (`usage-api`), `sub` (`registration_id`), `did` (`device_id`), `aud` (`usage-ingest`), `scope` (`usage:write`), `iat`, `exp` (`iat + 600`), `jti`.

## 4. Usage ingest (E4)

Request: the payload defined in [data-plan.md](./data-plan.md) section 3, with headers:
- `Authorization: Bearer <token>`
- `Idempotency-Key: <snapshot_id>`

Server checks, in order:
1. The token signature verifies against JWKS, and `exp`, `aud`, `iss`, and `scope` are valid. Otherwise `401`.
2. The device in `did` is still `active` (checked on each request, so revocation is immediate). Otherwise `403`.
3. `Idempotency-Key` equals `snapshot_id` in the body. Otherwise `400`.
4. The body passes the JSON schema (`schema_version`, field types, `trigger` enum, UUID format, `window.end > window.start`, `apps` sorted and non-empty per the field rules). Otherwise `422`.
5. `sub` (registration ID) equals `user_id` in the body. Otherwise `403`.
6. The registration is `active`. Otherwise `403`.

Write rules:
- Insert the row with `snapshot_id` as the primary key. A duplicate key is handled as a duplicate, which is the same behaviour data-plan section 4 describes.
- Store a SHA-256 hash of the canonical body (`body_sha256`). If the same `snapshot_id` arrives with a different hash, return `409`. The first copy is kept.
- A second scheduled snapshot for the same registration and date (different `snapshot_id`) hits the partial unique index from data-plan section 4. Return `409` with `scheduled_exists`. Manual snapshots are not limited.
- A duplicate with the same hash returns `200` with `status: duplicate`. A new snapshot returns `201` with `status: stored`.
- Body size limit: 256 KB. Larger requests return `413`.

Responses (as in data-plan section 3):
- `201` `{ "snapshot_id", "status": "stored", "received_at" }`
- `200` `{ "snapshot_id", "status": "duplicate", "received_at" }`
- `400`, `401`, `403`, `409`, `413`, `422`, `429`, and `5xx` (the app retries on `5xx` and timeouts).

## 5. Data model (PostgreSQL)

The registry and usage tables are defined in [data-plan.md](./data-plan.md) section 4 (`registry.registrations`, `usage_snapshots`, `app_usage`). The auth tables below are new. The `devices` table is created first, because `usage_snapshots` references it.

```sql
CREATE TABLE devices (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),   -- device_id
  registration_id  TEXT NOT NULL REFERENCES registry.registrations(registration_id),
  public_key_jwk   JSONB NOT NULL,                              -- P-256 public key, not secret
  status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
  app_version      TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at     TIMESTAMPTZ,
  revoked_at       TIMESTAMPTZ,
  CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);
-- At most one active device per registration. A new phone needs reset-device (A3) first.
CREATE UNIQUE INDEX uq_active_device_per_registration
  ON devices (registration_id) WHERE status = 'active';

-- Two columns added to usage_snapshots for the ingest checks (section 4).
ALTER TABLE usage_snapshots
  ADD COLUMN device_id    UUID REFERENCES devices(id),
  ADD COLUMN body_sha256  BYTEA NOT NULL;   -- hash of the canonical body, for the duplicate check

CREATE TABLE auth_challenges (
  nonce_hash       BYTEA PRIMARY KEY,                           -- SHA-256 of the nonce, never the nonce itself
  registration_id  TEXT NOT NULL REFERENCES registry.registrations(registration_id),
  issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at       TIMESTAMPTZ NOT NULL,                        -- issued_at + 60 seconds
  used_at          TIMESTAMPTZ                                  -- set once, by the atomic UPDATE in section 3
);
CREATE INDEX ix_auth_challenges_expires ON auth_challenges (expires_at);

CREATE TABLE signing_keys (
  kid           TEXT PRIMARY KEY,
  alg           TEXT NOT NULL DEFAULT 'ES256',
  public_jwk    JSONB NOT NULL,                                 -- published in JWKS
  kms_key_ref   TEXT NOT NULL,                                  -- KMS key version; the private key is never stored here
  status        TEXT NOT NULL CHECK (status IN ('active', 'retired')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  retire_after  TIMESTAMPTZ                                     -- kept in JWKS until its last token expires
);

CREATE TABLE audit_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor       TEXT NOT NULL,                                    -- admin user or 'system'
  action      TEXT NOT NULL,
  target_id   TEXT,
  request_id  TEXT NOT NULL,
  outcome     TEXT NOT NULL
);
-- Append-only: the API role has INSERT and SELECT only. A trigger rejects UPDATE and DELETE for every role.
```

Withdrawal (A8) locks the registration, marks it withdrawn, and deletes model inputs and results as well as `app_usage`, `usage_snapshots`, `auth_challenges`, `devices`, and finally `registry.registrations` in one transaction. Score writes use the same registration lock and active-status check, as specified in [data-plan.md](./data-plan.md), so a concurrent or delayed job cannot recreate results. The audit log keeps a receipt with the counts and the request ID, and no personal data.


## 6. Encryption

### 6.1 In transit
- TLS 1.2 or higher for all endpoints (TLS 1.3 preferred). HSTS on the API domain.
- Certificate pinning in the app, as described in app-plan Security feature.
- Database connections use TLS with certificate verification enabled.

### 6.2 At rest, layer 1: whole database
- Managed PostgreSQL with AES-256 encrypted storage. The WAL and backups are encrypted too.
- Keys are held by the cloud KMS, not on the database host.
- Backups, snapshots, and logs are encrypted with the same KMS hierarchy.

### 6.3 At rest, layer 2: field-level envelope encryption
Same design as data-plan section 4, which is the reference:
- **Columns encrypted by the application:** `registry.registrations.age_enc` and `gender_enc`, and `usage_snapshots.payload_enc` (the full received JSON, kept for audit and re-derivation).
- Each row has its own AES-256-GCM data key (DEK). The DEK is stored wrapped by a KMS master key in `dek_wrapped`, and `key_id` or `payload_key_id` records the master key version.
- Plain totals and `app_usage` stay queryable, as data-plan section 4 says. Package names are visible to the database, so the study must accept this (see data-plan open question 3).
- Age and gender cannot be used in equality queries. Age-group analysis is done after decryption, in the analysis environment.
- The master key lives in the KMS. It is never stored in the database, the repository, or environment files that are committed.
- Key rotation: rotate the KMS master key yearly, and re-wrap the DEKs. The key version on each row makes sure decryption always finds the right key.


### 6.4 Keys that are not stored as data
- **Signing private key (ES256):** a KMS asymmetric key. The server calls KMS `Sign`, so the private key never leaves KMS. Only the public key is in `signing_keys` and JWKS.
- **Nonces:** stored as hashes only.
- **Secrets for the database and KMS:** in the secret manager, injected at runtime. Never committed.

### 6.5 Data minimisation in logs
- Access logs hold the request ID, path, status, and latency. They never hold the body, the token, the nonce, the signature, the registration ID in full, age, or gender. Log the registration ID in a hashed or truncated form.
- IP addresses are used only for rate limiting, held in memory or a short-lived cache, and not stored with usage data (data-plan section 0).
- Error responses are generic. Stack traces are not returned to clients.

## 7. Security controls

- **Rate limits:** E2 and E3 at 10 per registration per minute. E4 at 60 per registration per minute. Admin endpoints separately.
- **Input validation:** JSON schema on every endpoint. Reject unknown fields on E1, E3, and E4.
- **Replay protection:** single-use nonce with a 60-second TTL, plus a ±60-second timestamp window.
- **Key-rotation handling:** JWKS lists active and recently retired keys. The app keeps a list of accepted `kid` values (app-plan Security feature).
- **Revocation:** device status is checked on each upload, so a revoked device is blocked at once, even with a valid token.
- **Least privilege:** separate database roles for the API (read and write on app tables), the analysis reader (read only, no `payload_enc` key access), and the audit writer (insert only).
- **Model integration:** restrict the scoring job to approved model inputs and result writes, and the API to the result access it needs. Define secure participant linkage, withdrawal/deletion coordination, and FastAPI service authentication before deployment.
- **Dependency and secret scanning** in CI. Pin dependency versions.
- **Security headers and CORS:** CORS is not needed for the app. Deny cross-origin requests on the API.

## 8. OpenAPI documentation (Java / Spring Boot)

Every endpoint in section 2 is documented in an OpenAPI 3.1 description.

### 8.1 Approach
- **Code-first with springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui`). Controllers and DTOs are annotated, and the description is generated at build time. The generated file is committed as `api/openapi/usage-api.yaml` and is the reviewed contract.
- **Two documents** through `GroupedOpenApi`: `app` (E1–E7 and the proposed recommendation endpoints) and `admin` (A1–A9). The admin document is never served on the public app path (see 8.4).
- Use the endpoint IDs from section 2 as `operationId` values (for example `registerDevice`, `issueChallenge`, `issueToken`, `uploadSnapshot`, `adminImportRegistrations`) so the app and the plan refer to the same names.
- Mark the proposed endpoints (`GET /api/v1/recommendations`, `POST /api/v1/recommendations/{id}/feedback`) with `x-status: proposed` until the participant-scoped authorization design exists.

### 8.2 What each operation must document
- Summary, description, and the rules from sections 3 and 4 that affect clients (for example the signed string for E3, and the 256 KB body limit for E4).
- Request and response schemas as DTOs with `@Schema` (type, format, required, enum, `maxLength`). Use the payload schema from [data-plan.md](./data-plan.md) section 3 for E4, with the `trigger` enum, UUID formats, and `schema_version`.
- Every status code the section lists, each with a schema: E1 `200/201/403/409`; E2 `200/429`; E3 `200/401/429`; E4 `200/201/400/401/403/409/413/422/429/5xx`; and the equivalents for the other endpoints. Use one shared generic error schema, so the documentation never suggests that error bodies carry details (section 6.5). Add `409` examples for `scheduled_exists` and the hash mismatch.
- Headers: `Authorization` (via the security scheme) and the required `Idempotency-Key` on E4, and `Retry-After` on `429`.
- At least one request and response example per operation, copied from this plan (E1, E2, E3 and E4 examples are already here). Examples use fake values only: no real registration IDs, tokens, or keys.
- E6 documents the JWKS response (`kid`, `alg`, `x`, `y`). E7 documents the readiness response without internal host names.

### 8.3 Security schemes
- `deviceBearer`: `type: http`, `scheme: bearer`, `bearerFormat: JWT` (ES256, `aud: usage-ingest`, scope `usage:write`). Applied to E4, E5, and the recommendation endpoints. E1, E2, E3, E6, and E7 declare `security: []` explicitly.
- `adminOidc`: `type: openIdConnect` for the admin document, with the study staff identity provider URL taken from configuration, not hard-coded.
- State in the description that E3 takes a signed nonce, not a password.

### 8.4 Serving and exposure
- Serve `/v3/api-docs/app` and the Swagger UI only in `dev` and `test` profiles. In production both are disabled (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`) and the committed YAML is the published artifact.
- The admin document and its UI are reachable only on the admin network path with admin authentication, like the admin endpoints (section 2.2).
- "Try it out" must not point at production. Set `servers` to the local and test URLs only.
- No example, description, or default value may contain a real secret, key, or participant data.

### 8.5 Build and CI
- Generate the files with the springdoc Maven or Gradle plugin during the integration test phase (the build tool is decided in B1).
- CI fails if the regenerated file differs from the committed one, so the contract cannot drift from the code.
- Lint with Spectral (the `oas` ruleset). Require operation IDs, summaries, and error responses.
- A contract test validates the real responses of E1–E4 against the generated schemas (for example with Atlassian `swagger-request-validator` or Schemathesis). The sample payload from data-plan section 3 must pass E4 without changes.
- Review any change to the committed YAML as an API change, and keep `info.version` equal to the API version (`v1`) with a changelog line for breaking changes.

## 9. Delivery order

1. **B1: Skeleton.** API service, configuration from the environment, health endpoint (E7), database connection with TLS, and KMS connection. Add springdoc-openapi, the `app` and `admin` groups, the security schemes, the shared error schema, and the CI drift check and Spectral lint (section 8).
2. **B2: Registry and admin import.** `registry.registrations` and `audit_log`, admin A1, A2, and the audit log.
3. **B3: Device registration and auth.** Tables `devices` and `auth_challenges`, endpoints E1, E2, and E3, and the signing key in KMS with JWKS (E6, `signing_keys`).
4. **B4: Upload.** Endpoint E4 with idempotency and body hashing. Field-level encryption for `payload_enc`. Endpoint E5 (optional).
5. **B5: Admin operations.** A3 to A9, including withdrawal and deletion (A8).
6. **B6: Hardening.** Rate limits, key rotation (A5), backup restore test, and the security checklist in section 7.
7. **Model integration (later):** add the Python/FastAPI component and scheduled scoring job, result storage, and the proposed Spring Boot recommendation endpoints after the model and ethics prerequisites in [model-plan.md](./model-plan.md).

Each endpoint is documented in the same step that implements it: an endpoint is not done until its OpenAPI operation, examples, and error responses are in the committed YAML.

## 10. Open questions

1. **Database.** Provisionally PostgreSQL, to be revisited once the data extracted from the device is well understood (field shapes, volumes, and how often they change). [data-plan.md](./data-plan.md) section 4 is the current schema source. Until then, keep the schema behind a repository layer so the storage choice can change without rewriting the endpoints.
2. **Hosting provider and region.** Choose a provider that offers a low-tier managed PostgreSQL, a managed KMS with asymmetric (ES256) keys, and a container runtime. The region is needed for the data-residency and ethics approval. Budget should also cover the database backup retention period.
3. **Retention.** How long are snapshots kept after the study ends, and what is the deletion process when a participant withdraws?
4. **Age bands.** Confirm whether an age band should replace the exact age (data-plan open question 5).
5. **Device loss.** Confirm that an admin reset (A3) is the only way to register a new phone, and who may perform it.
6. **Phone clock.** Confirm that a ±60-second window is acceptable for the timestamp check, or allow a larger skew (the app can use `server_time` from E2 to correct).
7. **Participant count and study length.** Expected number of participants and duration. This sizes the database, the rate limits, and the backup retention.
8. **Activation ID format.** The `P-` plus six characters format is provisional. The study must confirm it (app-plan J1).
9. **Payload sensitivity.** Whether package names are personal data under the consent terms, and whether they must be hashed before storage.
10. **Data residency and retention.** Region limits from the ethics approval, and how long snapshots are kept after the study ends.
11. **Consent alignment.** Confirm that storing age, gender, and usage data matches the consent form, including the age-band decision (item 4).
12. **Rate-limit values.** Limits for E2, E3, and E4, per registration and per source IP.
13. **Key rotation schedule.** How often to rotate the signing key (A5) and the KMS master key. Yearly is the current assumption.
14. **Backups.** Retention period and restore-test schedule.
15. **Logging and monitoring.** Tools to use, and confirmation that logs never contain the fields listed in section 6.
16. **Uptime and offline behaviour.** Expected availability, and the app's queue size and retry policy when the server is down.
17. **Build and framework details.** Java version, Maven or Gradle, migration tool (Flyway or Liquibase), and Spring Data JPA or JDBC (decided in B1). Also the springdoc version and the OpenAPI lint and contract-test tools (section 8.5).
18. **CI.** Whether the pipeline runs Testcontainers and the KMS emulator.
19. **KMS emulator for tests.** Which emulator to use, which depends on the provider (item 2).
20. **Attestation.** Whether to use Keystore attestation. On emulators the key is software-backed, so attestation results differ from real phones.
21. **Domain and TLS.** Domain name and certificate source for the production API.
22. **Local development.** Whether the Android emulator and Docker Desktop can run together on the development Windows machine.

## 11. Testing

- Unit tests for the signed-string builder, nonce hashing, token claims, and the idempotency rules (same hash returns duplicate, different hash returns 409).
- Integration tests against a PostgreSQL test container and a KMS emulator: register, challenge, token, upload, duplicate upload, second scheduled snapshot for the same date, revoked device, withdrawn registration, and replayed nonce.
- Contract test: a sample payload from [data-plan.md](./data-plan.md) must pass E4 without changes.
- Encryption check: confirm that `payload_enc`, `age_enc`, and `gender_enc` are not readable from a raw database dump.
- OpenAPI check: the regenerated YAML matches the committed file, passes the Spectral lint, and the real responses of E1–E4 validate against it (section 8.5). Check that production has the docs endpoints disabled.
