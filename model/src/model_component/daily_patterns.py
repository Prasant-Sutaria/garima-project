"""Learns weekday and recent-history patterns of daily screen-on time from real rows.

Real snapshots hold one total per day, so this model only covers day-level patterns.
Time-of-day patterns are handled separately in time_of_day.py.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_absolute_error
from sklearn.model_selection import GroupKFold

MIN_PARTICIPANTS = 10
MIN_PARTICIPANT_DAYS = 50
MIN_GROUP_SIZE = 5
FEATURES = ["weekday", "is_weekend", "previous_day_hours", "trailing_7day_mean_hours"]
WEEKDAY_NAMES = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")


def build_daily_features(daily: pd.DataFrame) -> pd.DataFrame:
    """Add calendar and history features. Uses only earlier days, never the same day."""
    frame = daily.sort_values(["participant_index", "usage_date"]).copy()
    frame["hours"] = frame["screen_on_seconds"] / 3600
    grouped = frame.groupby("participant_index")["hours"]
    frame["previous_day_hours"] = grouped.shift(1)
    frame["trailing_7day_mean_hours"] = grouped.transform(
        lambda hours: hours.shift(1).rolling(7, min_periods=1).mean()
    )
    frame["weekday"] = frame["usage_date"].dt.day_of_week
    frame["is_weekend"] = (frame["weekday"] >= 5).astype(int)
    return frame


def _regressor(seed: int) -> HistGradientBoostingRegressor:
    return HistGradientBoostingRegressor(
        max_iter=200,
        learning_rate=0.05,
        max_leaf_nodes=15,
        min_samples_leaf=30,
        random_state=seed,
    )


def train_daily_pattern_model(daily: pd.DataFrame, seed: int = 20261124) -> dict[str, object]:
    """Fit the model on real daily rows and return a JSON-ready summary.

    Small groups are suppressed: weekday or date summaries with fewer than MIN_GROUP_SIZE
    participants are reported as null.
    """
    if set(daily["source"].unique()) != {"real"}:
        raise ValueError("daily pattern model accepts only source=real rows")

    all_days = build_daily_features(daily)
    features = all_days.dropna(subset=["previous_day_hours"])
    participants = int(features["participant_index"].nunique())
    if participants < MIN_PARTICIPANTS or len(features) < MIN_PARTICIPANT_DAYS:
        return {
            "source": "real",
            "status": "insufficient_data",
            "participants": participants,
            "participant_days": int(len(features)),
            "minimum_participants": MIN_PARTICIPANTS,
            "minimum_participant_days": MIN_PARTICIPANT_DAYS,
        }

    x = features[FEATURES].to_numpy(dtype=float)
    y = features["hours"].to_numpy(dtype=float)
    groups = features["participant_index"].to_numpy()
    n_splits = min(5, participants)

    predicted = np.empty_like(y)
    for train_index, test_index in GroupKFold(n_splits=n_splits).split(x, y, groups):
        model = _regressor(seed).fit(x[train_index], y[train_index])
        predicted[test_index] = model.predict(x[test_index])

    metrics = {
        "model_mae_hours": float(mean_absolute_error(y, predicted)),
        "baseline_previous_day_mae_hours": float(
            mean_absolute_error(y, features["previous_day_hours"])
        ),
        "baseline_trailing_7day_mae_hours": float(
            mean_absolute_error(y, features["trailing_7day_mean_hours"])
        ),
    }

    final_model = _regressor(seed).fit(x, y)
    weekday_effect: list[float] = []
    for weekday in range(7):
        counterfactual = features[FEATURES].copy()
        counterfactual["weekday"] = weekday
        counterfactual["is_weekend"] = int(weekday >= 5)
        weekday_effect.append(
            float(final_model.predict(counterfactual.to_numpy(dtype=float)).mean())
        )

    observed = features.groupby("weekday").agg(
        mean_hours=("hours", "mean"),
        participants=("participant_index", "nunique"),
    )
    weekday_profile = []
    for weekday in range(7):
        row = observed.loc[weekday] if weekday in observed.index else None
        visible = row is not None and int(row["participants"]) >= MIN_GROUP_SIZE
        weekday_profile.append(
            {
                "weekday": WEEKDAY_NAMES[weekday],
                "observed_mean_hours": float(row["mean_hours"]) if visible else None,
                "observed_participants": int(row["participants"]) if visible else None,
                "model_effect_hours": weekday_effect[weekday],
            }
        )

    trend = all_days.groupby("usage_date").agg(
        mean_hours=("hours", "mean"),
        participants=("participant_index", "nunique"),
    )
    daily_trend = [
        {
            "date": day.date().isoformat(),
            "mean_hours": float(row["mean_hours"]) if row["participants"] >= MIN_GROUP_SIZE else None,
            "participants": int(row["participants"]),
        }
        for day, row in trend.iterrows()
    ]

    return {
        "source": "real",
        "status": "ok",
        "participants": participants,
        "participant_days": int(len(features)),
        "date_range": [
            all_days["usage_date"].min().date().isoformat(),
            all_days["usage_date"].max().date().isoformat(),
        ],
        "model": {
            "type": "HistGradientBoostingRegressor",
            "features": FEATURES,
            "target": "screen_on_hours",
            "validation": f"GroupKFold({n_splits}) by participant",
            "same_day_usage_used_as_feature": False,
        },
        "metrics": metrics,
        "weekend_minus_weekday_hours": float(np.mean(weekday_effect[5:]) - np.mean(weekday_effect[:5])),
        "weekday_profile": weekday_profile,
        "daily_trend": daily_trend,
    }
