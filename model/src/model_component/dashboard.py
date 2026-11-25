"""Writes the usage-pattern JSON files and a self-contained HTML dashboard."""

from __future__ import annotations

import json
from pathlib import Path

from model_component import PATTERN_VERSION

CHART_JS_URL = "https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"

_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mobile usage patterns</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 2rem auto; max-width: 1080px; color: #1d1d1f; padding: 0 1rem; }
  h1 { font-size: 1.6rem; }
  h2 { margin-top: 2.5rem; border-bottom: 1px solid #ddd; padding-bottom: .3rem; }
  .banner { background: #fff4e5; border-left: 4px solid #f0a020; padding: .75rem 1rem; margin: 1rem 0; }
  .note { color: #555; font-size: .9rem; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; }
  .card { border: 1px solid #e3e3e3; border-radius: 8px; padding: 1rem; }
  table { border-collapse: collapse; width: 100%; font-size: .9rem; }
  th, td { border-bottom: 1px solid #eee; padding: .35rem .5rem; text-align: left; }
  @media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
</head>
<body>
<h1>Mobile usage patterns</h1>
<p class="note">Pattern analysis only. Results describe groups of participants and are not diagnoses or clinical risk scores.</p>

<h2>1. Daily pattern (real data)</h2>
<div id="real-status"></div>
<div id="real-body">
<div class="grid">
  <div class="card"><canvas id="weekdayChart"></canvas></div>
  <div class="card"><table id="metricsTable"></table></div>
</div>
</div>

<h2>2. Time-of-day pattern by profession (synthetic)</h2>
<div class="banner">Synthetic hourly data for students, working professionals and homemakers. The real snapshots store only daily totals, so no real time-of-day pattern is shown here.</div>
<div id="tod-status"></div>
<div class="grid">
  <div class="card"><canvas id="weekdayHourlyChart"></canvas></div>
  <div class="card"><canvas id="weekendHourlyChart"></canvas></div>
  <div class="card"><canvas id="daypartChart"></canvas></div>
  <div class="card"><canvas id="dailyHoursChart"></canvas></div>
</div>
<div class="card" style="margin-top:1rem"><table id="professionTable"></table></div>

<h3>Can hourly rhythm alone tell the professions apart?</h3>
<div id="classifier-status"></div>
<div class="grid">
  <div class="card"><table id="confusionTable"></table></div>
  <div class="card"><table id="distinguishingTable"></table></div>
</div>

<h3>Groups found without using profession (clustering)</h3>
<div id="cluster-status"></div>
<div class="grid">
  <div class="card"><canvas id="clusterChart"></canvas></div>
  <div class="card"><table id="clusterTable"></table></div>
</div>

<script type="application/json" id="pattern-data">__DATA_JSON__</script>
<script src="__CHART_JS_URL__"></script>
<script>
(function () {
  const data = JSON.parse(document.getElementById("pattern-data").textContent);
  const palette = ["#2f6fdb", "#e0703a", "#2ca58d", "#9b59b6", "#c0392b", "#7f8c8d"];

  function text(parent, value) {
    const node = document.createElement("div");
    node.textContent = value;
    parent.appendChild(node);
  }
  function fmt(value, digits) {
    return value === null || value === undefined ? "suppressed (n<5)" : Number(value).toFixed(digits);
  }
  function table(target, header, rows) {
    target.textContent = "";
    const thead = document.createElement("tr");
    header.forEach(function (h) { const th = document.createElement("th"); th.textContent = h; thead.appendChild(th); });
    target.appendChild(thead);
    rows.forEach(function (row) {
      const tr = document.createElement("tr");
      row.forEach(function (cell) { const td = document.createElement("td"); td.textContent = cell; tr.appendChild(td); });
      target.appendChild(tr);
    });
  }

  const real = data.real;
  const realStatus = document.getElementById("real-status");
  if (real.status !== "ok") {
    document.getElementById("real-body").hidden = true;
    text(realStatus, "Daily model not run: " + real.status + " (participants: " + (real.participants || 0) + ").");
  } else {
    text(realStatus, "Source: real, read-only. " + real.participants + " participants, " + real.participant_days +
      " participant-days, " + real.date_range[0] + " to " + real.date_range[1] +
      ". Weekend minus weekday (model): " + real.weekend_minus_weekday_hours.toFixed(2) + " h/day.");
    new Chart(document.getElementById("weekdayChart"), {
      type: "bar",
      data: {
        labels: real.weekday_profile.map(function (d) { return d.weekday; }),
        datasets: [
          { label: "Observed mean (h/day)", data: real.weekday_profile.map(function (d) { return d.observed_mean_hours; }), backgroundColor: palette[0] },
          { label: "Model-learned effect (h/day)", type: "line", data: real.weekday_profile.map(function (d) { return d.model_effect_hours; }), borderColor: palette[1], backgroundColor: palette[1] }
        ]
      },
      options: { plugins: { title: { display: true, text: "Usage by weekday" } }, scales: { y: { title: { display: true, text: "hours" } } } }
    });
    table(document.getElementById("metricsTable"), ["Validation (grouped by participant)", "MAE (hours/day)"], [
      ["Model", real.metrics.model_mae_hours.toFixed(3)],
      ["Baseline: previous day", real.metrics.baseline_previous_day_mae_hours.toFixed(3)],
      ["Baseline: trailing 7-day mean", real.metrics.baseline_trailing_7day_mae_hours.toFixed(3)]
    ]);
  }

  const tod = data.time_of_day;
  const names = { student: "Student", working_professional: "Working professional", homemaker: "Homemaker" };
  const partNames = { morning: "Morning 06-11", afternoon: "Afternoon 12-16", evening: "Evening 17-20", night: "Night 21-23", late_night: "Late night 00-05" };
  const hourLabels = Array.from({ length: 24 }, function (_, h) { return String(h).padStart(2, "0") + ":00"; });
  function pct(v) { return (v * 100).toFixed(1) + "%"; }
  function hh(h) { return h === null ? "suppressed" : String(h).padStart(2, "0") + ":00"; }
  function label(p) { return names[p] || p; }

  const shares = tod.overall_daypart_shares;
  text(document.getElementById("tod-status"), "Source: synthetic (" + tod.data_origin + "). " + tod.participants +
    " participants, " + tod.date_range[0] + " to " + tod.date_range[1] + ". Night plus late-night share of all screen time: " +
    pct(shares.night + shares.late_night) + ".");

  const professions = (tod.professions || []).filter(function (p) { return p.weekday_hourly_share; });
  function rhythmChart(id, key, title) {
    new Chart(document.getElementById(id), {
      type: "line",
      data: {
        labels: hourLabels,
        datasets: professions.map(function (p, i) {
          return { label: label(p.profession) + " (n=" + p.participants + ")", data: p[key].map(function (v) { return v * 100; }), borderColor: palette[i % palette.length], backgroundColor: palette[i % palette.length], pointRadius: 0, tension: 0.3 };
        })
      },
      options: { plugins: { title: { display: true, text: title } }, scales: { y: { title: { display: true, text: "% of day's screen time" } } } }
    });
  }
  rhythmChart("weekdayHourlyChart", "weekday_hourly_share", "Weekday: share of screen time by hour");
  rhythmChart("weekendHourlyChart", "weekend_hourly_share", "Weekend: share of screen time by hour");

  const parts = Object.keys(tod.dayparts);
  new Chart(document.getElementById("daypartChart"), {
    type: "bar",
    data: {
      labels: professions.map(function (p) { return label(p.profession); }),
      datasets: parts.map(function (part, i) {
        return { label: partNames[part] || part, data: professions.map(function (p) { return p.daypart_shares[part] * 100; }), backgroundColor: palette[i % palette.length] };
      })
    },
    options: { indexAxis: "y", plugins: { title: { display: true, text: "Daypart share by profession (%)" } }, scales: { x: { stacked: true, max: 100 }, y: { stacked: true } } }
  });
  new Chart(document.getElementById("dailyHoursChart"), {
    type: "bar",
    data: {
      labels: professions.map(function (p) { return label(p.profession); }),
      datasets: ["weekday", "weekend"].map(function (dayType, i) {
        return { label: dayType === "weekday" ? "Weekday" : "Weekend", data: professions.map(function (p) { return p.mean_daily_hours[dayType]; }), backgroundColor: palette[i] };
      })
    },
    options: { plugins: { title: { display: true, text: "Average daily screen time (hours)" } } }
  });
  table(document.getElementById("professionTable"),
    ["Profession", "Participants", "Weekday peak", "Weekend peak", "Night share", "Late-night share", "Weekday h/day", "Weekend h/day"],
    (tod.professions || []).map(function (p) {
      const ok = p.daypart_shares !== null;
      return [label(p.profession), p.participants, hh(p.peak_hour_weekday), hh(p.peak_hour_weekend),
        ok ? pct(p.daypart_shares.night) : "suppressed", ok ? pct(p.daypart_shares.late_night) : "suppressed",
        ok ? p.mean_daily_hours.weekday.toFixed(2) : "suppressed", ok ? p.mean_daily_hours.weekend.toFixed(2) : "suppressed"];
    }));

  const clf = tod.profession_classifier;
  if (clf && clf.status === "ok") {
    text(document.getElementById("classifier-status"), clf.type + ", " + clf.validation + ". Balanced accuracy " +
      pct(clf.balanced_accuracy) + " against " + pct(clf.majority_baseline_accuracy) + " for always guessing the largest group.");
    table(document.getElementById("confusionTable"), ["Actual (rows) / predicted (columns)"].concat(clf.classes.map(label)),
      clf.confusion_matrix.map(function (row, i) { return [label(clf.classes[i])].concat(row); }));
    table(document.getElementById("distinguishingTable"), ["Profession", "Hours that most point to it"],
      Object.keys(clf.most_distinguishing_hours).map(function (p) { return [label(p), clf.most_distinguishing_hours[p].join(", ")]; }));
  } else {
    text(document.getElementById("classifier-status"), "Classifier not run (profession labels unavailable).");
  }

  const clusters = tod.clusters.filter(function (c) { return c.weekday_hourly_share; });
  text(document.getElementById("cluster-status"), "KMeans on 48 hourly shares (weekday and weekend). " + tod.chosen_clusters +
    " groups chosen by silhouette score " + tod.silhouette_score.toFixed(3) +
    (tod.profession_recovery_adjusted_rand !== undefined ? "; agreement with profession (adjusted Rand) " + tod.profession_recovery_adjusted_rand.toFixed(3) : "") + ".");
  new Chart(document.getElementById("clusterChart"), {
    type: "line",
    data: {
      labels: hourLabels,
      datasets: clusters.map(function (c, i) {
        const combined = c.weekday_hourly_share.map(function (v, h) { return (5 * v + 2 * c.weekend_hourly_share[h]) / 7 * 100; });
        return { label: c.label + " (n=" + c.participants + ")", data: combined, borderColor: palette[i % palette.length], backgroundColor: palette[i % palette.length], pointRadius: 0, tension: 0.3 };
      })
    },
    options: { plugins: { title: { display: true, text: "Learned groups: share of screen time by hour (%)" } } }
  });
  table(document.getElementById("clusterTable"), ["Group", "Participants", "Peak", "Night + late night", "Profession mix"], tod.clusters.map(function (c) {
    const mix = c.profession_mix ? Object.keys(c.profession_mix).map(function (p) { return label(p) + " " + c.profession_mix[p]; }).join(", ") : "-";
    return [c.label, c.participants, hh(c.peak_hour), c.daypart_shares ? pct(c.daypart_shares.night + c.daypart_shares.late_night) : "suppressed", mix];
  }));
})();
</script>
</body>
</html>
"""


def _safe_json(payload: object) -> str:
    """Serialise for embedding in a script tag; '</' cannot close the tag early."""
    return json.dumps(payload, separators=(",", ":"), allow_nan=False).replace("</", "<\\/")


def write_outputs(
    output_dir: Path,
    real_result: dict[str, object],
    time_of_day_result: dict[str, object],
    manifest: dict[str, object],
) -> None:
    data_dir = output_dir / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "real_daily_patterns.json").write_text(
        json.dumps(real_result, indent=2), encoding="utf-8"
    )
    (data_dir / "synthetic_time_of_day.json").write_text(
        json.dumps(time_of_day_result, indent=2), encoding="utf-8"
    )
    (output_dir / "run_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    combined = {"real": real_result, "time_of_day": time_of_day_result, "version": PATTERN_VERSION}
    html = _PAGE.replace("__DATA_JSON__", _safe_json(combined)).replace("__CHART_JS_URL__", CHART_JS_URL)
    (output_dir / "index.html").write_text(html, encoding="utf-8")
