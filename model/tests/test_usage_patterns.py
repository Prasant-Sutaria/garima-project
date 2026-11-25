import sqlite3
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from model_component.daily_patterns import train_daily_pattern_model
from model_component.hourly_synthetic import PROFESSIONS, generate_hourly_usage, read_csv
from model_component.postgres_source import load_real_daily_usage, load_synthetic_hourly_usage
from model_component.time_of_day import learn_time_of_day_patterns

COMMITTED_DATA = Path(__file__).resolve().parents[1] / "data" / "synthetic"


def _real_daily(participants: int, days: int, seed: int = 1) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    start = pd.Timestamp("2026-01-05")  # a Monday
    rows = []
    for participant in range(1, participants + 1):
        base = rng.uniform(2.5, 4.5)
        for offset in range(days):
            day = start + pd.Timedelta(days=offset)
            weekend = day.day_of_week >= 5
            hours = base + 1.5 * weekend + rng.normal(0, 0.3)
            rows.append(
                {
                    "participant_index": participant,
                    "usage_date": day,
                    "screen_on_seconds": max(hours, 0.0) * 3600,
                    "unlock_count": 40,
                    "active_app_count": 8,
                    "source": "real",
                }
            )
    return pd.DataFrame(rows)


def test_real_loader_excludes_withdrawn_and_manual_rows_and_hides_registration_ids():
    connection = sqlite3.connect(":memory:")
    connection.execute("ATTACH ':memory:' AS registry")
    connection.execute("CREATE TABLE registry.registrations (registration_id TEXT, status TEXT)")
    connection.execute(
        "CREATE TABLE usage_snapshots (registration_id TEXT, usage_date TEXT, "
        "\"trigger\" TEXT, screen_on_seconds REAL, unlock_count INT, active_app_count INT)"
    )
    connection.executemany(
        "INSERT INTO registry.registrations VALUES (?, ?)",
        [("a", "active"), ("b", "withdrawn"), ("c", "active")],
    )
    connection.executemany(
        "INSERT INTO usage_snapshots VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("a", "2026-01-05", "scheduled", 3600, 10, 4),
            ("a", "2026-01-06", "scheduled", 7200, 12, 5),
            ("a", "2026-01-06", "manual", 999, 1, 1),
            ("b", "2026-01-05", "scheduled", 3600, 10, 4),
            ("c", "2026-01-05", "scheduled", 1800, 6, 3),
        ],
    )

    loaded = load_real_daily_usage(connection)

    assert len(loaded) == 3
    assert set(loaded["source"]) == {"real"}
    assert "registration_id" not in loaded.columns
    assert loaded["participant_index"].nunique() == 2
    assert 999 not in loaded["screen_on_seconds"].tolist()


def test_daily_model_rejects_non_real_rows():
    synthetic = _real_daily(12, 10).assign(source="synthetic")
    with pytest.raises(ValueError, match="source=real"):
        train_daily_pattern_model(synthetic)


def test_daily_model_reports_insufficient_data_for_small_groups():
    result = train_daily_pattern_model(_real_daily(participants=3, days=30))
    assert result["status"] == "insufficient_data"


def test_daily_model_learns_weekend_effect():
    result = train_daily_pattern_model(_real_daily(participants=12, days=40))

    assert result["status"] == "ok"
    assert 1.0 < result["weekend_minus_weekday_hours"] < 2.0


def test_synthetic_usage_leans_to_night_and_differs_by_profession():
    data = generate_hourly_usage(participants=120, days=14, seed=5)
    hourly = data.hourly_usage.merge(data.participants[["synthetic_id", "profession"]])
    late = hourly["hour"].isin([21, 22, 23, 0, 1, 2])
    totals = hourly.groupby("profession")["screen_minutes"].sum()
    night_share = hourly[late].groupby("profession")["screen_minutes"].sum() / totals
    after_midnight = hourly[hourly["hour"].isin([0, 1, 2])]
    late_night_share = after_midnight.groupby("profession")["screen_minutes"].sum() / totals

    assert set(night_share.index) == set(PROFESSIONS)
    assert (night_share > 0.35).all()
    assert late_night_share["student"] > late_night_share.drop("student").max()
    assert hourly["screen_minutes"].between(0, 60).all()


def test_committed_synthetic_csvs_match_the_postgres_schema_rules():
    data = read_csv(COMMITTED_DATA)
    hourly = data.hourly_usage

    assert len(data.participants) == 300
    assert set(data.participants["profession"]) == set(PROFESSIONS)
    assert len(hourly) == 300 * 28 * 24
    assert not hourly.duplicated(["synthetic_id", "usage_date", "hour"]).any()
    assert hourly["hour"].between(0, 23).all()
    assert hourly["screen_minutes"].between(0, 60).all()
    assert set(hourly["synthetic_id"]) <= set(data.participants["synthetic_id"])


def test_synthetic_loader_reads_the_separate_schema():
    data = generate_hourly_usage(participants=30, days=7, seed=2)
    connection = sqlite3.connect(":memory:")
    connection.execute("ATTACH ':memory:' AS synthetic")
    connection.execute("CREATE TABLE synthetic.participants (synthetic_id, profession, age_band)")
    connection.execute(
        "CREATE TABLE synthetic.hourly_usage "
        "(synthetic_id, usage_date, hour, screen_minutes, unlock_count)"
    )
    connection.executemany(
        "INSERT INTO synthetic.participants VALUES (?, ?, ?)",
        data.participants[["synthetic_id", "profession", "age_band"]].itertuples(index=False),
    )
    hourly = data.hourly_usage.assign(usage_date=data.hourly_usage["usage_date"].dt.date)
    connection.executemany(
        "INSERT INTO synthetic.hourly_usage VALUES (?, ?, ?, ?, ?)",
        hourly[["synthetic_id", "usage_date", "hour", "screen_minutes", "unlock_count"]]
        .astype({"usage_date": str})
        .itertuples(index=False),
    )

    loaded = load_synthetic_hourly_usage(connection)

    assert set(loaded.hourly_usage["source"]) == {"synthetic"}
    assert len(loaded.hourly_usage) == 30 * 7 * 24
    assert set(loaded.participants["profession"]) <= set(PROFESSIONS)


def test_time_of_day_learns_profession_rhythms_from_synthetic_rows():
    data = generate_hourly_usage(participants=200, seed=7)

    result = learn_time_of_day_patterns(data.hourly_usage, data.participants, seed=7)

    classifier = result["profession_classifier"]
    assert result["status"] == "ok"
    assert {p["profession"] for p in result["professions"]} == set(PROFESSIONS)
    assert classifier["balanced_accuracy"] > 0.75
    assert classifier["balanced_accuracy"] > classifier["majority_baseline_accuracy"] + 0.3


def test_time_of_day_rejects_real_rows():
    hourly = generate_hourly_usage(participants=40, seed=3).hourly_usage.assign(source="real")
    with pytest.raises(ValueError, match="source=synthetic"):
        learn_time_of_day_patterns(hourly)
