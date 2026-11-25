# Frontend integration specification: Usage API

This specification describes the implemented backend for the Android usage publisher and a future administrative UI. Use it when generating UI and integration code. The machine-readable contracts are [app OpenAPI](./api/openapi/usage-api-app.yaml) and [admin OpenAPI](./api/openapi/usage-api-admin.yaml); implementation and those contracts, rather than planned endpoints, are authoritative.

For the local self-registration screen and its UI states, see the [registration UI specification](./registration-ui-spec.md).

## Scope and environment

- Implemented: local-only synthetic self-registration, readiness, registration import and status update, device registration, challenge/token authentication, JWKS, and snapshot upload and status.
- Not implemented: recommendations or model results, participant or snapshot listing, payload retrieval, device reset/revocation by an administrator, data deletion/export, signing-key rotation, and staff OIDC/MFA. Do not generate UI that presents these as working APIs.
- The [app plan](../app-plan.md) keeps Phase 1 offline. Device registration and upload belong to Phase 2; local activation, lock, permissions, scheduling, and audit display do not require these APIs.
- Default local base URL is `http://localhost:8080`; configure it for the client environment. On Android, `localhost` refers to the device itself, not the development computer. Use a reachable development address and any platform-specific local HTTP settings.
- The local backend is **not production-ready**: it has local Basic admin credentials, local keys, in-memory rate limiting, and no configured TLS. Do not send real participant data or expose it to untrusted networks. Production requires TLS, a production key provider and staff authentication; see [README](./README.md).
- Backend CORS is disabled. A browser UI at a different origin cannot directly integrate without appropriate server-side CORS configuration or a same-origin development proxy. Do not embed admin credentials in a public frontend.
- JSON requests use `Content-Type: application/json`. JSON field names are snake_case. Unknown request properties are rejected. POST and PATCH bodies under `/api/v1/` are limited to 262,144 bytes; larger bodies return 413.
- Registration IDs match `P-[A-Z0-9]{6}`, for example `P-ABC123`. This is the app's activation ID and the snapshot's `user_id`. Snapshot and device IDs are UUIDs.

## Endpoint overview

| Method | Path | Access | Success |
|---|---|---|---|
| GET | `/api/v1/health` | Public | 200 readiness |
| POST | `/api/v1/registrations` | Public, local profile only | 201 registration ID |
| POST | `/api/v1/devices/register` | Public, active registration required | 201 new; 200 same key |
| POST | `/api/v1/auth/challenge` | Public, active registered device required | 200 nonce |
| POST | `/api/v1/auth/token` | Public, signed nonce required | 200 access token |
| GET | `/.well-known/jwks.json` | Public | 200 public signing keys |
| POST | `/api/v1/usage/snapshots` | Bearer token | 201 stored; 200 duplicate |
| GET | `/api/v1/usage/snapshots/{snapshot_id}` | Bearer token | 200 owned snapshot status |
| POST | `/api/v1/admin/registrations` | Admin Basic auth | 201 imported |
| PATCH | `/api/v1/admin/registrations/{registration_id}` | Admin Basic auth | 204, no body |

Local generated docs are at `/v3/api-docs/app` and `/v3/api-docs/admin`; the admin document requires Basic auth. The docs are disabled in the production profile. The generated `servers` URL is not a deployment address; configure the base URL separately.

## Local self-registration

`POST /api/v1/registrations` is for synthetic local testing only. It is disabled when the `prod` profile is active. It does not verify contact ownership and can reveal whether a contact has already been registered. Do not send real participant information. The existing study plan requires a separate approved enrollment and consent process before production use.

```json
{
  "user_name": "Example Person",
  "mobile_number": "+14155550123",
  "email": "person@example.test",
  "age": 25,
  "gender": "example",
  "indoor_activity": "Reading",
  "outdoor_activity": "Walking",
  "scheme_months": 6,
  "profession": "student"
}
```

