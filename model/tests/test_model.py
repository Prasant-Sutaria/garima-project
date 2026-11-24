from __future__ import annotations

import pandas as pd
import pytest

from model_component.features import FEATURE_COLUMNS, build_feature_table
from model_component.synthetic import (
    add_synthetic_questionnaire_label,
    generate_daily_usage,
)
from model_component.training import _time_holdout


def test_synthetic_generation_and_features_are_deterministic() -> None:
    first = generate_daily_usage(participants=60, seed=7)
    second = generate_daily_usage(participants=60, seed=7)

    pd.testing.assert_frame_equal(first.daily_usage, second.daily_usage)
    assert set(first.daily_usage["source"]) == {"synthetic"}
    assert first.daily_usage["synthetic_id"].nunique() == 60

    features = build_feature_table(first.daily_usage)
    assert len(features) == 120
    assert set(FEATURE_COLUMNS).issubset(features.columns)
    assert not {"gender", "registration_id", "user_id", "payload_enc"}.intersection(
        features.columns
    )
    assert features["age_band"].notna().all()
    assert features["declared_interest"].notna().all()


def test_feature_builder_rejects_real_or_linkable_inputs() -> None:
    daily = generate_daily_usage(participants=60).daily_usage

    real_source = daily.copy()
    real_source.loc[0, "source"] = "real"
    with pytest.raises(ValueError, match="source=synthetic"):
        build_feature_table(real_source)

    linkable = daily.assign(registration_id="P-123456")
    with pytest.raises(ValueError, match="forbidden"):
        build_feature_table(linkable)


def test_synthetic_label_and_time_holdout_are_explicit_and_grouped() -> None:
    daily = generate_daily_usage(participants=100, seed=9).daily_usage
    features = build_feature_table(daily)
    labeled = add_synthetic_questionnaire_label(features, seed=11)

    assert set(labeled["source"]) == {"synthetic"}
    assert labeled["synthetic_questionnaire_score"].between(0, 100).all()
    assert set(labeled["synthetic_risk_label"].unique()).issubset({0, 1})
    assert set(labeled["synthetic_effect_scale"]) == {1.0}
    assert (labeled.groupby("synthetic_id").size() == 2).all()

    training, holdout, cutoff = _time_holdout(labeled, holdout_fraction=0.2)
    assert set(training["synthetic_id"]).isdisjoint(holdout["synthetic_id"])
    assert training["period_end"].max() < cutoff
    assert holdout["enrollment_date"].min() == cutoff


def test_feature_builder_rejects_inconsistent_participant_metadata() -> None:
    daily = generate_daily_usage(participants=60).daily_usage
    participant_id = daily.loc[0, "synthetic_id"]
    row_index = daily.index[daily["synthetic_id"] == participant_id][0]
    daily.loc[row_index, "age_band"] = "inconsistent"

    with pytest.raises(ValueError, match="constant per participant"):
        build_feature_table(daily)
