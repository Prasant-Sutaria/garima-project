"""Deterministic generator for synthetic daily usage snapshots."""

from __future__ import annotations

import json
from dataclasses import dataclass

import numpy as np
import pandas as pd

APP_NAMES = (
    "synthetic.app.social",
    "synthetic.app.video",
    "synthetic.app.games",
    "synthetic.app.reading",
)
AGE_BANDS = ("18-24", "25-34", "35-44", "45+")
INTERESTS = ("gaming", "sport", "reading", "music", "creative")
SOURCE = "synthetic"
RNG_SEED = 20261124


@dataclass(frozen=True)
class SyntheticDataset:
    daily_usage: pd.DataFrame
    participants: int
    days_per_period: int
    periods_per_participant: int


def generate_daily_usage(
    participants: int = 2000,
    days_per_period: int = 14,
    seed: int = RNG_SEED,
) -> SyntheticDataset:
    """Create two non-overlapping-in-time cohorts of synthetic daily snapshots."""
    if participants < 30:
        raise ValueError("participants must be at least 30 for grouped validation")
    if days_per_period < 7:
        raise ValueError("days_per_period must be at least 7")

    rng = np.random.default_rng(seed)
    holdout_count = max(1, int(round(participants * 0.2)))
    training_count = participants - holdout_count
    base_date = pd.Timestamp("2025-01-01")
    rows: list[dict[str, object]] = []

    for participant_index in range(participants):
        is_holdout_cohort = participant_index >= training_count
        cohort_offset = 60 if is_holdout_cohort else 0
        cohort_spread = 14
        enrollment_date = base_date + pd.Timedelta(
            days=cohort_offset + participant_index % cohort_spread
        )
        participant_id = f"synthetic-{participant_index + 1:06d}"
        age_band = str(rng.choice(AGE_BANDS))
        interest = str(rng.choice(INTERESTS))
        usual_screen_seconds = float(rng.lognormal(np.log(3.2 * 3600), 0.3))
        usual_screen_seconds = float(np.clip(usual_screen_seconds, 45 * 60, 12 * 3600))
        usual_unlocks = float(np.clip(rng.normal(58, 18), 8, 160))
        concentration = float(rng.beta(2.2, 3.5))
        trend_fraction = float(rng.normal(0.0, 0.07))

        for period in range(2):
            period_screen_shift = 1.0 + trend_fraction * period
            for day in range(days_per_period):
                study_day = period * days_per_period + day
                daily_date = enrollment_date + pd.Timedelta(days=study_day)
                weekend = daily_date.day_of_week >= 5
                weekend_shift = 1.12 if weekend else 0.97
                screen_seconds = int(
                    np.clip(
                        rng.normal(
                            usual_screen_seconds * period_screen_shift * weekend_shift,
                            usual_screen_seconds * 0.16,
                        ),
                        0,
                        16 * 3600,
                    )
                )
                unlock_count = int(
                    np.clip(rng.poisson(max(1, usual_unlocks * period_screen_shift)), 0, 300)
                )

                app_weights = rng.dirichlet(
                    np.array(
                        [
                            0.4 + concentration * 5,
                            1.3,
                            0.8 + (interest == "gaming") * 1.2,
                            0.8 + (interest == "reading") * 1.2,
                        ]
                    )
                )
                app_seconds = np.floor(app_weights * screen_seconds).astype(int)
                launches = np.maximum(0, rng.poisson(max(0.1, unlock_count / 4), size=4))
                apps = [
                    {
                        "package_name": app_name,
                        "foreground_seconds": int(seconds),
                        "launch_count": int(count),
                    }
                    for app_name, seconds, count in zip(
                        APP_NAMES, app_seconds, launches, strict=True
                    )
                    if seconds > 0 or count > 0
                ]
                rows.append(
                    {
                        "source": SOURCE,
                        "synthetic_id": participant_id,
                        "enrollment_date": enrollment_date.date().isoformat(),
                        "period": period,
                        "date": daily_date.date().isoformat(),
                        "age_band": age_band,
                        "declared_interest": interest,
                        "screen_on_seconds": screen_seconds,
                        "unlock_count": unlock_count,
                        "apps_json": json.dumps(apps, separators=(",", ":")),
                    }
                )

    return SyntheticDataset(
        daily_usage=pd.DataFrame.from_records(rows),
        participants=participants,
        days_per_period=days_per_period,
        periods_per_participant=2,
    )


def add_synthetic_questionnaire_label(
    feature_rows: pd.DataFrame,
    effect_scale: float = 1.0,
    seed: int = RNG_SEED + 1,
) -> pd.DataFrame:
    """Add a noisy, explicitly synthetic outcome derived from usage features."""
    if effect_scale <= 0:
        raise ValueError("effect_scale must be greater than zero")
    if set(feature_rows["source"].unique()) != {SOURCE}:
        raise ValueError("synthetic labels can only be generated for source=synthetic")

    result = feature_rows.copy()
    rng = np.random.default_rng(seed)
    screen_hours = result["mean_daily_screen_on_seconds"] / 3600
    unlocks = result["mean_daily_unlock_count"]
    trend_hours = result["usage_trend_per_day"] / 3600
    noise = rng.normal(0, 0.8, size=len(result))
    logit = (
        -3.2
        + effect_scale
        * (
            0.65 * screen_hours
            + 0.012 * unlocks
            + 0.7 * trend_hours.clip(lower=0)
            + 0.4 * result["top_app_share"]
        )
    )
    probability = 1 / (1 + np.exp(-np.clip(logit + noise, -30, 30)))
    result["synthetic_questionnaire_score"] = probability * 100
    result["synthetic_risk_label"] = (probability >= 0.5).astype("int8")
    result["synthetic_effect_scale"] = effect_scale
    return result
