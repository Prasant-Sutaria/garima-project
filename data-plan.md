# Data plan: capture, payload, and storage

> Local-development exception: the implemented synthetic self-registration endpoint accepts and encrypts names, contact details, and activity descriptions. It is not approved for real participants or production; the study privacy and consent requirements below remain unchanged until formally revised.

This file covers the data side of the Android usage publisher described in [app-plan.md](./app-plan.md). It defines what is captured on the device, how it is derived, the publish payload, the server schema, and the phone's local store.

## 0. Privacy and identity

The app collects only usage data. It publishes no personal details.

- **Identifier:** each published record carries a `user_id`, a pseudonymous code issued by the study (for example `P-7K3Q9X`). The participant enters it on the registration screen, which is shown on first launch. The app does not generate it, and it must not be derived from a name, phone number, email, account, IMEI, serial, ANDROID_ID, or advertising ID.
- **Access secrets:** the security code and the secret answer are stored only on the device, encrypted or as verifiers. They are never in the payload or sent to the API.
- **Age and gender:** not sent by the app. The server keeps them in a separate `registry` schema, in a `registrations` table keyed by the registration ID. Each row holds only the registration ID, encrypted age and gender, status, and enrolment time. Age and gender are joined from it during analysis.
- **Not collected:** name, mobile number, email, contacts, accounts, location, notification content, message content, app contents, IP address stored in usage tables, or device identifiers. The payload has no `device` block.
- **Dataset content:** totals and per-app foreground time and launch counts only, with package names as the only app identifiers.

## 1. Captured from the device
Raw input is `UsageEvents` from `UsageStatsManager.queryEvents(begin, end)`. Each event has a package name, an epoch-ms timestamp, and an event type.

| Raw event type | Used for | Min API |
|---|---|---|
| `ACTIVITY_RESUMED` (`MOVE_TO_FOREGROUND` before API 29) | Foreground start, app launch count | 29 (26 for the fallback) |
| `ACTIVITY_PAUSED` (`MOVE_TO_BACKGROUND` before API 29) | Foreground end | 29 (26 for the fallback) |
| `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` | Total screen-on time | 28 |
| `KEYGUARD_HIDDEN` | Unlock count | 28 |
| `NOTIFICATION_INTERRUPTION` | Notification count (best effort) | 28 |

Notes:
- Min SDK 26 is below API 28. On API 26 and 27 the screen, unlock, and notification events are absent, so the app reports them as `unavailable`. The OnePlus 8 (Android 11+) is not affected.
- Android keeps events only for a limited time, so the phone-side snapshot is the only reliable copy.

## 2. Derived in the app (`UsageCollector`)
Rules for turning raw events into the window totals:
- **Window:** local day, 00:00 to the window end (publish time for scheduled runs, now for manual runs). Only time inside `[windowStart, windowEnd)` is counted.
- **Lookback:** query from `windowStart` minus 24 h. An app already in the foreground at midnight then has a start event before the window, so its time is clipped to the window instead of being dropped.
- **Open sessions:** a foreground session with no pause event before `windowEnd` is closed at `windowEnd`. The number of such sessions is stored in `quality.open_sessions_closed`.
- **Pairing:** resume/pause pairs are matched per package. Unmatched events are ignored and counted in `quality.unmatched_events`.
- **Screen-on:** the total of `SCREEN_INTERACTIVE` to `SCREEN_NON_INTERACTIVE` intervals, clipped to the window.
- **Durations:** computed in milliseconds, published as whole seconds (rounded down).
- **Notifications:** measured if events exist. Otherwise a synthetic value is generated, and `notification_source` is set to `synthetic`. If the API cannot provide notifications at all, `notification_count` is `null` and the source is `unavailable`. This replaces the single boolean `notification_count_synthetic` from the earlier design, which cannot express "unavailable". `notification_count_synthetic` is still true when the source is `synthetic`.

## 3. Published payload (phone to server)
`POST /api/v1/usage/snapshots`, with `Authorization: Bearer <token>` and `Idempotency-Key: <snapshot_id>`. Content type is `application/json`. All field names are snake_case.

