CREATE TABLE registry.schemes (
    duration_months INTEGER PRIMARY KEY CHECK (duration_months IN (3, 6, 12)),
    price NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO registry.schemes (duration_months, price, active) VALUES
    (3, 0, TRUE),
    (6, 0, TRUE),
    (12, 0, TRUE);

ALTER TABLE registry.enrollment_details
    ADD COLUMN scheme_months INTEGER REFERENCES registry.schemes(duration_months);
