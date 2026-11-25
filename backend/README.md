# Usage API

Spring Boot backend for the mobile usage publisher. The current implementation covers the local-development core (B1–B4): health/readiness, admin registration import/status, device registration and nonce authentication, JWKS, and idempotent usage snapshot ingest/status. It also provides local-only public self-registration for synthetic data. It intentionally does not implement B5–B6 admin operations or the later model/recommendation integration.

## Local development

Requirements: Java 21, Maven 3.9+, and Docker Compose.

Set local-only credentials in PowerShell, then start the PostgreSQL and API containers:

```powershell
$env:DB_PASSWORD = [guid]::NewGuid().ToString() + [guid]::NewGuid().ToString()
$env:ADMIN_USERNAME = "study-admin"
$env:ADMIN_PASSWORD = [guid]::NewGuid().ToString() + [guid]::NewGuid().ToString()
$env:LOCAL_KEYSTORE_PASSWORD = [guid]::NewGuid().ToString() + [guid]::NewGuid().ToString()
docker compose -f ..\compose.yaml up --build postgres api
```

Run this command from the `backend` directory. When reusing existing volumes,
use their existing database and keystore passwords instead of generating new ones.

The API listens on `http://localhost:8080`. PostgreSQL migrations run automatically. The local profile creates and persists an EC P-256 signing key, AES-256 wrapping key, and contact-index HMAC key in the ignored `local_keys` volume. Preserve that volume with the database volume; losing it invalidates issued tokens, makes existing encrypted fields unreadable, and prevents further self-registration until the original index key is restored.

To run the application directly without Docker, create an empty PostgreSQL database and user, then set the credentials and connection URL for that database. For example:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/usage"
$env:DB_USERNAME = "usage_api"
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