```json
{
  "schema_version": "1.0",
  "snapshot_id": "7d3c2a1e-5f4b-4c8e-9a10-2b6e8f0d1c44",
  "trigger": "scheduled",
  "date": "2026-11-20",
  "user_id": "P-7K3Q9X",
  "app_version": "1.0.0",
  "timezone": "Asia/Kolkata",
  "utc_offset_minutes": 330,
  "window": {
    "start": "2026-11-20T00:00:00+05:30",
    "end": "2026-11-20T23:55:00+05:30"
  },
  "collected_at": "2026-11-20T23:55:02+05:30",
  "totals": {
    "screen_on_seconds": 14520,
    "unlock_count": 63,
    "notification_count": 88,
    "notification_source": "measured",
    "notification_count_synthetic": false,
    "active_app_count": 2
  },
  "apps": [
    { "package_name": "com.instagram.android", "foreground_seconds": 4210, "launch_count": 18 },
    { "package_name": "com.google.android.gm", "foreground_seconds": 610, "launch_count": 5 }
  ],
  "quality": {
    "event_count": 412,
    "open_sessions_closed": 1,
    "unmatched_events": 0
  }
}
```

Field rules:
- `trigger` is `scheduled` or `manual`.
- `snapshot_id` is a UUIDv4, generated once when the snapshot is saved. Retries reuse it.
- `user_id` is the study-issued registration ID entered on the registration screen. It is the join key to `registry.registrations`. The app validates it against the study format (defined by the study, for example `P-` followed by six characters) before saving. It is not a name or contact detail.
- `apps` lists only packages with `foreground_seconds > 0` or `launch_count > 0`, sorted by `foreground_seconds` descending.
- `active_app_count` is the length of `apps`, stored so it does not need recounting.

Responses:
- `201 Created` with `{ "snapshot_id", "status": "stored", "received_at" }` for a new snapshot.
- `200 OK` with `"status": "duplicate"` when the same `snapshot_id` is sent again. This is what makes retries safe.
- `400` for validation errors, `401` for a bad token. `403` if the registration ID is unknown or withdrawn (not retried). `5xx` or a timeout triggers WorkManager retry.

## 4. Server database (PostgreSQL)

```sql
CREATE SCHEMA registry;   -- registration data, separate from usage data and restricted by role

CREATE TABLE registry.registrations (
  registration_id  TEXT PRIMARY KEY,               -- study-issued ID, the user_id in the payload
  age_enc          BYTEA NOT NULL,                 -- AES-256-GCM ciphertext of age, under the row DEK
  gender_enc       BYTEA NOT NULL,                 -- AES-256-GCM ciphertext of gender, under the row DEK
  dek_wrapped      BYTEA NOT NULL,                 -- per-row data key (DEK), wrapped by the KMS master key
  key_id           TEXT NOT NULL,                  -- KMS master key version that wrapped the DEK
  status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'withdrawn')),
  enrolled_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Limited on purpose: no name, phone number, email, or contact details.

CREATE TABLE usage_snapshots (
  id                      UUID PRIMARY KEY,               -- snapshot_id from the payload
  registration_id         TEXT NOT NULL REFERENCES registry.registrations(registration_id),
  schema_version          TEXT NOT NULL,
  app_version             TEXT NOT NULL,
  trigger                 TEXT NOT NULL CHECK (trigger IN ('scheduled', 'manual')),
  usage_date              DATE NOT NULL,
  timezone                TEXT NOT NULL,
  utc_offset_minutes      SMALLINT NOT NULL,
  window_start            TIMESTAMPTZ NOT NULL,
  window_end              TIMESTAMPTZ NOT NULL,
  collected_at            TIMESTAMPTZ NOT NULL,
  received_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  screen_on_seconds       INTEGER NOT NULL CHECK (screen_on_seconds >= 0),
  unlock_count            INTEGER CHECK (unlock_count >= 0),
  notification_count      INTEGER CHECK (notification_count >= 0),
  notification_source     TEXT NOT NULL CHECK (notification_source IN ('measured', 'synthetic', 'unavailable')),
  active_app_count        INTEGER NOT NULL CHECK (active_app_count >= 0),
  open_sessions_closed    INTEGER NOT NULL DEFAULT 0,
  event_count             INTEGER NOT NULL DEFAULT 0,
  payload_enc             BYTEA NOT NULL,                 -- encrypted full payload, kept for audit and re-derivation
  payload_key_id          TEXT NOT NULL,                  -- KMS master key version that wrapped the payload DEK
  CHECK (window_end > window_start)
);

-- One scheduled snapshot per registration ID per date. Manual snapshots are unlimited.
CREATE UNIQUE INDEX uq_scheduled_per_day
  ON usage_snapshots (registration_id, usage_date) WHERE trigger = 'scheduled';
CREATE INDEX ix_snapshots_registration_date ON usage_snapshots (registration_id, usage_date);

CREATE TABLE app_usage (
  snapshot_id        UUID NOT NULL REFERENCES usage_snapshots(id) ON DELETE CASCADE,
  package_name       TEXT NOT NULL,
  foreground_seconds INTEGER NOT NULL CHECK (foreground_seconds >= 0),
  launch_count       INTEGER NOT NULL DEFAULT 0 CHECK (launch_count >= 0),
  PRIMARY KEY (snapshot_id, package_name)
);
CREATE INDEX ix_app_usage_package ON app_usage (package_name);

-- One successful result per participant and scheduled scoring date.
CREATE TABLE participant_risk_scores (
  registration_id   TEXT NOT NULL REFERENCES registry.registrations(registration_id) ON DELETE CASCADE,
  scoring_date      DATE NOT NULL,                    -- scheduled run date in UTC
  scored_at         TIMESTAMPTZ NOT NULL,
  input_window_start TIMESTAMPTZ NOT NULL,
  input_window_end   TIMESTAMPTZ NOT NULL,           -- exclusive
  model_version     TEXT NOT NULL,
  feature_version   TEXT NOT NULL,
  risk_score        NUMERIC(7,6) NOT NULL CHECK (risk_score >= 0 AND risk_score <= 1),
  risk_drivers      JSONB NOT NULL CHECK (
    jsonb_typeof(risk_drivers) = 'array' AND jsonb_array_length(risk_drivers) <= 3
  ),
  remediation_ids   TEXT[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (registration_id, scoring_date),
  CHECK (input_window_end > input_window_start)
);
CREATE INDEX ix_risk_scores_latest
  ON participant_risk_scores (registration_id, scoring_date DESC);
```

