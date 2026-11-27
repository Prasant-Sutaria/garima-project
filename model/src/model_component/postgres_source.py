"""Read-only loaders for PostgreSQL.

Real daily rows: the query returns no registration identifiers; participants are numbered
within the result set, and only activated registrations' scheduled snapshots are included.
Synthetic hourly rows come from the separate `synthetic` schema and are tagged
source=synthetic; the two sources are never combined.
"""

from __future__ import annotations

import os
from typing import Any

import pandas as pd

from model_component.hourly_synthetic import SyntheticHourlyDataset, tag_dataset

DAILY_USAGE_SQL = """
SELECT DENSE_RANK() OVER (ORDER BY s.registration_id) AS participant_index,
       s."usage_date" AS usage_date,
       s.screen_on_seconds,
       s.unlock_count,
       s.active_app_count
FROM usage_snapshots AS s
JOIN registry.registrations AS r ON r.registration_id = s.registration_id
WHERE s."trigger" = 'scheduled'
  AND r.status = 'activated'
ORDER BY participant_index, usage_date
"""


def load_real_daily_usage(connection: Any) -> pd.DataFrame:
    """Return one row per participant per scheduled day, tagged as source=real."""
    frame = pd.read_sql_query(DAILY_USAGE_SQL, connection)
    frame["usage_date"] = pd.to_datetime(frame["usage_date"])
    if frame.duplicated(["participant_index", "usage_date"]).any():
        raise ValueError("duplicate scheduled snapshots for one participant and day")
    frame["source"] = "real"
    return frame


SYNTHETIC_PARTICIPANTS_SQL = """
SELECT synthetic_id, profession, age_band, addiction_level
FROM synthetic.participants
ORDER BY synthetic_id
"""

SYNTHETIC_HOURLY_SQL = """
SELECT synthetic_id, usage_date, hour, screen_minutes, unlock_count
FROM synthetic.hourly_usage
ORDER BY synthetic_id, usage_date, hour
"""


def load_synthetic_hourly_usage(connection: Any) -> SyntheticHourlyDataset:
    """Read the generated hourly rows from the separate `synthetic` schema."""
    return tag_dataset(
        pd.read_sql_query(SYNTHETIC_PARTICIPANTS_SQL, connection),
        pd.read_sql_query(SYNTHETIC_HOURLY_SQL, connection),
    )


def connect_from_env() -> Any:
    """Open a read-only connection using MODEL_DB_* variables.

    The password is required; the connection is forced into read-only transactions so the
    job cannot write to the study database even if the role has more privileges.
    """
    import psycopg

    password = os.getenv("MODEL_DB_PASSWORD", "")
    if not password:
        raise RuntimeError("MODEL_DB_PASSWORD must be set to read real usage data")
    return psycopg.connect(
        host=os.getenv("MODEL_DB_HOST", "postgres"),
        port=int(os.getenv("MODEL_DB_PORT", "5432")),
        dbname=os.getenv("MODEL_DB_NAME", "usage"),
        user=os.getenv("MODEL_DB_USER", "usage_model_reader"),
        password=password,
        connect_timeout=10,
        options="-c default_transaction_read_only=on",
    )
