-- Generated (synthetic) hourly phone usage. Never joined to real study tables.
CREATE SCHEMA IF NOT EXISTS synthetic;
COMMENT ON SCHEMA synthetic IS
    'Generated demo data only (model/data/synthetic). Not participant data; never mix with public or registry tables.';

CREATE TABLE IF NOT EXISTS synthetic.participants (
    synthetic_id INTEGER PRIMARY KEY,
    profession TEXT NOT NULL
        CHECK (profession IN ('student', 'working_professional', 'homemaker')),
    age_band TEXT NOT NULL CHECK (age_band IN ('18-24', '25-34', '35-44', '45+'))
);

CREATE TABLE IF NOT EXISTS synthetic.hourly_usage (
    synthetic_id INTEGER NOT NULL REFERENCES synthetic.participants(synthetic_id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    hour SMALLINT NOT NULL CHECK (hour BETWEEN 0 AND 23),
    screen_minutes NUMERIC(4, 1) NOT NULL CHECK (screen_minutes BETWEEN 0 AND 60),
    unlock_count INTEGER NOT NULL CHECK (unlock_count >= 0),
    PRIMARY KEY (synthetic_id, usage_date, hour)
);
