from __future__ import annotations

import argparse
from pathlib import Path

from model_component.synthetic import generate_daily_usage


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate synthetic daily usage snapshots.")
    parser.add_argument("--participants", type=int, default=2000)
    parser.add_argument("--days-per-period", type=int, default=14)
    parser.add_argument("--seed", type=int, default=20261124)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/synthetic/daily_usage.csv"),
    )
    args = parser.parse_args()

    dataset = generate_daily_usage(args.participants, args.days_per_period, args.seed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    dataset.daily_usage.to_csv(args.output, index=False)
    print(
        f"Wrote {len(dataset.daily_usage)} synthetic daily snapshots "
        f"for {dataset.participants} participants to {args.output}"
    )


if __name__ == "__main__":
    main()
