CREATE SCHEMA registry;

CREATE TABLE registry.registrations (
    registration_id TEXT PRIMARY KEY,
    age_enc BYTEA NOT NULL,
    gender_enc BYTEA NOT NULL,
    dek_wrapped BYTEA NOT NULL,
    key_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'withdrawn')),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    registration_id TEXT NOT NULL REFERENCES registry.registrations(registration_id),
    public_key_jwk JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    app_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_active_device_per_registration
    ON devices (registration_id) WHERE status = 'active';

CREATE TABLE auth_challenges (
    nonce_hash BYTEA PRIMARY KEY,
    registration_id TEXT NOT NULL REFERENCES registry.registrations(registration_id),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CHECK (expires_at > issued_at)
);

CREATE INDEX ix_auth_challenges_expires ON auth_challenges (expires_at);

CREATE TABLE signing_keys (
    kid TEXT PRIMARY KEY,
    alg TEXT NOT NULL DEFAULT 'ES256' CHECK (alg = 'ES256'),
    public_jwk JSONB NOT NULL,
    kms_key_ref TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('active', 'retired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retire_after TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_active_signing_key ON signing_keys (status) WHERE status = 'active';

CREATE TABLE usage_snapshots (
    id UUID PRIMARY KEY,
    registration_id TEXT NOT NULL REFERENCES registry.registrations(registration_id),
    device_id UUID NOT NULL REFERENCES devices(id),
    schema_version TEXT NOT NULL,
    app_version TEXT NOT NULL,
    trigger TEXT NOT NULL CHECK (trigger IN ('scheduled', 'manual')),
    usage_date DATE NOT NULL,
    timezone TEXT NOT NULL,
    utc_offset_minutes SMALLINT NOT NULL CHECK (utc_offset_minutes BETWEEN -840 AND 840),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    screen_on_seconds INTEGER NOT NULL CHECK (screen_on_seconds >= 0),
    unlock_count INTEGER CHECK (unlock_count >= 0),
    notification_count INTEGER CHECK (notification_count >= 0),
    notification_source TEXT NOT NULL CHECK (notification_source IN ('measured', 'synthetic', 'unavailable')),
    notification_count_synthetic BOOLEAN NOT NULL,
    active_app_count INTEGER NOT NULL CHECK (active_app_count >= 0),
    open_sessions_closed INTEGER NOT NULL CHECK (open_sessions_closed >= 0),
    unmatched_events INTEGER NOT NULL CHECK (unmatched_events >= 0),
    event_count INTEGER NOT NULL CHECK (event_count >= 0),
    body_sha256 BYTEA NOT NULL,
    payload_enc BYTEA NOT NULL,
    payload_dek_wrapped BYTEA NOT NULL,
    payload_key_id TEXT NOT NULL,
    CHECK (window_end > window_start),
    CHECK ((notification_source = 'unavailable') = (notification_count IS NULL)),
    CHECK (notification_count_synthetic = (notification_source = 'synthetic'))
);

CREATE UNIQUE INDEX uq_scheduled_per_day
    ON usage_snapshots (registration_id, usage_date) WHERE trigger = 'scheduled';
CREATE INDEX ix_snapshots_registration_date
    ON usage_snapshots (registration_id, usage_date);

CREATE TABLE app_usage (
    snapshot_id UUID NOT NULL REFERENCES usage_snapshots(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    foreground_seconds INTEGER NOT NULL CHECK (foreground_seconds >= 0),
    launch_count INTEGER NOT NULL DEFAULT 0 CHECK (launch_count >= 0),
    PRIMARY KEY (snapshot_id, package_name)
);
CREATE INDEX ix_app_usage_package ON app_usage (package_name);

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target_id TEXT,
    request_id TEXT NOT NULL,
    outcome TEXT NOT NULL
);

CREATE FUNCTION reject_audit_log_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();