Design notes:
- `snapshot_id` is the primary key, so a duplicate POST is rejected by the database. The API returns `duplicate` instead of inserting a second row.
- The partial unique index keeps one scheduled snapshot per registration ID per day. A manual snapshot on the same day is stored as its own row. The dashboard can show either the latest or all of them.
- Totals are stored as plain columns so they can be queried. They are protected by the encryption at rest described below. `payload_enc` keeps the exact JSON that was received, encrypted.
- `notification_count` is nullable only when the source is `unavailable`. Add a check constraint if you want to enforce this.

### Nightly model results
- The Python scoring job produces results; a restricted writer saves them in `participant_risk_scores`. The scheduled run uses a UTC `scoring_date`, fixed for that run even if a retry finishes on another day. `scored_at` records the actual scoring time, and the half-open input window and model and feature versions identify what was scored.
- The primary key retains one successful result per participant per scheduled date. A retry with identical data is a no-op; a conflicting result for the same key is rejected and reported. Any correction or rescore policy must be agreed before allowing replacement.
- `risk_score` is a model estimate between 0 and 1, not a diagnosis. `risk_drivers` is an ordered JSON array of up to three plain-language drivers; `remediation_ids` references the clinician-reviewed library. Validate their contents and identifiers in the writer, not only their container types.
- Spring Boot retrieves the latest successful result by `scoring_date` for the authenticated participant. It applies the participant's study arm before returning suggestions: monitoring-only participants receive none, regardless of stored IDs. Missing results and failed or stale runs must be reported explicitly, not represented by a zero score or an apparently current result. The response and staleness policy remain to be defined.
- `registration_id` is the existing pseudonymous join key, not a name or contact detail. Model exports must not contain it: an authorized linking step resolves the de-identified model identifier to the registration only when saving results. The mapping mechanism and its access controls remain to be designed.
- The writer and withdrawal operation must coordinate on the same registration row: lock it and verify it is active in the same transaction as the result write. Withdrawal locks the row, marks it withdrawn, and deletes model results and inputs along with the other participant data. A delayed or concurrent writer must not recreate results after withdrawal; a missing registration also rejects the write.
- The participant's request to remove risk output must delete it and prevent future output from being recreated until the applicable consent and re-enablement policy is met. Define that policy and its persisted state before enabling the feature.
- This table is for study data only. Synthetic scores must carry `source = synthetic` and use separate tables from real scores, as required by [model-plan.md](./model-plan.md). Do not copy synthetic records into this table.
- Risk scores and drivers are sensitive participant data. Apply database and backup encryption, restrict the writer and Spring Boot to the minimum required access, and exclude scores, drivers, and recommendation IDs from routine logs. Agree retention and backup deletion procedures under the study's consent and ethics terms.

