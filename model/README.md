# Digital-use model component

This Python component is an **academic, synthetic-only pipeline validation**. It generates 2,000 synthetic participants with two 14-day periods, builds participant-period features, compares logistic regression with LightGBM, and reports grouped cross-validation and a later-cohort time hold-out. It is not a diagnostic tool, does not use real participant data, and its score and label must not be interpreted as clinical risk or a validated questionnaire result.

The assumptions and planted label rule are recorded in [assumptions.json](./assumptions.json). The generated `synthetic_questionnaire_score` is a noisy rule-based proxy, not a real questionnaire. Because the label is deliberately derived from usage features, model performance only confirms that the implementation can recover a planted relationship; it does not demonstrate predictive validity or effectiveness. `gender` is excluded and no gender subgroup claim is made because synthetic gender categories have not been defined. Synthetic subgroup metrics by age band are reported only as a pipeline check.

## Requirements and local validation

Use Python 3.11–3.14. From this directory:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e ".[dev]"
python scripts\generate_synthetic.py
python scripts\train_model.py
python scripts\export_openapi.py
pytest
```

The default outputs are written under ignored `artifacts\synthetic\`. To run sensitivity checks with weaker and stronger planted effects:

```powershell
python scripts\train_model.py --sensitivity
```

All generated dataset rows and reports include `source = synthetic`. The pipeline rejects non-synthetic sources and inputs containing `registration_id`, `user_id`, or `payload_enc`. Synthetic and real records must never be combined. Gender, exact ages, identifiers, and payloads are not generated.

## Feature availability

Features use fields already represented by daily snapshots: screen-on seconds, unlock count, per-app foreground seconds, and app launch count. Unlocks and launches are documented proxies, not literal pickups or session durations. Night-time-use share cannot be computed from the current daily aggregate contract and is deliberately omitted. `age_band` and a fixed-list interest are synthetic-only joins; the real-data linkage and interest normalization remain undecided.

## Training and output

Training is an offline job. It uses participant-grouped nested cross-validation for hyperparameter selection, class weights, average precision (PR-AUC), and Brier score. It holds out the latest enrollment cohort (with a gap after the training cohort) for final evaluation and checks calibration and age-band metrics. The training report records the model/feature versions, data cut, selected parameters, metrics, synthetic source, and the top three per-row model-contribution drivers for hold-out examples. Contribution values explain the synthetic model in log-odds space; they are not clinical explanations.

The committed OpenAPI contract documents the only currently designed HTTP operation: `GET /api/v1/health`. Documentation routes are enabled in `dev` and `test`, and disabled in other environments. No scoring, model-info, data-ingest, or participant endpoint is exposed. Training and nightly scoring jobs are not HTTP APIs.

Run the local health API with `uvicorn model_component.api:app --host 127.0.0.1 --port 8000`.

## Deliberate integration boundary

The component does not ingest backend exports, access PostgreSQL, link model IDs to registration IDs, write scores, or return recommendations. The data plan leaves the de-identified linkage, withdrawal/write coordination, retention, scoring staleness, and participant-removal policy unresolved; the model plan leaves API authentication and deployment unresolved. These must be designed and approved before real-data scoring or participant-facing output is implemented.

The remediation library is also not populated or served: each item requires clinician review, and no reviewer or approved content is specified. Risk estimates are not diagnoses. Ethics approval and the study team's questionnaire, thresholds, escalation, and consent decisions are prerequisites to real use.
