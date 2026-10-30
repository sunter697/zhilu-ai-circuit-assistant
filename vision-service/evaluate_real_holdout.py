"""Evaluate a frozen, independently collected real Logisim screenshot benchmark."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.real_holdout import evaluate  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--minimum-cases", type=int, default=20)
    parser.add_argument("--position-tolerance", type=float, default=8.0)
    parser.add_argument(
        "--regression",
        action="store_true",
        help="allow a changed runtime after the official result; never reports acceptance passed",
    )
    args = parser.parse_args()
    if args.minimum_cases <= 0:
        parser.error("--minimum-cases must be positive")
    if args.output.exists():
        raise FileExistsError(f"output already exists: {args.output}")
    result = evaluate(
        args.dataset,
        minimum_cases=args.minimum_cases,
        position_tolerance=args.position_tolerance,
        official=not args.regression,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "benchmark": result["benchmark"],
                "runtime": result["runtime"],
                "overall": result["overall"],
                "acceptance": result["acceptance"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