Set `DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `LOCAL_KEYSTORE_PASSWORD` in the same shell before starting. The local database user must be allowed to create the `registry` schema and run Flyway migrations. Bind the service to localhost (`$env:SERVER_ADDRESS = "127.0.0.1"`) when testing on a shared machine.

The local API documentation is served at `/v3/api-docs/app`, `/v3/api-docs/admin`, and `/swagger-ui/index.html`. The admin docs and ungrouped combined document require the configured local admin Basic credentials. Docs endpoints are disabled in the `prod` profile.

For frontend generation and integration, see the [frontend API specification](./frontend-api-spec.md), [registration UI specification](./registration-ui-spec.md), and linked OpenAPI contracts.

## Implemented endpoints

| Method and path | Access |
|---|---|
| `GET /api/v1/health` | Public readiness (database and local key store) |
| `POST /api/v1/registrations` | Public self-registration, local profile only |
| `POST /api/v1/admin/registrations` | HTTP Basic local administrator |
| `PATCH /api/v1/admin/registrations/{registration_id}` | HTTP Basic local administrator |
| `POST /api/v1/devices/register` | Public; a `not_registered` or `activated` registration is required. The first device moves the registration to `activated`. |
| `POST /api/v1/auth/challenge` | Public; rate-limited, active device required |
| `POST /api/v1/auth/token` | Public; one-time signed nonce |
| `GET /.well-known/jwks.json` | Public ES256 public key set |
| `POST /api/v1/usage/snapshots` | Bearer JWT, `usage:write` |
| `GET /api/v1/usage/snapshots/{snapshot_id}` | Bearer JWT; participant-owned snapshots only |

Admin registration import encrypts age and gender before persistence. Usage ingestion validates the contract and event-derived totals, checks ownership and registration/device status, stores the encrypted canonical payload and queryable totals transactionally, and distinguishes same-body retries from conflicting snapshot IDs and duplicate scheduled days. Request bodies over 256 KiB are rejected. API errors contain only a stable code, generic message, and request ID.

Local self-registration accepts `user_name`, `mobile_number` (international E.164 format), `email`, `age`, `gender`, `indoor_activity`, `outdoor_activity`, `scheme_months` (3, 6, or 12; must reference an active row in `registry.schemes`, which is seeded by migration `V3` and currently free), and `profession` (`student`, `working_professional`, or `homemaker`; stored unencrypted in `registry.enrollment_details.profession` by migration `V4` so read-only analysis can group usage by profession; earlier rows have no value). Every field is required and activities are free-text descriptions. It returns `201` with `{"registration_id":"P-..."}`. Details are envelope-encrypted; email and mobile uniqueness among self-registrations is enforced with keyed HMAC indexes. Admin imports have no contact details and cannot be checked for contact duplicates. Email is normalized to lowercase, and mobile must be supplied in canonical E.164 form. A duplicate email or mobile returns a generic `409`; retries after an unknown outcome cannot recover the original ID. Requests are limited to 10 per source IP per minute using the local in-memory limiter.

## Security and deployment boundary

This is a local-development implementation, not a production deployment:

- The `local` profile stores signing and wrapping keys in a password-protected local PKCS#12 file. This is not a KMS; local rate limits are in-memory and do not coordinate across instances.
- Public self-registration is available only in the local profile when `prod` is not active. It has no contact ownership verification, can disclose whether contact details are registered, and does not provide account recovery or idempotent enrollment. It must not be used for real participants or exposed to an untrusted network. Production enrollment requires a separately approved consent, verification, identity, abuse-prevention, recovery, retention, and deletion design.
- Admin access uses one locally configured HTTP Basic account, not the study staff OIDC/MFA provider.
- Compose uses local HTTP and does not configure TLS, managed PostgreSQL, database TLS, cloud storage encryption, backups, audit-log role grants, or operational monitoring.
- The production profile deliberately has no `KeyProvider` implementation. Startup fails rather than falling back to local keys. Implement and configure a cloud KMS provider, staff identity provider, TLS/database controls, and provider-specific key rotation before deployment.
- The append-only audit log has a database trigger, but production database roles and grants still need provisioning.
- The implementation does not yet include A3–A9 (reset/revoke, signing-key rotation, snapshot query/export, withdrawal deletion, audit-log retrieval), rate-limit distribution, or the model/FastAPI integration.

Do not use real participant data or expose the local service to an untrusted network.

## Verification

```powershell
mvn test
```

Unit tests cover canonical hashing and idempotency outcomes. A Spring MVC smoke test uses an in-memory test database for readiness, JWKS, OpenAPI availability, and admin-route authentication. It generates both OpenAPI groups under `target/openapi` and fails if they drift from the committed contracts. PostgreSQL-specific migrations and ingest transactions should additionally be exercised against PostgreSQL before a deployment; Docker is required for the local Compose stack.

Optional PostgreSQL integration tests exercise the real database and transactions. The REST Assured suite starts the application on a random loopback port and covers every implemented endpoint: self-registration, health, JWKS and JWT verification, admin import and status, device registration, challenge and token, and snapshot upload and status. It also checks encrypted storage, duplicate and concurrent enrollment, validation, authorization, idempotency, replay protection, rate limits, and the registration status lifecycle. The authentication integration test directly verifies challenge SQL and nonce consumption. Run them only against a dedicated disposable test database: Flyway creates the application schema, and append-only synthetic audit records remain after test cleanup.

```powershell
$env:TEST_POSTGRES_JDBC_URL = "jdbc:postgresql://localhost:5432/usage_test"
$env:TEST_POSTGRES_USERNAME = "usage_test"
$env:TEST_POSTGRES_PASSWORD = "<test database password>"
mvn "-Dtest=PostgresAuthenticationIntegrationTest,RestAssuredPostgresIntegrationTest" test
```

Without `TEST_POSTGRES_JDBC_URL`, these PostgreSQL tests are skipped. The database username and password must also be set. No running API or Docker is required; PostgreSQL must be reachable.
