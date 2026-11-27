-- Loads the committed synthetic CSVs into the `synthetic` schema. Safe to re-run: existing
-- synthetic rows are replaced in one transaction. Run from this directory with psql:
--   psql -v ON_ERROR_STOP=1 -f load.sql
\set ON_ERROR_STOP on

BEGIN;
\ir schema.sql

TRUNCATE synthetic.hourly_usage, synthetic.participants;
-- Upgrades a schema created before the addiction_level tag existed (the table is empty here).
ALTER TABLE synthetic.participants ADD COLUMN IF NOT EXISTS addiction_level TEXT NOT NULL
    CHECK (addiction_level IN ('pre', 'mild', 'high'));
\copy synthetic.participants (synthetic_id, profession, age_band, addiction_level) FROM 'participants.csv' WITH (FORMAT csv, HEADER true)
\copy synthetic.hourly_usage (synthetic_id, usage_date, hour, screen_minutes, unlock_count) FROM 'hourly_usage.csv' WITH (FORMAT csv, HEADER true)

-- Let the model job's read-only role read the synthetic schema when that role exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'usage_model_reader') THEN
        GRANT USAGE ON SCHEMA synthetic TO usage_model_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA synthetic TO usage_model_reader;
    END IF;
END
$$;
COMMIT;

SELECT profession, count(*) AS participants FROM synthetic.participants GROUP BY profession ORDER BY profession;
SELECT addiction_level, count(*) AS participants FROM synthetic.participants GROUP BY addiction_level ORDER BY addiction_level;
SELECT count(*) AS hourly_rows FROM synthetic.hourly_usage;