All fields are required. Name is at most 120 characters, email at most 254, gender at most 64, and each free-text activity at most 2,000. Age is an integer from 0 to 120; `scheme_months` is 3, 6, or 12; `profession` is `student`, `working_professional`, or `homemaker`; mobile must use E.164 (`+` followed by 2-15 digits, starting with a nonzero digit). A successful response is `201` with `{"registration_id":"P-ABC123"}`; use that ID for the existing device registration flow. Email is compared case-insensitively and the canonical E.164 number is compared exactly. Duplicate email or mobile among self-registrations produces generic `409 registration_exists`; admin imports have no contact details to compare. Malformed input returns 422. Requests are rate-limited to 10 per source IP per minute, returning 429 and `Retry-After: 60`.

The backend encrypts submitted details and uses keyed contact fingerprints for uniqueness. There is no contact verification or recovery endpoint, and the request is not idempotent: if its outcome is unknown, retrying may return 409 without returning the original ID. Do not implement silent retries or display a duplicate result as successful registration.

## Participant authentication

Device registration is separate from the app's local activation and lock. The security code and secret answer must never be sent to the server.

1. A study administrator imports an active registration (or the local-only test enrollment creates one). For first upload, generate an EC P-256 key pair in Android Keystore. Keep the private key non-exportable and available to background work while the phone is locked.
2. Register its public JWK. Save the key reference and use the same key on subsequent attempts.
3. Request a challenge; sign and exchange it for a token as described below.
4. Send the token as `Authorization: Bearer <access_token>` for upload and status. Keep the token in memory, not in persistent storage or logs.
5. On an upload 401, obtain a fresh challenge and token and retry once with the unchanged snapshot and idempotency key. A failed or used nonce cannot be reused.

### Register device

`POST /api/v1/devices/register`

```json
{
  "registration_id": "P-ABC123",
  "public_key": {
    "kty": "EC",
    "crv": "P-256",
    "x": "<base64url-encoded 32-byte x coordinate>",
    "y": "<base64url-encoded 32-byte y coordinate>"
  },
  "app_version": "1.0"
}
```

`x` and `y` are unpadded base64url strings of exactly 43 characters. Substitute coordinates from the actual key, not the placeholders. `app_version` is nonblank and at most 32 characters.

```json
{"device_id":"4cd7d26c-d09a-4b19-98de-e72c041cc443","status":"active"}
```

201 creates a device; 200 returns the existing device for the same key. An unknown or inactive registration returns 403; a different active key returns 409 `device_exists`. Invalid key material can return 400 `invalid_key`, and invalid request format returns 422. There is no implemented self-service reset endpoint for a lost key or a replacement device.

### Challenge and token

`POST /api/v1/auth/challenge`

```json
{"registration_id":"P-ABC123"}
```

```json
{
  "nonce": "<43-character unpadded base64url nonce>",
  "expires_at": "2026-01-15T10:01:00Z",
  "server_time": "2026-01-15T10:00:00Z"
}
```

The nonce represents 32 random bytes, expires after 60 seconds, and is single-use. The response timestamps are ISO-8601 instants.

`POST /api/v1/auth/token`

Build the following exact UTF-8 message using LF (`\n`) separators and **no trailing newline**:

```text
usage-auth-v1
<nonce>
<registration_id>
<timestamp>
```

Use an ISO-8601 instant parseable by Java `Instant.parse`, for example the response's `server_time`; the timestamp must be within 60 seconds of the server's current time. Sign the bytes with the device private key using `SHA256withECDSA`. Encode the resulting **DER-encoded** ECDSA signature with standard Base64 (not base64url). Send the exact timestamp string used in the signature:

```json
{
  "registration_id": "P-ABC123",
  "nonce": "<nonce from challenge>",
  "timestamp": "2026-01-15T10:00:00Z",
  "signature": "<standard-base64 DER signature>"
}
```

```json
{"access_token":"<JWT>","token_type":"Bearer","expires_in":600}
```

The JWT is ES256-signed, expires after 600 seconds, and has issuer `usage-api`, audience `usage-ingest`, scope `usage:write`, registration ID in `sub`, device UUID in `did`, and `iat`, `exp`, and `jti` claims. The header `kid` identifies the signing key. Invalid/expired proofs return 401; an invalid signature consumes the nonce, so start again with a new challenge. Challenge and token requests each have separate rolling 60-second limits of 10 per registration and 100 per source IP.

