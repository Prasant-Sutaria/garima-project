"""Learns time-of-day usage patterns from synthetic hourly usage.

Three views are produced: a descriptive profile per profession; unsupervised KMeans clusters
over each participant's weekday and weekend hourly shares (profession is not used, and the
adjusted Rand index reports how closely clusters line up with it); and a cross-validated
classifier showing how well hourly rhythm alone predicts profession.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    adjusted_rand_score,
    balanced_accuracy_score,
    confusion_matrix,
    silhouette_score,
)
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

MIN_PARTICIPANTS = 30
MIN_GROUP_SIZE = 5
DAYPARTS = {
    "morning": (6, 11),
    "afternoon": (12, 16),
    "evening": (17, 20),
    "night": (21, 23),
    "late_night": (0, 5),
}
DAY_TYPES = ("weekday", "weekend")
ADDICTION_LEVELS = ("pre", "mild", "high")


def _with_day_type(hourly: pd.DataFrame) -> pd.DataFrame:
    frame = hourly.copy()
    frame["usage_date"] = pd.to_datetime(frame["usage_date"])
    frame["day_type"] = np.where(frame["usage_date"].dt.day_of_week >= 5, "weekend", "weekday")
    return frame


def build_participant_profiles(hourly: pd.DataFrame) -> pd.DataFrame:
    """48 columns per participant: mean share of each day's screen time per hour, weekday then
    weekend. Days with no screen time are skipped."""
    frame = _with_day_type(hourly)
    day_totals = frame.groupby(["synthetic_id", "usage_date"])["screen_minutes"].transform("sum")
    frame = frame[day_totals > 0].copy()
    frame["share"] = frame["screen_minutes"] / day_totals[day_totals > 0]
    profiles = frame.pivot_table(
        index="synthetic_id", columns=["day_type", "hour"], values="share", aggfunc="mean"
    )
    columns = pd.MultiIndex.from_product([DAY_TYPES, range(24)], names=["day_type", "hour"])
    return profiles.reindex(columns=columns).fillna(0.0)


def _daypart_shares(profile: np.ndarray) -> dict[str, float]:
    return {
        name: float(profile[start : end + 1].sum()) for name, (start, end) in DAYPARTS.items()
    }


def _combined(weekday: np.ndarray, weekend: np.ndarray) -> np.ndarray:
    return (5 * weekday + 2 * weekend) / 7


def _group_summaries(
    frame: pd.DataFrame,
    profiles: pd.DataFrame,
    groups: pd.Series,
    key: str,
    order: list[str],
) -> list[dict[str, object]]:
    daily = frame.groupby(["synthetic_id", "usage_date", "day_type"], as_index=False)[
        ["screen_minutes", "unlock_count"]
    ].sum()
    daily["group"] = daily["synthetic_id"].map(groups)
    summaries = []
    for group in order:
        members = groups.index[groups == group]
        if len(members) == 0:
            continue
        visible = len(members) >= MIN_GROUP_SIZE
        weekday = profiles.loc[members, "weekday"].to_numpy().mean(axis=0)
        weekend = profiles.loc[members, "weekend"].to_numpy().mean(axis=0)
        combined = _combined(weekday, weekend)
        shares = _daypart_shares(combined)
        group_days = daily[daily["group"] == group].groupby("day_type")
        summaries.append(
            {
                key: group,
                "participants": int(len(members)),
                "mean_daily_hours": (
                    {
                        day_type: float(value) / 60
                        for day_type, value in group_days["screen_minutes"].mean().items()
                    }
                    if visible
                    else None
                ),
                "mean_daily_unlocks": (
                    {
                        day_type: float(value)
                        for day_type, value in group_days["unlock_count"].mean().items()
                    }
                    if visible
                    else None
                ),
                "weekday_hourly_share": [float(v) for v in weekday] if visible else None,
                "weekend_hourly_share": [float(v) for v in weekend] if visible else None,
                "peak_hour_weekday": int(np.argmax(weekday)) if visible else None,
                "peak_hour_weekend": int(np.argmax(weekend)) if visible else None,
                "daypart_shares": shares if visible else None,
            }
        )
    return summaries


def _profession_classifier(
    profiles: pd.DataFrame, professions: pd.Series, seed: int
) -> dict[str, object]:
    """Cross-validated check of how well hourly rhythm alone predicts profession."""
    x = profiles.to_numpy()
    y = professions.to_numpy()
    classes = sorted(professions.unique())
    folds = int(min(5, professions.value_counts().min()))
    if folds < 2:
        return {"status": "insufficient_data"}

    def pipeline():
        return make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=2000))

    predicted = cross_val_predict(
        pipeline(), x, y, cv=StratifiedKFold(folds, shuffle=True, random_state=seed)
    )
    final = pipeline().fit(x, y)
    coefficients = final[-1].coef_
    if len(final[-1].classes_) == 2:
        coefficients = np.vstack([-coefficients[0], coefficients[0]])
    feature_names = [f"{day_type} {hour:02d}:00" for day_type, hour in profiles.columns]
    distinguishing = {
        str(label): [feature_names[i] for i in np.argsort(row)[::-1][:3]]
        for label, row in zip(final[-1].classes_, coefficients, strict=True)
    }
    return {
        "status": "ok",
        "type": "LogisticRegression on standardised 48 hourly shares",
        "validation": f"StratifiedKFold({folds}), participant-level",
        "balanced_accuracy": float(balanced_accuracy_score(y, predicted)),
        "majority_baseline_accuracy": float(professions.value_counts(normalize=True).max()),
        "classes": classes,
        "confusion_matrix": confusion_matrix(y, predicted, labels=classes).tolist(),
        "most_distinguishing_hours": distinguishing,
    }


def _hourly_heatmap(frame: pd.DataFrame, rows: pd.Series, order: list[str]) -> dict[str, object]:
    """Mean screen minutes per participant-day for each (row group, hour); groups with fewer
    than MIN_GROUP_SIZE participants are suppressed."""
    labelled = frame.assign(row=frame["synthetic_id"].map(rows))
    means = labelled.groupby(["row", "hour"])["screen_minutes"].mean().unstack("hour")
    sizes = labelled.groupby("row")["synthetic_id"].nunique()
    present = [row for row in order if row in means.index]
    return {
        "rows": present,
        "columns": list(range(24)),
        "unit": "minutes per participant-day",
        "values": [
            [float(v) for v in means.loc[row].reindex(range(24), fill_value=0.0)]
            if sizes[row] >= MIN_GROUP_SIZE
            else None
            for row in present
        ],
    }


def _heatmaps(
    frame: pd.DataFrame, participants: pd.DataFrame | None
) -> dict[str, dict[str, object]]:
    weekday_names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    day_names = dict(enumerate(weekday_names))
    weekday = frame.assign(row=frame["usage_date"].dt.day_of_week.map(day_names))
    means = weekday.groupby(["row", "hour"])["screen_minutes"].mean().unstack("hour")
    heatmaps: dict[str, dict[str, object]] = {
        "hour_by_weekday": {
            "rows": weekday_names,
            "columns": list(range(24)),
            "unit": "minutes per participant-day",
            "values": [
                [float(v) for v in means.loc[name].reindex(range(24), fill_value=0.0)]
                for name in weekday_names
            ],
        }
    }
    if participants is None:
        return heatmaps
    indexed = participants.set_index("synthetic_id")
    for key, column, order in (
        ("hour_by_profession", "profession", sorted(indexed["profession"].unique())),
        ("hour_by_addiction_level", "addiction_level", list(ADDICTION_LEVELS)),
        ("hour_by_age_band", "age_band", ["18-24", "25-34", "35-44", "45+"]),
    ):
        if column in indexed.columns:
            heatmaps[key] = _hourly_heatmap(frame, indexed[column], order)

    if {"profession", "addiction_level"} <= set(indexed.columns):
        daily = frame.groupby(["synthetic_id", "usage_date"])["screen_minutes"].sum().reset_index()
        daily = daily.join(indexed[["profession", "addiction_level"]], on="synthetic_id")
        hours = daily.groupby(["profession", "addiction_level"])["screen_minutes"].mean() / 60
        sizes = daily.groupby(["profession", "addiction_level"])["synthetic_id"].nunique()
        professions = sorted(indexed["profession"].unique())
        heatmaps["profession_by_addiction_level"] = {
            "rows": professions,
            "columns": list(ADDICTION_LEVELS),
            "unit": "hours per day",
            "values": [
                [
                    float(hours[(p, level)])
                    if sizes.get((p, level), 0) >= MIN_GROUP_SIZE
                    else None
                    for level in ADDICTION_LEVELS
                ]
                for p in professions
            ],
        }
    return heatmaps


def learn_time_of_day_patterns(
    hourly: pd.DataFrame,
    participants: pd.DataFrame | None = None,
    k_range: range = range(2, 7),
    seed: int = 20261124,
) -> dict[str, object]:
    """Summarise hourly rhythms per profession and addiction level, and cluster participants
    by rhythm.

    `participants` (synthetic_id, profession, optional addiction_level) is optional; without
    it only the clusters are reported. Neither label is used to form the clusters.
    """
    if set(hourly["source"].unique()) != {"synthetic"}:
        raise ValueError("time-of-day model accepts only source=synthetic rows")

    frame = _with_day_type(hourly)
    profiles = build_participant_profiles(frame)
    if len(profiles) < MIN_PARTICIPANTS:
        raise ValueError(f"at least {MIN_PARTICIPANTS} participants are required")
    matrix = profiles.to_numpy()

    best_score, best_k, best_model = -1.0, k_range.start, None
    for k in k_range:
        model = KMeans(n_clusters=k, n_init=10, random_state=seed).fit(matrix)
        score = float(silhouette_score(matrix, model.labels_))
        if score > best_score:
            best_score, best_k, best_model = score, k, model
    if best_model is None:
        raise ValueError("k_range must not be empty")
    labels = best_model.labels_

    professions = None
    addiction = None
    if participants is not None:
        indexed = participants.set_index("synthetic_id").reindex(profiles.index)
        professions = indexed["profession"]
        if "addiction_level" in indexed.columns:
            addiction = indexed["addiction_level"]

    clusters = []
    used_names: set[str] = set()
    for cluster_id in range(best_k):
        member_mask = labels == cluster_id
        weekday = matrix[member_mask, :24].mean(axis=0)
        weekend = matrix[member_mask, 24:].mean(axis=0)
        combined = _combined(weekday, weekend)
        shares = _daypart_shares(combined)
        peak = int(np.argmax(combined))
        name = f"{max(shares, key=shares.get).replace('_', '-').capitalize()}-leaning"
        if name in used_names:
            name = f"{name}, peak {peak:02d}:00"
        used_names.add(name)
        count = int(member_mask.sum())
        visible = count >= MIN_GROUP_SIZE
        cluster: dict[str, object] = {
            "cluster_id": int(cluster_id),
            "label": name,
            "participants": count,
            "peak_hour": peak if visible else None,
            "daypart_shares": shares if visible else None,
            "weekday_hourly_share": [float(v) for v in weekday] if visible else None,
            "weekend_hourly_share": [float(v) for v in weekend] if visible else None,
        }
        if professions is not None:
            cluster["profession_mix"] = (
                {k: int(v) for k, v in professions[member_mask].value_counts().items()}
                if visible
                else None
            )
        if addiction is not None:
            cluster["addiction_mix"] = (
                {k: int(v) for k, v in addiction[member_mask].value_counts().items()}
                if visible
                else None
            )
        clusters.append(cluster)
    clusters.sort(key=lambda c: -c["participants"])

    overall = _combined(matrix[:, :24].mean(axis=0), matrix[:, 24:].mean(axis=0))
    result: dict[str, object] = {
        "source": "synthetic",
        "status": "ok",
        "participants": int(len(profiles)),
        "date_range": [
            frame["usage_date"].min().date().isoformat(),
            frame["usage_date"].max().date().isoformat(),
        ],
        "dayparts": {name: list(bounds) for name, bounds in DAYPARTS.items()},
        "overall_hourly_share": [float(v) for v in overall],
        "overall_daypart_shares": _daypart_shares(overall),
        "chosen_clusters": int(best_k),
        "silhouette_score": best_score,
        "clusters": clusters,
        "heatmaps": _heatmaps(frame, participants),
        "model": {
            "type": "KMeans",
            "features": "48 hourly shares of daily screen time (24 weekday + 24 weekend)",
            "k_selection": f"silhouette over k={k_range.start}..{k_range.stop - 1}",
            "uses_profession": False,
        },
    }
    if professions is not None:
        result["professions"] = _group_summaries(
            frame, profiles, professions, "profession", sorted(professions.unique())
        )
        result["profession_recovery_adjusted_rand"] = float(
            adjusted_rand_score(professions.to_numpy(), labels)
        )
        result["profession_classifier"] = _profession_classifier(profiles, professions, seed)
    if addiction is not None:
        result["addiction_levels"] = _group_summaries(
            frame, profiles, addiction, "addiction_level", list(ADDICTION_LEVELS)
        )
        if professions is not None:
            counts = pd.crosstab(professions, addiction)
            result["profession_by_addiction_level"] = {
                str(profession): {
                    level: int(counts.loc[profession].get(level, 0)) for level in ADDICTION_LEVELS
                }
                for profession in counts.index
            }
    return result
