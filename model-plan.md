# Plan: digital-addiction risk model and remediation recommendations

## Problem
Use collected usage data, plus age band, gender and interests, to (1) estimate each participant's risk of problematic phone use and (2) suggest remediation that helps them reduce it.

## Key risk: there is no ground-truth label
"Digital addiction" is not a measured quantity in the usage data. Any model needs a label to learn from, so the label choice is the first decision.

| Option | Label source | Pros | Cons |
|---|---|---|---|
| A. Validated questionnaire (e.g. Smartphone Addiction Scale-Short Version, Bergen Social Media Addiction Scale) | Participant self-report at baseline and follow-up | Most defensible target; clinically recognised scales | Needs questionnaire consent and ethics amendment; self-report bias; needs enough responses |
| B. Rule-based proxy (e.g. more than N hours a day, many night-time sessions, high pickups) | Thresholds set by the team | Available immediately; no extra survey | Model learns the rule, not addiction; circular if the same features are used as inputs |
| C. Unsupervised archetypes (clustering), checked by clinicians | No label; clusters reviewed afterwards | Finds patterns without assumptions | Clusters are not risk levels until reviewed; hard to validate |

Recommended: A as the target, B as a transparent baseline, C only for exploration.

## Technique: risk prediction
- **Baseline: logistic regression** on engineered features. Easy to explain and sets the bar any other model must beat.
- **Main: gradient-boosted trees (LightGBM or XGBoost)** on tabular features. Strong on small-to-medium tabular data; SHAP gives per-person reasons.
- Alternatives: random forest (similar results, less tuning), sequence models such as LSTM or transformer on daily time series (needs far more data than a study is likely to collect).

Feature examples (from data-plan, to be confirmed): daily total, session count and length, night-time use share, pickup count, top-app share, weekday/weekend shift, trend over the last 14 days, age band, gender (see fairness), declared interests.

## Technique: remediation recommendation
- **Main: rule-based library, curated and clinician-reviewed.** Each risk driver (from SHAP) maps to a short list of interventions (e.g. night-time: wind-down schedule and app limits; high pickups: notification batching). Interest matching selects among the list (e.g. offer a sport or reading alternative to someone interested in gaming). Simple, explainable and safe.
- Contextual bandit (e.g. Thompson sampling) to learn which intervention works for whom. Deferred to a later study, after the pilot gives outcome data and a validated reward.
- Collaborative filtering on intervention outcomes. Rejected for now: sparse data and cold start for new participants.

## Training procedure
1. Build a feature table from the de-identified export (backend A7), one row per participant per period. Do not include `payload_enc` or registry fields other than the age band.
2. Split by participant, never by snapshot, to prevent leakage. Use grouped cross-validation, and keep a time-based hold-out for the final test.
3. Handle class imbalance with class weights, and report PR-AUC rather than accuracy.
4. Tune hyperparameters with nested cross-validation. Keep the hold-out untouched until the end.
5. Check calibration (Brier score, reliability plot) so that "risk 0.7" means what it says.
6. Explain each prediction with SHAP values and store the top three drivers with the score.
7. Check performance by age band and gender. Report gaps and do not use gender as a feature unless the study needs it.
8. Version each model (data cut, features, hyperparameters, metrics) and keep a record in the audit log.

## Serving and integration
- Build the model component separately in Python with FastAPI. Training is an offline Python job; a separate scheduled Python job runs **nightly batch scoring** outside Spring Boot.
- The scoring job writes `risk_score`, `risk_drivers` and `remediation_ids` to PostgreSQL. Spring Boot reads the saved results and serves the participant's output to the Android app; the app does not call FastAPI or trigger scoring.
- FastAPI's internal responsibilities and endpoints, job scheduling, deployment, and authentication remain to be designed. Real-time scoring is not required for this phase.
- Use the result schema and withdrawal coordination in [data-plan.md](./data-plan.md). Define a secure link between the de-identified model input and the participant's saved output without exposing registry identifiers in model exports. Use separate least-privilege access for the scoring job and Spring Boot.
- Record model version and scoring time with each result. Handle missing or failed scoring explicitly, and ensure withdrawal deletes model inputs and outputs and prevents a concurrent or later job from recreating them.
- The app's Home page shows a plain-language message and any suggestions returned for the participant, never a diagnosis. The UI mock contains labeled examples only.

## Safety and ethics requirements
- Output is a risk estimate and suggestions, not a clinical diagnosis. Show that in the app and in the consent form.
- Clinician review of every remediation text before release.
- Escalation: if the questionnaire or usage data shows severe distress, the app shows a support contact. Define the threshold with the study team.
- Participants can see and remove their risk output. Withdrawal (A8) deletes model inputs and outputs.
- Ethics amendment needed for model training, risk scoring and returning results to participants.

## Phases
1. Define the label with the study team and the ethics amendment (no code).
2. Feature table and rule-based baseline (proxy labels).
3. Logistic regression and LightGBM with grouped CV, calibration and subgroup checks.
4. Curated remediation library and interest matching, with clinician review.
5. Separate Python/FastAPI model component, nightly batch scoring into PostgreSQL, and a Spring Boot read endpoint for the app.
6. Run the randomised pilot with the clinician-approved library and monitoring-only control; measure the pre-registered outcomes. No bandit in this study.

