"""Feature extraction for the synthetic daily-snapshot format."""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

SOURCE = "synthetic"
FORBIDDEN_COLUMNS = {"registration_id", "user_id", "payload_enc"}
NUMERIC_FEATURES = (
    "mean_daily_screen_on_seconds",
    "mean_daily_unlock_count",
    "mean_daily_launch_count",
    "mean_session_seconds",
    "top_app_share",
    "weekend_usage_shift",
    "usage_trend_per_day",
)
CATEGORICAL_FEATURES = ("age_band", "declared_interest")
FEATURE_COLUMNS = (*NUMERIC_FEATURES, *CATEGORICAL_FEATURES)
DRIVER_LABELS = {
    "mean_daily_screen_on_seconds": "average daily screen-on time",
    "mean_daily_unlock_count": "average daily unlock count",
    "mean_daily_launch_count": "average daily app launches",
    "mean_session_seconds": "average app foreground time per launch",
    "top_app_share": "share of foreground time in the most-used app",
    "weekend_usage_shift": "difference between weekend and weekday use",
    "usage_trend_per_day": "change in foreground use over the period",
    "age_band": "age band",
    "declared_interest": "declared interest",
}


def build_feature_table(daily_usage: pd.DataFrame) -> pd.DataFrame:
    """Aggregate synthetic daily snapshots into one row per participant-period."""
    if not isinstance(daily_usage, pd.DataFrame) or daily_usage.empty:
        raise ValueError("daily_usage must be a non-empty dataframe")
    forbidden = FORBIDDEN_COLUMNS.intersection(daily_usage.columns)
    if forbidden:
        raise ValueError(f"forbidden identifier or payload columns: {sorted(forbidden)}")
    if "source" not in daily_usage or set(daily_usage["source"].unique()) != {SOURCE}:
        raise ValueError("only source=synthetic data is accepted")
    if "synthetic_id" not in daily_usage or not daily_usage["synthetic_id"].map(
        lambda value: isinstance(value, str) and value.startswith("synthetic-")
    ).all():
        raise ValueError("synthetic_id values must use the synthetic- prefix")

    required = {
        "synthetic_id",
        "period",
        "date",
        "enrollment_date",
        "age_band",
        "declared_interest",
        "screen_on_seconds",
        "unlock_count",
        "apps_json",
    }
    missing = required.difference(daily_usage.columns)
    if missing:
        raise ValueError(f"daily_usage is missing columns: {sorted(missing)}")

    data = daily_usage.copy()
    data["date"] = pd.to_datetime(data["date"], errors="raise")
    data["enrollment_date"] = pd.to_datetime(data["enrollment_date"], errors="raise")
    for column in ("screen_on_seconds", "unlock_count", "period"):
        data[column] = pd.to_numeric(data[column], errors="raise")
        if (data[column] < 0).any():
            raise ValueError(f"{column} values must be non-negative")

    records: list[dict[str, object]] = []
    for (synthetic_id, period), group in data.groupby(
        ["synthetic_id", "period"], sort=True, observed=True
    ):
        if group["age_band"].nunique() != 1 or group["declared_interest"].nunique() != 1:
            raise ValueError("age_band and declared_interest must be constant per participant")
        days: list[dict[str, object]] = []
        app_totals: dict[str, int] = {}
        total_launches = 0
        for row in group.sort_values("date").itertuples(index=False):
            try:
                apps = json.loads(row.apps_json)
            except (TypeError, json.JSONDecodeError) as error:
                raise ValueError("apps_json must be valid JSON") from error
            if not isinstance(apps, list):
                raise ValueError("apps_json must contain a list")
            day_foreground = 0
            day_launches = 0
            for app in apps:
                if not isinstance(app, dict) or set(app) != {
                    "package_name",
                    "foreground_seconds",
                    "launch_count",
                }:
                    raise ValueError("each app must contain only its package and usage totals")
                seconds = int(app["foreground_seconds"])
                launches = int(app["launch_count"])
                if seconds < 0 or launches < 0:
                    raise ValueError("app usage totals must be non-negative")
                day_foreground += seconds
                day_launches += launches
                app_totals[app["package_name"]] = (
                    app_totals.get(app["package_name"], 0) + seconds
                )
            total_launches += day_launches
            days.append(
                {
                    "date": row.date,
                    "screen_on_seconds": int(row.screen_on_seconds),
                    "unlock_count": int(row.unlock_count),
                    "foreground_seconds": day_foreground,
                    "launch_count": day_launches,
                }
            )

        day_table = pd.DataFrame(days).sort_values("date")
        mean_foreground = float(day_table["foreground_seconds"].mean())
        weekday_mean = float(
            day_table.loc[day_table["date"].dt.day_of_week < 5, "foreground_seconds"].mean()
        )
        weekend_mean = float(
            day_table.loc[day_table["date"].dt.day_of_week >= 5, "foreground_seconds"].mean()
        )
        if not np.isfinite(weekday_mean):
            weekday_mean = mean_foreground
        if not np.isfinite(weekend_mean):
            weekend_mean = mean_foreground
        day_positions = np.arange(len(day_table), dtype=float)
        trend_per_day = float(
            np.polyfit(day_positions, day_table["foreground_seconds"].to_numpy(), 1)[0]
        )
        total_foreground = int(day_table["foreground_seconds"].sum())
        records.append(
            {
                "source": SOURCE,
                "synthetic_id": synthetic_id,
                "period": int(period),
                "enrollment_date": group["enrollment_date"].iloc[0].date().isoformat(),
                "period_end": day_table["date"].max().date().isoformat(),
                "mean_daily_screen_on_seconds": float(day_table["screen_on_seconds"].mean()),
                "mean_daily_unlock_count": float(day_table["unlock_count"].mean()),
                "mean_daily_launch_count": float(day_table["launch_count"].mean()),
                "mean_session_seconds": (
                    total_foreground / total_launches if total_launches else 0.0
                ),
                "top_app_share": (
                    max(app_totals.values()) / sum(app_totals.values())
                    if sum(app_totals.values())
                    else 0.0
                ),
                "weekend_usage_shift": (
                    (weekend_mean - weekday_mean) / mean_foreground
                    if mean_foreground
                    else 0.0
                ),
                "usage_trend_per_day": trend_per_day,
                "age_band": group["age_band"].iloc[0],
                "declared_interest": group["declared_interest"].iloc[0],
            }
        )
    return pd.DataFrame.from_records(records)