### Encryption at rest
Data is encrypted in three layers:
1. **Storage encryption (whole database):** the PostgreSQL volume, its WAL, and all backups use AES-256 transparent encryption, with keys held by the hosting provider's KMS. This covers the plain totals and the app usage rows.
2. **Field-level envelope encryption (sensitive columns):** `registry.registrations.age_enc` and `gender_enc`, and `usage_snapshots.payload_enc`. Each row has its own AES-256-GCM data key (DEK). The DEK is wrapped by a KMS master key, and `key_id` records which key version was used. The master key is never stored in the database or in the repository. Keys are rotated, and old versions are kept until the data is re-wrapped.
3. **Access separation:** the ingest service uses a role that can insert usage rows and check `registrations.registration_id` and `status`. It cannot decrypt age or gender. A separate analysis role can join usage to registrations, and only a named role can call the KMS decrypt. Every decrypt is logged.

Trade-off: the plain totals can be queried, but age and gender can only be read after decryption. Age-group analysis is done after decryption, in the analysis environment.

Logs, error messages, and the audit trail must not contain age, gender, or the decrypted payload.

## 5. Phone snapshot store (Room, Phase 1)
Room table `snapshots`, for the local copy and the audit screen. The table is encrypted at rest. `payload_enc` is encrypted with the Keystore AES-256-GCM key from the app plan, so the phone holds no plain payload on disk:

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | `snapshot_id` |
| `date` | TEXT | `yyyy-MM-dd` in local time |
| `trigger` | TEXT | `scheduled` or `manual` |
| `window_start`, `window_end` | INTEGER | epoch ms |
| `created_at` | INTEGER | epoch ms |
| `status` | TEXT | `saved_local`, `pending_upload`, `uploaded`, `failed` (Phase 2 only; Phase 1 uses `saved_local`) |
| `attempts` | INTEGER | Phase 2 |
| `last_http_code` | INTEGER NULL | Phase 2 |
| `last_attempt_at` | INTEGER NULL | Phase 2 |
| `payload_enc` | BLOB | the exact payload that is sent, AES-256-GCM encrypted with the Keystore key; decrypted only to show the audit screen or to upload |

Pruning: delete rows older than 7 days, but only if `status` is `uploaded` or `failed` (after the 24 h retry window). Rows in `saved_local` or `pending_upload` are never pruned.

## Open questions
1. **Gap at the publish time.** A scheduled run at 23:55 does not cover 23:55 to 24:00, and the next day's window starts at 00:00. Options: publish at 23:59 with the window ending at 23:59:59, or accept the small gap. The plan assumes the gap is acceptable.
2. **Per-app launch count.** `launch_count` is an addition to the plan. Confirm it is wanted for the prediction study.
3. **Package names.** The payload contains installed-app package names. They identify apps, not people, but they still reveal app use, so confirm they are acceptable under the study's consent approach.
4. **Issuing the registration ID.** The plan assumes the researcher issues each ID and the participant types it on the registration screen. The server rejects unknown or withdrawn IDs with `403`. Confirm this is the right enrolment flow.
5. **Age storage.** `age` is stored encrypted as a number. Confirm whether an age band (for example 18 to 24) is preferred for stronger anonymity.
6. **Registration ID as join key.** The ID is plain text in `registrations` and in the usage rows, so the join works without decrypting anything. If the ID itself should not appear in analysis data, the usage rows could carry a keyed HMAC of the ID instead. Confirm which option is wanted.
7. **Key management.** Confirm the KMS provider and who is allowed to decrypt age and gender.
8. **Model result linkage and retention.** Define the protected mapping from de-identified model identifiers to registrations, score retention and backup deletion, staleness reporting, and participant-requested removal and re-enablement.
