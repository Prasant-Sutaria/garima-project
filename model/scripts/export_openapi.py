from __future__ import annotations

import argparse
from pathlib import Path

import yaml

from model_component.api import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description="Export the committed model API contract.")
    parser.add_argument("--check", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("api/model-api.yaml"),
    )
    args = parser.parse_args()

    document = create_app("dev").openapi()
    serialized = yaml.safe_dump(document, sort_keys=False, allow_unicode=True)
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != serialized:
            raise SystemExit(f"{args.output} is stale; run python scripts/export_openapi.py")
        print(f"{args.output} is up to date")
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(serialized, encoding="utf-8")
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