## Decisions (from user)
- Label: questionnaire (option A). Ethics amendment needed.
- Risk model: LightGBM, with logistic regression as the baseline.
- Remediation: randomised pilot. Participants are randomly assigned to the clinician-approved library or a monitoring-only control. No adaptive learning in this study.
- Control arm: monitoring only (usage feedback, no suggestions).
- Delivery: separate Python/FastAPI model component and scheduled Python jobs; Spring Boot exposes the saved recommendations through an API that the app calls.

## Randomised pilot design
- Random assignment at enrolment, stratified by age band and baseline risk tier, so the arms are balanced. The assignment is stored in the registry and is not visible to the app.
- Primary outcome: change in the questionnaire score from baseline to follow-up. Secondary: change in daily usage against the 14-day baseline, and engagement with suggestions.
- Analysis: compare arms with a pre-registered model (for example, ANCOVA on follow-up score adjusted for baseline). Report effect size with a confidence interval.
- Monitoring-only control still gets the usage dashboard, so both arms are treated equally except for the suggestions.
- The remediation library is the same content for both arms that receive suggestions. Clinicians review it before the pilot.

## Future phase: contextual bandit (deferred)
- Only after the pilot provides outcome data and a validated reward.
- Would choose only among the clinician-approved candidates, with a fixed exploration rate and a logged policy for off-policy evaluation.
- Needs a separate ethics review.

## Serving with API (decided)
- The nightly Python job computes risk scores and writes results to PostgreSQL. Spring Boot applies the participant's arm assignment when serving suggestions; the assignment is not exposed to the app.
- Spring Boot read endpoint `GET /api/v1/recommendations` (bearer auth, proposed) returns the risk tier, the top drivers in plain language, and suggestions for the participant's arm. Monitoring-only participants get no suggestions.
- Spring Boot feedback endpoint `POST /api/v1/recommendations/{id}/feedback` (proposed) records engagement.

## OpenAPI documentation (Python / FastAPI)
This section covers the FastAPI model component only. The nightly scoring and training jobs are not HTTP APIs and are not in the OpenAPI file; document them in the component README (schedule, inputs, outputs, failure handling).

- **Code-first with FastAPI's built-in generation.** Every route declares a Pydantic `response_model`, request models, `status_code`, `operation_id`, `summary`, `tags`, and `responses={...}` for each error status it can return (at least `401`, `403`, `422`, `5xx`, using one shared error model with no internal detail).
- **Scope of the API.** Document only the operations that are actually designed (see open questions). Candidates are a health endpoint, a model-info endpoint (model version, data cut, metrics summary), and internal scoring-trigger or status operations. If none are needed beyond health, the file stays small; do not add endpoints just to document them.
- **Internal only.** The app never calls FastAPI. Set `servers` and the description to say the API is for the scoring job and the Spring Boot service only. State the service authentication scheme (for example `apiKey` in a header or an `http` bearer token from the platform's workload identity) as a `securitySchemes` entry, and apply it to every route except health. The scheme is decided with the open authentication question below.
- **No participant data in the schema or examples.** Examples use synthetic values and `source = synthetic`. No registration IDs, names, or real usage values. Request and response models use the de-identified model-input identifier, never registry fields.
- **Exposure.** `/docs`, `/redoc`, and `/openapi.json` are enabled in `dev` and `test` only. In production construct the app with `docs_url=None, redoc_url=None, openapi_url=None`, and publish the committed file instead.
- **Committed contract.** A script (for example `scripts/export_openapi.py`) calls `app.openapi()` and writes `api/openapi/model-api.yaml`. Set `title`, `version` (the API version, separate from the model version), and `description` on the `FastAPI(...)` object.
- **CI.** Regenerate the file and fail if it differs from the committed one. Lint with Spectral. Add a Schemathesis or pytest contract test that calls each route with the test client and validates responses against the schema. A route without a documented error response fails the lint.
- **Delivery.** Add this in phase 5, together with the first FastAPI route. A route is not done until it appears in the committed YAML with examples.


## Synthetic data for validation (academic, decided)
- Purpose: validate the pipeline and the model. Not used to tune remediation and not used to claim effectiveness.
- No real participant data is used to set any parameter.
- Size: 2000 synthetic participants, each with daily usage over the study period.
- Label: a latent risk score built from documented rules (for example, higher night-time share and more pickups raise risk), plus noise. Each rule and its effect size is written in a generator assumptions file.
- Every synthetic record, file, model version and report carries `source = synthetic`. Synthetic and real data are never stored in the same tables.
- Sensitivity checks: rerun with weaker and stronger planted effects. Report whether the conclusions hold across them.
- Ethics: confirm with the committee that synthetic data without real persons needs no approval. Keep the generator code and assumptions with the thesis.

## Open questions
- Will the study collect a questionnaire label? This decides the whole approach.
- Expected number of participants. Models need a few hundred labelled participants to be useful; fewer means the rule-based approach is the main output.
- Interest list: a fixed list, or free text?
- Is returning a risk score to participants allowed under the ethics approval?
- Which team member is the clinical reviewer?
- What internal operations should FastAPI expose, and how will they be authenticated and deployed? How will jobs be scheduled? (The answer sets the content of the FastAPI OpenAPI file.)
- How will de-identified inputs be securely linked to participant outputs, and how will scoring coordinate with withdrawal and deletion?
