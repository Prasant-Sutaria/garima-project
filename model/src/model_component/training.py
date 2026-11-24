"""Grouped model training and synthetic-only validation."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable

import joblib
import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss
from sklearn.model_selection import GridSearchCV, GroupKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

from model_component import FEATURE_VERSION, MODEL_VERSION
from model_component.features import (
    CATEGORICAL_FEATURES,
    DRIVER_LABELS,
    FEATURE_COLUMNS,
    FORBIDDEN_COLUMNS,
    NUMERIC_FEATURES,
)

SOURCE = "synthetic"
LABEL_COLUMN = "synthetic_risk_label"


def _validate_training_rows(rows: pd.DataFrame) -> None:
    if rows.empty:
        raise ValueError("training rows must not be empty")
    forbidden = FORBIDDEN_COLUMNS.intersection(rows.columns)
    if forbidden:
        raise ValueError(f"forbidden identifier or payload columns: {sorted(forbidden)}")
    if "source" not in rows or set(rows["source"].unique()) != {SOURCE}:
        raise ValueError("training accepts only source=synthetic rows")
    if "synthetic_id" not in rows or not rows["synthetic_id"].map(
        lambda value: isinstance(value, str) and value.startswith("synthetic-")
    ).all():
        raise ValueError("training requires synthetic- participant IDs")
    if set(FEATURE_COLUMNS).difference(rows.columns) or LABEL_COLUMN not in rows:
        raise ValueError("training rows do not match the feature and label schema")
    if "synthetic_effect_scale" not in rows or rows["synthetic_effect_scale"].nunique() != 1:
        raise ValueError("training rows must declare exactly one synthetic effect scale")
    if not np.isfinite(pd.to_numeric(rows["synthetic_effect_scale"], errors="raise")).all():
        raise ValueError("synthetic effect scale must be finite")
    if not set(rows[LABEL_COLUMN].unique()).issubset({0, 1}):
        raise ValueError("synthetic_risk_label must contain only 0 and 1")
    if rows["synthetic_id"].isna().any():
        raise ValueError("synthetic_id must not be null")


def _pipeline(model_name: str, seed: int) -> Pipeline:
    preprocessor = ColumnTransformer(
        transformers=[
            ("numeric", "passthrough", list(NUMERIC_FEATURES)),
            (
                "categorical",
                OneHotEncoder(handle_unknown="ignore"),
                list(CATEGORICAL_FEATURES),
            ),
        ],
        remainder="drop",
    )
    if model_name == "logistic_regression":
        classifier = LogisticRegression(
            class_weight="balanced",
            max_iter=1500,
            random_state=seed,
            solver="liblinear",
        )
    elif model_name == "lightgbm":
        classifier = LGBMClassifier(
            class_weight="balanced",
            random_state=seed,
            verbosity=-1,
            n_jobs=1,
        )
    else:
        raise ValueError(f"unsupported model: {model_name}")
    return Pipeline([("preprocessor", preprocessor), ("classifier", classifier)])


def _parameter_grid(model_name: str) -> dict[str, list[Any]]:
    if model_name == "logistic_regression":
        return {"classifier__C": [0.1, 1.0, 10.0]}
    return {
        "classifier__n_estimators": [100, 200],
        "classifier__num_leaves": [7, 15],
    }


def _metrics(labels: pd.Series | np.ndarray, probabilities: np.ndarray) -> dict[str, float]:
    labels_array = np.asarray(labels, dtype=int)
    if np.unique(labels_array).size < 2:
        pr_auc: float | None = None
    else:
        pr_auc = float(average_precision_score(labels_array, probabilities))
    return {
        "pr_auc": pr_auc,
        "brier_score": float(brier_score_loss(labels_array, probabilities)),
    }


def _grouped_nested_cv(
    rows: pd.DataFrame,
    model_name: str,
    seed: int,
    outer_folds: int = 3,
    inner_folds: int = 3,
) -> tuple[dict[str, Any], np.ndarray]:
    features = rows.loc[:, FEATURE_COLUMNS]
    labels = rows[LABEL_COLUMN].astype(int).to_numpy()
    groups = rows["synthetic_id"].to_numpy()
    unique_groups = np.unique(groups)
    if len(unique_groups) < max(outer_folds, inner_folds) + 1:
        raise ValueError("not enough participant groups for grouped cross-validation")

    probabilities = np.full(len(rows), np.nan, dtype=float)
    fold_parameters: list[dict[str, Any]] = []
    outer = GroupKFold(n_splits=outer_folds)
    for fold_number, (train_indices, validation_indices) in enumerate(
        outer.split(features, labels, groups)
    ):
        fold_groups = groups[train_indices]
        search = GridSearchCV(
            _pipeline(model_name, seed + fold_number),
            _parameter_grid(model_name),
            scoring="average_precision",
            cv=GroupKFold(n_splits=inner_folds),
            n_jobs=1,
            refit=True,
        )
        search.fit(
            features.iloc[train_indices],
            labels[train_indices],
            groups=fold_groups,
        )
        probabilities[validation_indices] = search.best_estimator_.predict_proba(
            features.iloc[validation_indices]
        )[:, 1]
        fold_parameters.append(
            {
                "fold": fold_number + 1,
                "best_parameters": search.best_params_,
                "train_participants": int(np.unique(fold_groups).size),
                "validation_participants": int(np.unique(groups[validation_indices]).size),
            }
        )

    if np.isnan(probabilities).any():
        raise RuntimeError("grouped cross-validation did not predict every training row")
    return {
        "metrics": _metrics(labels, probabilities),
        "folds": fold_parameters,
    }, probabilities


def _fit_final_model(
    rows: pd.DataFrame,
    model_name: str,
    seed: int,
) -> tuple[Pipeline, dict[str, Any]]:
    features = rows.loc[:, FEATURE_COLUMNS]
    labels = rows[LABEL_COLUMN].astype(int).to_numpy()
    groups = rows["synthetic_id"].to_numpy()
    search = GridSearchCV(
        _pipeline(model_name, seed),
        _parameter_grid(model_name),
        scoring="average_precision",
        cv=GroupKFold(n_splits=3),
        n_jobs=1,
        refit=True,
    )
    search.fit(features, labels, groups=groups)
    return search.best_estimator_, search.best_params_


def _fit_sigmoid_calibrator(
    uncalibrated_probabilities: np.ndarray,
    labels: pd.Series | np.ndarray,
) -> LogisticRegression:
    logits = np.log(
        np.clip(uncalibrated_probabilities, 1e-6, 1 - 1e-6)
        / np.clip(1 - uncalibrated_probabilities, 1e-6, 1)
    ).reshape(-1, 1)
    calibrator = LogisticRegression(C=1e6, solver="lbfgs", max_iter=1000)
    calibrator.fit(logits, np.asarray(labels, dtype=int))
    return calibrator


def _calibrated_probabilities(
    model: Pipeline,
    calibrator: LogisticRegression,
    rows: pd.DataFrame,
) -> np.ndarray:
    raw = model.predict_proba(rows.loc[:, FEATURE_COLUMNS])[:, 1]
    logits = np.log(np.clip(raw, 1e-6, 1 - 1e-6) / np.clip(1 - raw, 1e-6, 1))
    return calibrator.predict_proba(logits.reshape(-1, 1))[:, 1]


def _reliability(
    labels: pd.Series | np.ndarray,
    probabilities: np.ndarray,
    bins: int = 10,
) -> list[dict[str, float | int]]:
    table = pd.DataFrame(
        {"label": np.asarray(labels, dtype=int), "probability": probabilities}
    )
    table["bin"] = pd.cut(
        table["probability"],
        bins=np.linspace(0, 1, bins + 1),
        include_lowest=True,
        duplicates="drop",
    )
    reliability = []
    for _, group in table.groupby("bin", observed=True):
        reliability.append(
            {
                "count": int(len(group)),
                "mean_predicted_probability": float(group["probability"].mean()),
                "observed_positive_rate": float(group["label"].mean()),
            }
        )
    return reliability


def _age_band_metrics(rows: pd.DataFrame, probabilities: np.ndarray) -> dict[str, Any]:
    results: dict[str, Any] = {}
    for age_band in sorted(rows["age_band"].unique()):
        mask = rows["age_band"].to_numpy() == age_band
        results[str(age_band)] = {
            "participants": int(rows.loc[mask, "synthetic_id"].nunique()),
            "rows": int(mask.sum()),
            **_metrics(rows.loc[mask, LABEL_COLUMN], probabilities[mask]),
        }
    return results


def _raw_feature_name(encoded_name: str) -> str:
    name = encoded_name.split("__", maxsplit=1)[-1]
    for feature in sorted(FEATURE_COLUMNS, key=len, reverse=True):
        if name == feature or name.startswith(f"{feature}_"):
            return feature
    raise ValueError(f"could not map encoded feature to source field: {encoded_name}")


def _top_drivers(model: Pipeline, rows: pd.DataFrame) -> list[list[dict[str, Any]]]:
    preprocessor = model.named_steps["preprocessor"]
    classifier = model.named_steps["classifier"]
    encoded = preprocessor.transform(rows.loc[:, FEATURE_COLUMNS])
    encoded_names = preprocessor.get_feature_names_out()
    if isinstance(classifier, LGBMClassifier):
        contributions = classifier.booster_.predict(encoded, pred_contrib=True)[:, :-1]
    elif isinstance(classifier, LogisticRegression):
        coefficients = classifier.coef_[0]
        contributions = encoded.multiply(coefficients).toarray()
    else:
        raise ValueError("contributions are not implemented for this estimator")

    source_features = [_raw_feature_name(name) for name in encoded_names]
    grouped: dict[str, np.ndarray] = {}
    for feature in FEATURE_COLUMNS:
        indexes = [index for index, name in enumerate(source_features) if name == feature]
        if indexes:
            grouped[feature] = contributions[:, indexes].sum(axis=1)

    result = []
    for row_index in range(len(rows)):
        drivers = [
            {
                "feature": feature,
                "label": DRIVER_LABELS[feature],
                "direction": "risk_increasing" if values[row_index] >= 0 else "risk_reducing",
                "contribution_log_odds": float(values[row_index]),
            }
            for feature, values in grouped.items()
        ]
        drivers.sort(key=lambda item: abs(item["contribution_log_odds"]), reverse=True)
        result.append(drivers[:3])
    return result


def _time_holdout(
    rows: pd.DataFrame, holdout_fraction: float
) -> tuple[pd.DataFrame, pd.DataFrame, str]:
    if not 0 < holdout_fraction < 0.5:
        raise ValueError("holdout_fraction must be between 0 and 0.5")
    participant_dates = rows[["synthetic_id", "enrollment_date"]].drop_duplicates()
    ordered_dates = participant_dates["enrollment_date"].sort_values().reset_index(drop=True)
    cutoff_index = int(len(ordered_dates) * (1 - holdout_fraction))
    cutoff_index = min(max(cutoff_index, 1), len(ordered_dates) - 1)
    cutoff = str(ordered_dates.iloc[cutoff_index])
    training = rows.loc[rows["enrollment_date"] < cutoff].copy()
    holdout = rows.loc[rows["enrollment_date"] >= cutoff].copy()
    overlap = set(training["synthetic_id"]).intersection(holdout["synthetic_id"])
    if training.empty or holdout.empty or overlap:
        raise ValueError("time hold-out must have non-empty, participant-disjoint partitions")
    if training["period_end"].max() >= holdout["enrollment_date"].min():
        raise ValueError("hold-out cohort must start after the training time window")
    return training, holdout, cutoff


def train_and_evaluate(
    feature_rows: pd.DataFrame,
    output_dir: Path,
    holdout_fraction: float = 0.2,
    seed: int = 20261124,
) -> dict[str, Any]:
    """Train both models and write a versioned synthetic validation report."""
    _validate_training_rows(feature_rows)
    training_rows, holdout_rows, cutoff = _time_holdout(feature_rows, holdout_fraction)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_reports: dict[str, Any] = {}
    scored_holdout = holdout_rows.loc[
        :, ["source", "synthetic_id", "period", "period_end", LABEL_COLUMN]
    ].copy()

    for model_offset, model_name in enumerate(("logistic_regression", "lightgbm")):
        model_seed = seed + model_offset
        cv_report, oof_probabilities = _grouped_nested_cv(
            training_rows, model_name, model_seed
        )
        model, best_parameters = _fit_final_model(training_rows, model_name, model_seed)
        calibrator = _fit_sigmoid_calibrator(
            oof_probabilities, training_rows[LABEL_COLUMN].astype(int)
        )
        probabilities = _calibrated_probabilities(model, calibrator, holdout_rows)
        model_path = output_dir / f"{MODEL_VERSION}-{model_name}.joblib"
        joblib.dump(
            {
                "source": SOURCE,
                "model_version": MODEL_VERSION,
                "feature_version": FEATURE_VERSION,
                "model": model,
                "calibrator": calibrator,
            },
            model_path,
        )
        model_reports[model_name] = {
            "cross_validation": cv_report,
            "best_parameters": best_parameters,
            "time_holdout": {
                **_metrics(holdout_rows[LABEL_COLUMN], probabilities),
                "age_band": _age_band_metrics(holdout_rows, probabilities),
                "reliability": _reliability(holdout_rows[LABEL_COLUMN], probabilities),
            },
            "artifact": model_path.name,
        }
        scored_holdout[f"{model_name}_risk_score"] = probabilities
        if model_name == "lightgbm":
            scored_holdout["risk_drivers"] = _top_drivers(model, holdout_rows)

    timestamp = datetime.now(UTC).isoformat()
    report = {
        "source": SOURCE,
        "model_version": MODEL_VERSION,
        "feature_version": FEATURE_VERSION,
        "scored_at": timestamp,
        "data_cut": {
            "first_enrollment_date": str(feature_rows["enrollment_date"].min()),
            "last_period_end": str(feature_rows["period_end"].max()),
            "holdout_enrollment_cutoff": cutoff,
        },
        "synthetic_dataset": {
            "participants": int(feature_rows["synthetic_id"].nunique()),
            "participant_period_rows": int(len(feature_rows)),
            "training_participants": int(training_rows["synthetic_id"].nunique()),
            "holdout_participants": int(holdout_rows["synthetic_id"].nunique()),
            "holdout_fraction": holdout_fraction,
        },
        "target": {
            "name": LABEL_COLUMN,
            "positive_rows": int(feature_rows[LABEL_COLUMN].sum()),
            "warning": "No real questionnaire responses or validated risk thresholds were used.",
        },
        "synthetic_effect_scale": sorted(
            float(value) for value in feature_rows["synthetic_effect_scale"].unique()
        ),
        "gender_used_as_feature": False,
        "gender_subgroup_metrics": {
            "status": "not_available",
            "reason": "Synthetic gender categories are not defined and gender is not generated.",
        },
        "models": model_reports,
    }
    report_path = output_dir / "training_report.json"
    scores_path = output_dir / "synthetic_holdout_scores.csv"
    report_path.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    scored_holdout.to_csv(scores_path, index=False)
    return report


def run_sensitivity(
    daily_usage: pd.DataFrame,
    build_features: Callable[[pd.DataFrame], pd.DataFrame],
    output_dir: Path,
    seed: int = 20261124,
) -> dict[str, Any]:
    """Repeat the synthetic validation with weaker and stronger planted effects."""
    results: dict[str, Any] = {"source": SOURCE, "effect_scale": {}}
    for label, scale in (("weaker", 0.7), ("nominal", 1.0), ("stronger", 1.3)):
        features = build_features(daily_usage)
        from model_component.synthetic import add_synthetic_questionnaire_label

        labeled = add_synthetic_questionnaire_label(features, effect_scale=scale, seed=seed)
        run_dir = output_dir / label
        report = train_and_evaluate(labeled, run_dir, seed=seed)
        results["effect_scale"][label] = {
            "effect_scale": scale,
            "positive_rows": report["target"]["positive_rows"],
            "models": {
                name: model["time_holdout"]
                for name, model in report["models"].items()
            },
            "report": str((run_dir / "training_report.json").name),
        }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "sensitivity_report.json").write_text(
        json.dumps(results, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    return results