### Server public keys

`GET /.well-known/jwks.json` returns `{"keys":[...]}` with public EC JWKs containing `kty`, `crv`, `kid`, `alg`, `use`, `x`, and `y`. These are **server token-verification keys**, not device keys. Follow the [app plan](../app-plan.md) for trusted key provisioning and token verification; do not treat keys downloaded from an untrusted endpoint as proof of that endpoint's identity. Production transport requires TLS and the planned pinning strategy.

## Snapshot upload and status

### Upload

`POST /api/v1/usage/snapshots`

Headers:

```text
Authorization: Bearer <access_token>
Content-Type: application/json
Idempotency-Key: 7eddf4b1-697e-41f1-964e-98aa5b7dfac2
```

Example request (synthetic data):

```json
{
  "schema_version": "1.0",
  "snapshot_id": "7eddf4b1-697e-41f1-964e-98aa5b7dfac2",
  "trigger": "scheduled",
  "date": "2026-01-15",
  "user_id": "P-ABC123",
  "app_version": "1.0",
  "timezone": "Asia/Kolkata",
  "utc_offset_minutes": 330,
  "window": {
    "start": "2026-01-15T00:00:00+05:30",
    "end": "2026-01-15T23:55:00+05:30"
  },
  "collected_at": "2026-01-15T23:55:00+05:30",
  "totals": {
    "screen_on_seconds": 3600,
    "unlock_count": 12,
    "notification_count": 8,
    "notification_source": "measured",
    "notification_count_synthetic": false,
    "active_app_count": 2
  },
  "apps": [
    {"package_name":"example.app.one","foreground_seconds":1800,"launch_count":5},
    {"package_name":"example.app.two","foreground_seconds":600,"launch_count":2}
  ],
  "quality": {"event_count":42,"open_sessions_closed":0,"unmatched_events":0}
}
```

Contract and validation:

- All top-level fields shown are required. `schema_version` must be `1.0`; `trigger` is `scheduled` or `manual`. `user_id` must equal the token's registration ID. `app_version` is nonblank, max 32 characters; `timezone` is a valid zone ID, max 64 characters.
- `date` is `YYYY-MM-DD`; window timestamps and `collected_at` require an explicit offset. Start must precede end. The start's local date and its date in `timezone` must equal `date`; `utc_offset_minutes` must match both the start offset and the zone's offset at start. The end offset must match the zone at end, including daylight-saving transitions. Offset minutes range from -840 to 840.
- Totals are nonnegative integers. `screen_on_seconds`, `notification_source`, `notification_count_synthetic`, and `active_app_count` are required; `unlock_count` and `notification_count` may be null or omitted. `notification_source` is `measured`, `synthetic`, or `unavailable`. Count must be present for `measured` and `synthetic`, absent or null for `unavailable`; the synthetic flag is true exactly when the source is `synthetic`.
- `apps` is required and contains at most 500 entries. `active_app_count` equals the list length. Each app has a unique nonblank `package_name` of at most 255 characters, nonnegative `foreground_seconds` and `launch_count`, and at least one of those values greater than zero. Sort entries by foreground seconds in descending order; equal values are allowed.
- `quality` is required; its `event_count`, `open_sessions_closed`, and `unmatched_events` are nonnegative integers. Do not invent unavailable usage measurements.
- `Idempotency-Key` must exactly equal the canonical lowercase UUID string in `snapshot_id`. Persist the snapshot ID and payload before sending and reuse both unchanged for retries, including after timeouts. Never create a new ID merely because the response was lost.

201 response for a new snapshot:

```json
{"snapshot_id":"7eddf4b1-697e-41f1-964e-98aa5b7dfac2","status":"stored","received_at":"2026-01-15T18:25:01Z"}
```

