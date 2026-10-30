"""Prepare source-derived truth and a non-frozen real-holdout manifest draft."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.source_truth import prepare_dataset


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--source-project", type=Path, required=True)
    parser.add_argument(
        "--expression-plan",
        type=Path,
        default=Path("evaluation/real-holdout-expressions-v01.json"),
    )
    parser.add_argument("--model", type=Path, default=Path("models/best.onnx"))
    args = parser.parse_args()
    manifest = prepare_dataset(
        args.dataset.resolve(),
        args.source_project.resolve(),
        args.expression_plan.resolve(),
        args.model.resolve(),
    )
    print(
        f"Prepared {len(manifest['cases'])} source-derived annotations; "
        f"review manifest.draft.json and review-overlays before freezing."
    )


if __name__ == "__main__":
    main()
