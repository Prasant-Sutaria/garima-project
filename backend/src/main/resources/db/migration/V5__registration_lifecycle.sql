-- Registration lifecycle: not_registered -> activated (first device registration) -> deactivated (admin).
ALTER TABLE registry.registrations DROP CONSTRAINT registrations_status_check;

UPDATE registry.registrations r
SET status = CASE
    WHEN r.status = 'withdrawn' THEN 'deactivated'
    WHEN EXISTS (SELECT 1 FROM devices d WHERE d.registration_id = r.registration_id) THEN 'activated'
    ELSE 'not_registered'
END;

ALTER TABLE registry.registrations
    ALTER COLUMN status SET DEFAULT 'not_registered',
    ADD CONSTRAINT registrations_status_check
        CHECK (status IN ('not_registered', 'activated', 'deactivated'));
