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

## Synthetic hourly dataset (`data/synthetic`)

The project ships a generated dataset of hourly phone use. It covers 300 synthetic participants over 28 days (2026-01-05 to 2026-02-01), for a total of 201,600 rows. It is not participant data.

- `participants.csv`: `synthetic_id`, `profession` (`student`, `working_professional`, `homemaker`), `age_band`.
- `hourly_usage.csv`: `synthetic_id`, `usage_date`, `hour` (0-23, local time), `screen_minutes` (0-60), `unlock_count`.
- `schema.sql` creates the separate Postgres schema `synthetic`. `load.sql` (re)loads the CSVs in one transaction and grants read access to `usage_model_reader` if that role exists. Synthetic rows never go into `public` or `registry` tables.

Each profession has its own weekday and weekend rhythm, and all of them lean towards night (21:00-23:59) and late night (00:00-05:59):

| Profession | Weekday rhythm | Weekend rhythm |
| --- | --- | --- |
| Student | Light use around classes, a bump after class, then heavy use from 21:00 to about 02:00 | Late start, heavy late night |
| Working professional | Wake-up check, commute, lunch break, commute home, night scrolling from about 21:00 to 00:30 | More daytime use, night peak |
| Homemaker | Mid-morning and afternoon lulls between chores, then after dinner until about midnight | Later start, evening and night |

People differ by body clock (up to ?3 h), the strength of each part of their routine, daily volume, and day-to-day noise. About 5% of days are light days. Hours are capped at 60 minutes, and unlocks scale with screen time. In the committed data, about 54% of screen time falls between 21:00 and 05:59. The data is deterministic, so regenerate it after changing `src/model_component/hourly_synthetic.py`:

```powershell
.\.venv\Scripts\python.exe scripts\generate_synthetic_data.py
```

Load it into Postgres with `docker compose --profile model run --rm synthetic-loader`. Without Docker, run `psql -v ON_ERROR_STOP=1 -f load.sql` from `data/synthetic` as the database owner.

## Usage-pattern dashboard (read-only Postgres job)

`scripts/run_usage_patterns.py` is a one-shot job that writes `index.html`, `data/*.json`, and `run_manifest.json` to the output directory. It has two separate sections:

1. **Real daily pattern.** It reads scheduled daily snapshots through a read-only connection and learns how daily screen-on time varies by weekday. The dashboard shows the weekday profile and validation metrics. The day-by-day trend is written only to `data/real_daily_patterns.json` and is not displayed.
2. **Time of day by profession (synthetic).** It reads the `synthetic` schema and produces:
   - weekday and weekend hourly profiles and daypart shares per profession;
   - a cross-validated classifier that shows how well hourly rhythm alone predicts profession, with a confusion matrix and the hours that most distinguish each profession;
   - KMeans groups found without using profession, compared with profession by adjusted Rand index.

Details:

- Real rows come only from `usage_snapshots` joined to `registry.registrations` with `status = 'activated'` and `trigger = 'scheduled'`. Deactivated participants and manual snapshots are excluded. Participants are numbered inside the query, so registration IDs never leave the database. Groups with fewer than 5 participants are suppressed.
- The stored real snapshots contain daily totals only, so real time-of-day patterns cannot be learned yet. The time-of-day section is labelled synthetic and never mixes in real rows.
- The connection uses `default_transaction_read_only=on`. Use a least-privilege role:

```sql
CREATE ROLE usage_model_reader LOGIN PASSWORD '<set-a-secret>';
GRANT CONNECT ON DATABASE usage TO usage_model_reader;
GRANT USAGE ON SCHEMA public, registry, synthetic TO usage_model_reader;
GRANT SELECT ON usage_snapshots TO usage_model_reader;
GRANT SELECT (registration_id, status) ON registry.registrations TO usage_model_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA synthetic TO usage_model_reader;
```

Local run without a database (requires `pip install -e ".[dev]"`). This reads the committed CSVs and skips the real section:

```powershell
.\.venv\Scripts\python.exe scripts\run_usage_patterns.py --skip-real --synthetic-csv data\synthetic --output-dir artifacts\patterns
```

Without `--synthetic-csv`, the job reads the `synthetic` schema and needs the `MODEL_DB_*` variables, including `MODEL_DB_PASSWORD`.

Container run (the `model` profile keeps these services out of a plain `docker compose up`). Set `MODEL_DB_PASSWORD` in the root `.env` first. `model-job` runs `synthetic-loader` first, then writes the dashboard:

```powershell
docker compose --profile model run --rm model-job
docker compose --profile model up -d pattern-dashboard
```

Open `http://localhost:8090`. The page loads Chart.js from a CDN, so the browser needs internet access.

## Deliberate integration boundary

The component does not write participant data to PostgreSQL, write scores, link model IDs to registration IDs, or return recommendations. Its only database write is `data/synthetic/load.sql`, which replaces generated rows in the separate `synthetic` schema. The usage-pattern job reads PostgreSQL only through the read-only role. The data plan leaves the de-identified linkage, withdrawal/write coordination, retention, scoring staleness, and participant-removal policy unresolved; the model plan leaves API authentication and deployment unresolved. These must be designed and approved before real-data scoring or participant-facing output is implemented.

The remediation library is also not populated or served: each item requires clinician review, and no reviewer or approved content is specified. Risk estimates are not diagnoses. Ethics approval and the study team's questionnaire, thresholds, escalation, and consent decisions are prerequisites to real use.
