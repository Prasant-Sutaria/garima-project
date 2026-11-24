CREATE TABLE registry.enrollment_details (
    registration_id TEXT PRIMARY KEY REFERENCES registry.registrations(registration_id) ON DELETE CASCADE,
    details_enc BYTEA NOT NULL,
    dek_wrapped BYTEA NOT NULL,
    key_id TEXT NOT NULL,
    email_fingerprint BYTEA NOT NULL UNIQUE,
    mobile_fingerprint BYTEA NOT NULL UNIQUE
);

CREATE TABLE registry.contact_index_key (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    verifier BYTEA NOT NULL
);
