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


def _profession_summaries(
    frame: pd.DataFrame, profiles: pd.DataFrame, professions: pd.Series
) -> list[dict[str, object]]:
    daily = frame.groupby(["synthetic_id", "usage_date", "day_type"], as_index=False)[
        "screen_minutes"
    ].sum()
    daily["profession"] = daily["synthetic_id"].map(professions)
    summaries = []
    for profession in sorted(professions.unique()):
        members = professions.index[professions == profession]
        visible = len(members) >= MIN_GROUP_SIZE
        weekday = profiles.loc[members, "weekday"].to_numpy().mean(axis=0)
        weekend = profiles.loc[members, "weekend"].to_numpy().mean(axis=0)
        combined = _combined(weekday, weekend)
        shares = _daypart_shares(combined)
        minutes = daily[daily["profession"] == profession].groupby("day_type")["screen_minutes"]
        summaries.append(
            {
                "profession": profession,
                "participants": int(len(members)),
                "mean_daily_hours": (
                    {day_type: float(value) / 60 for day_type, value in minutes.mean().items()}
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


def learn_time_of_day_patterns(
    hourly: pd.DataFrame,
    participants: pd.DataFrame | None = None,
    k_range: range = range(2, 7),
    seed: int = 20261124,
) -> dict[str, object]:
    """Summarise hourly rhythms per profession and cluster participants by rhythm.

    `participants` (synthetic_id, profession) is optional; without it only the clusters are
    reported.
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
    if participants is not None:
        professions = participants.set_index("synthetic_id")["profession"].reindex(profiles.index)

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
        "model": {
            "type": "KMeans",
            "features": "48 hourly shares of daily screen time (24 weekday + 24 weekend)",
            "k_selection": f"silhouette over k={k_range.start}..{k_range.stop - 1}",
            "uses_profession": False,
        },
    }
    if professions is not None:
        result["professions"] = _profession_summaries(frame, profiles, professions)
        result["profession_recovery_adjusted_rand"] = float(
            adjusted_rand_score(professions.to_numpy(), labels)
        )
        result["profession_classifier"] = _profession_classifier(profiles, professions, seed)
    return result
