from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd

from model_component.features import build_feature_table
from model_component.synthetic import (
    add_synthetic_questionnaire_label,
    generate_daily_usage,
)
from model_component.training import run_sensitivity, train_and_evaluate


def main() -> None:
    parser = argparse.ArgumentParser(description="Train and validate on synthetic data only.")
    parser.add_argument("--participants", type=int, default=2000)
    parser.add_argument("--days-per-period", type=int, default=14)
    parser.add_argument("--seed", type=int, default=20261124)
    parser.add_argument("--sensitivity", action="store_true")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("artifacts/synthetic"),
    )
    parser.add_argument(
        "--input",
        type=Path,
        help="Optional generated synthetic daily CSV; source must be synthetic.",
    )
    args = parser.parse_args()

    if args.input:
        daily_usage = pd.read_csv(args.input)
    else:
        daily_usage = generate_daily_usage(
            args.participants, args.days_per_period, args.seed
        ).daily_usage
    feature_rows = build_feature_table(daily_usage)
    if args.sensitivity:
        report = run_sensitivity(daily_usage, build_feature_table, args.output_dir, args.seed)
        print(
            f"Synthetic sensitivity results written to "
            f"{args.output_dir / 'sensitivity_report.json'}"
        )
    else:
        labeled = add_synthetic_questionnaire_label(feature_rows, seed=args.seed + 1)
        report = train_and_evaluate(labeled, args.output_dir, seed=args.seed)
        print(f"Synthetic validation report written to {args.output_dir / 'training_report.json'}")
    print(f"Source: {report['source']}")


if __name__ == "__main__":
    main()