A retry of the same ID and canonical body returns 200 with `status: "duplicate"` and the original `received_at`; treat this as successful. A changed body for an existing ID returns 409 `snapshot_id_conflict`. Only one scheduled snapshot per participant and date can be stored; another returns 409 `scheduled_exists`. Manual snapshots on the same day are allowed with distinct IDs. Uploads are limited to 60 requests per registration per rolling minute.

### Read status

`GET /api/v1/usage/snapshots/{snapshot_id}` with a Bearer token returns:

```json
{"snapshot_id":"7eddf4b1-697e-41f1-964e-98aa5b7dfac2","status":"stored","received_at":"2026-01-15T18:25:01Z"}
```

Only a snapshot owned by the authenticated participant is returned; otherwise the response is 404. This endpoint returns status only, not the snapshot payload. The audit screen's full JSON and last-seven-days list therefore come from local storage, not this API.

## Administrator operations

These are staff operations, not participant-app credentials or participant UI features. Local testing uses `Authorization: Basic <base64(username:password)>` with the configured admin account. Use HTTPS and a proper staff identity solution before any real deployment.

### Import registrations

`POST /api/v1/admin/registrations`

```json
{"registrations":[{"registration_id":"P-ABC123","age":25,"gender":"example"}]}
```

The list must contain 1-500 entries; IDs must be unique and not already imported. Age is an integer from 0 to 120; gender is nonblank and at most 64 characters. A successful response is 201 `{"imported":1}`. A duplicate ID causes 409 `registration_exists`; the batch is transactional. Age and gender are sensitive; do not include them in logs, analytics, or participant-facing UI.

### Change registration status

`PATCH /api/v1/admin/registrations/P-ABC123`

```json
{"status":"withdrawn"}
```

Accepted values are `active` and `withdrawn`. Success is 204 with no body; an unknown registration is 404. Withdrawal prevents authentication and uploads and revokes the active device. Reactivating a registration does not restore that revoked device; a device must register again. Withdrawal is not a data-deletion endpoint.

## Errors and UI behavior

API errors normally use this JSON shape and the server also returns an `X-Request-Id` header:

```json
{"code":"invalid_payload","message":"The snapshot does not match the required format.","request_id":"<uuid>"}
```

Use the status and stable `code` for control flow, show a non-sensitive message, and retain the request ID for support without logging tokens, registration IDs, or payloads. Framework-level failures outside the API error handlers may have a different response; handle missing or non-JSON bodies safely.

| Status | Integration behavior |
|---|---|
| 400 | Correct invalid parameters, headers, idempotency key, or public key; do not blindly retry. |
| 401 | For participant requests, refresh once with a new challenge; if still rejected, show authentication failure. For admin, request valid credentials. |
| 403 | Device or registration is not eligible, or snapshot ownership does not match; show an actionable access error, not a successful upload. |
| 404 | Resource not found or not owned; do not imply another participant's snapshot exists. |
| 409 | Show the specific conflict; do not silently change a snapshot ID or body to bypass it. |
| 413 | Body exceeds the size limit; report that the snapshot cannot be uploaded as-is. |
| 422 | Request format or snapshot validation failed; correct the data rather than retrying unchanged. |
| 429 | Respect `Retry-After` seconds; retain the pending snapshot. |
| 500/503 or network failure | Show pending/retry state and retry with backoff; preserve the same snapshot and ID. |

## Frontend integration checklist

- Keep the API base URL configurable; use the OpenAPI schemas for client models, but implement the signing protocol and semantic validations above explicitly.
- Show local activation, permissions, scheduling, and local audit independently of network availability. In Phase 2 distinguish pending, uploaded (including duplicate), and failed upload states; only claim an upload after a successful server response or status check.
- Follow the app plan's connected-network worker, backoff, 24-hour retry window, and local retention rules. A pending upload must not be pruned.
- Test successful registration and same-key retry, rejected replacement key, nonce expiry/replay and bad signatures, token expiry, upload retries and conflicts, validation errors, rate limits, offline recovery, withdrawal, and owned/not-found status.
- Recommendation cards in the mock remain illustrative; no recommendation API is currently available.
