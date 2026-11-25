# Registration UI and API specification

## Purpose and boundary

Generate a UI for the implemented local self-registration API. This is a **synthetic-data development flow only**: it does not verify contact ownership, can reveal whether a contact is registered, and is unavailable when the `prod` profile is active. Do not collect real participant information or present this as production enrollment.

The existing [app plan](../app-plan.md) keeps Phase 1 offline. Any network registration UI must be explicitly restricted to local development and must not make Phase 1 depend on a network. The [app OpenAPI contract](./api/openapi/usage-api-app.yaml) and [frontend API specification](./frontend-api-spec.md) describe the broader API.

## Screen and fields

Display a clear local-test-only notice, all fields below, and a Register button. All fields are required. Show validation errors next to the relevant field and preserve entered values when a submission fails.

| Label | JSON field | Input | Validation |
|---|---|---|---|
| User name | `user_name` | Text | Nonblank; maximum 120 UTF-16 code units |
| Mobile number | `mobile_number` | Phone | E.164: `+` followed by 2-15 digits, first digit nonzero; regex `^\+[1-9][0-9]{1,14}$` |
| Email | `email` | Email | Nonblank, valid email syntax; maximum 254 UTF-16 code units |
| Age | `age` | Numeric | Whole number from 0 to 120 inclusive; send as a JSON number |
| Gender | `gender` | Radio group | One of Female, Male, Non-binary, Prefer not to say (sent as text); the backend accepts any nonblank text up to 64 UTF-16 code units |
| Profession | `profession` | Radio group | One of Working professional, Homemaker, Student, sent as `working_professional`, `homemaker`, or `student`; the backend rejects any other value |
| Indoor activity | `indoor_activity` | Hobby textboxes, joined with `, ` | Nonblank after joining; maximum 2,000 UTF-16 code units |
| Outdoor activity | `outdoor_activity` | Hobby textboxes, joined with `, ` | Nonblank after joining; maximum 2,000 UTF-16 code units |
| Scheme | `scheme_months` | Radio group | Integer 3, 6, or 12 (months); currently free of charge. The backend accepts only active schemes in `registry.schemes` |
| Terms and conditions | (not sent) | Checkbox | Must be checked: "By registering, you accept the terms and conditions." Enforced in the browser only |

Use descriptive labels and keyboard types, associate errors with inputs, and make the form and submission status accessible to screen readers. Do not invent gender options, activity categories, or extra required fields as API requirements. Server validation is authoritative; a 422 response does not identify individual invalid fields.

## API contract

**Request:** `POST /api/v1/registrations`

- Access: public; no Bearer token or admin credentials required.
- Headers: `Content-Type: application/json`, `Accept: application/json`.
- Base URL: configurable; local backend default is `http://localhost:8080`. On Android, `localhost` refers to the device, not the development computer. Browser clients on a different origin need a same-origin development proxy or separately configured CORS.
- The server limits POST bodies to 262,144 bytes and rate-limits this endpoint to 10 requests per source IP per minute.

Example request using synthetic data:

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

**Success:** HTTP `201 Created`

```json
{"registration_id":"P-ABC123"}
```

The ID matches `P-[A-Z0-9]{6}`. It identifies a new registration with status `not_registered`; it becomes `activated` when the app registers a device with it. A successful response does **not** mean the device is registered or authenticated, contact ownership is verified, or any usage data has been uploaded.

The backend encrypts submitted data. It compares email case-insensitively and canonical E.164 mobile numbers exactly; a duplicate of either among self-registrations is rejected. Admin-imported registrations contain no contact details and cannot be checked for contact duplicates. The selected scheme is stored unencrypted in `registry.enrollment_details.scheme_months` (references `registry.schemes`, which is managed by seed data and has a `price` column for future charging). The profession is also stored unencrypted, in `registry.enrollment_details.profession`, so read-only analysis can group usage by profession.

## UI flow and states

1. **Editing:** validate all fields before submission. Explain the required international phone format. Keep the Register button available to submit valid input.
2. **Submitting:** send one request and disable repeat submission while it is in flight. Do not automatically retry registration.
3. **Success:** only after a 201 response with a valid `registration_id`, display the ID clearly and explain that it is needed for activation. Do not show the submitted personal details in the success message. If the app stores the ID for activation, follow the encrypted-storage and masking rules in the app plan. The user must not lose the ID before completing that flow.
4. **Known rejection:** show an appropriate error and allow the user to correct input or wait as indicated below. Do not infer which contact caused a conflict.
5. **Unknown outcome:** a timeout, network interruption, malformed success response, or server error may occur after the server created the registration. Show that the outcome is unknown; do not silently retry, assume success, or promise ID recovery. There is no implemented recovery endpoint. A repeated submission may return 409 without returning the original ID.

The local registration endpoint is not idempotent and does not accept an idempotency key. A client-side timeout or cancellation cannot undo a request already received by the server.

## Errors

API errors normally return:

```json
{"code":"registration_exists","message":"A registration could not be completed.","request_id":"<uuid>"}
```

The response also has an `X-Request-Id` header. Handle missing or non-JSON error bodies gracefully. Show generic, non-sensitive text and, if useful, the request ID for support.

| HTTP status | UI behavior |
|---|---|
| 400 `invalid_scheme` | Explain that the selected scheme is not available; the UI should let the user choose again. |
| 409 | Explain that registration could not be completed because a submitted contact may already be registered. Do not identify which field or treat it as success. |
| 413 | Explain that the request is too large; retain input for correction. |
| 422 | Explain that the request is invalid; show local field validation where possible. Do not invent server field-level errors. |
| 429 | Show a rate-limit message and respect the `Retry-After` header before allowing another attempt. The server currently returns `60` seconds. |
| 5xx, timeout, network failure | Show an unknown-outcome message; do not automatically resubmit. |

Unexpected statuses should be shown as failures, not successes. Do not log request bodies, contact details, activity descriptions, or the full registration ID in analytics, crash reports, or application logs.

## Acceptance checks

- Every field is required and the UI sends the exact snake_case JSON names and integer age.
- Invalid phone format, blank values, out-of-range ages, and excessive lengths are caught locally; server-side 422 is still handled.
- A 201 response displays the returned ID without claiming device activation or contact verification.
- Duplicate contact, 413, 422, and 429 are shown distinctly; 429 respects `Retry-After`.
- A timeout or 5xx is treated as an unknown outcome without automatic retry.
- The UI is local-test-only and uses synthetic data; Phase 1 remains usable offline.
