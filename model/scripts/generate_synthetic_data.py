"""Regenerate the committed synthetic hourly dataset in data/synthetic.

The CSVs are deterministic for a given seed and size. Load them into Postgres with
data/synthetic/load.sql (or the compose `synthetic-loader` service).
"""

from __future__ import annotations

import argparse
from pathlib import Path

from model_component.hourly_synthetic import RNG_SEED, generate_hourly_usage, write_csv

DEFAULT_DIR = Path(__file__).resolve().parents[1] / "data" / "synthetic"


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_DIR)
    parser.add_argument("--participants", type=int, default=300)
    parser.add_argument("--days", type=int, default=28)
    parser.add_argument("--seed", type=int, default=RNG_SEED)
    args = parser.parse_args(argv)

    dataset = generate_hourly_usage(args.participants, args.days, args.seed)
    write_csv(dataset, args.output_dir)
    print(
        f"Wrote {len(dataset.participants)} participants and "
        f"{len(dataset.hourly_usage)} hourly rows to {args.output_dir}"
    )


if __name__ == "__main__":
    main()
