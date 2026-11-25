"""Run the usage-pattern job and write the dashboard.

- Real daily patterns: read-only from Postgres (`usage_snapshots`).
- Time-of-day patterns by profession: synthetic hourly rows from the Postgres `synthetic`
  schema (default) or from the committed CSVs with --synthetic-csv.
Real and synthetic rows are never mixed.
"""

from __future__ import annotations

import argparse
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from model_component import FEATURE_VERSION, MODEL_VERSION, PATTERN_VERSION
from model_component.daily_patterns import train_daily_pattern_model
from model_component.dashboard import write_outputs
from model_component.hourly_synthetic import read_csv
from model_component.postgres_source import (
    connect_from_env,
    load_real_daily_usage,
    load_synthetic_hourly_usage,
)
from model_component.time_of_day import learn_time_of_day_patterns


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/patterns"))
    parser.add_argument(
        "--skip-real",
        action="store_true",
        help="Skip the real daily-usage section.",
    )
    parser.add_argument(
        "--synthetic-csv",
        type=Path,
        metavar="DIR",
        help="Read synthetic hourly data from CSVs in DIR instead of the Postgres schema.",
    )
    parser.add_argument("--seed", type=int, default=20261124)
    args = parser.parse_args(argv)

    connection: Any = None
    if not args.skip_real or args.synthetic_csv is None:
        connection = connect_from_env()
    try:
        if args.skip_real:
            real_result: dict[str, object] = {"source": "real", "status": "skipped"}
        else:
            real_result = train_daily_pattern_model(load_real_daily_usage(connection), seed=args.seed)

        if args.synthetic_csv is not None:
            synthetic = read_csv(args.synthetic_csv)
            synthetic_origin = "committed CSV files"
        else:
            synthetic = load_synthetic_hourly_usage(connection)
            synthetic_origin = "Postgres schema 'synthetic'"
    finally:
        if connection is not None:
            connection.close()

    time_of_day_result = learn_time_of_day_patterns(
        synthetic.hourly_usage, synthetic.participants, seed=args.seed
    )
    time_of_day_result["data_origin"] = synthetic_origin

    manifest = {
        "generated_at_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "pattern_version": PATTERN_VERSION,
        "model_version": MODEL_VERSION,
        "feature_version": FEATURE_VERSION,
        "real_section_status": real_result["status"],
        "time_of_day_section_status": time_of_day_result["status"],
        "time_of_day_source": "synthetic",
        "time_of_day_data_origin": synthetic_origin,
        "seed": args.seed,
    }
    write_outputs(args.output_dir, real_result, time_of_day_result, manifest)
    print(f"Wrote dashboard to {args.output_dir / 'index.html'}")
    print(
        f"Real section: {real_result['status']}; "
        f"time-of-day section: {time_of_day_result['status']} ({synthetic_origin})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
