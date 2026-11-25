-- Stored unencrypted so read-only analysis can group usage by profession.
-- Rows created before this migration have no profession.
ALTER TABLE registry.enrollment_details
    ADD COLUMN profession TEXT
        CHECK (profession IN ('student', 'working_professional', 'homemaker'));
