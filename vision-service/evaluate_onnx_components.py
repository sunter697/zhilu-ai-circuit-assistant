"""Evaluate the deployed ONNX component detector against Vision IR ground truth."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path
from time import perf_counter
import sys
from typing import Any

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.onnx_detector import (  # noqa: E402
    FULL_CLASS_NAMES,
    HybridComponentDetector,
    OnnxComponentDetector,
)


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def intersection_over_union(
    first: tuple[float, float, float, float],
    second: tuple[float, float, float, float],
) -> float:
    x0 = max(first[0], second[0])
    y0 = max(first[1], second[1])
    x1 = min(first[2], second[2])
    y1 = min(first[3], second[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    first_area = max(0.0, first[2] - first[0]) * max(0.0, first[3] - first[1])
    second_area = max(0.0, second[2] - second[0]) * max(
        0.0, second[3] - second[1]
    )
    return intersection / max(first_area + second_area - intersection, 1e-9)


def ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def evaluate(
    model: Path,
    dataset: Path,
    confidence: float,
    match_iou: float,
    legacy_model: Path | None = None,
) -> dict[str, Any]:
    model = model.resolve()
    dataset = dataset.resolve()
    manifest_path = dataset / "manifest.jsonl"
    if not model.is_file():
        raise FileNotFoundError(model)
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    records = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not records:
        raise ValueError("evaluation manifest is empty")
    if any(record.get("datasetRole") != "evaluation" for record in records):
        raise ValueError("dataset is not a frozen evaluation dataset")

    primary_detector = OnnxComponentDetector(model, confidence=confidence)
    if primary_detector.class_names != FULL_CLASS_NAMES:
        raise ValueError(
            f"model does not use the 9-class schema: {primary_detector.class_names}"
        )
    detector: Any = primary_detector
    legacy_metadata = None
    if legacy_model is not None:
        legacy_model = legacy_model.resolve()
        legacy_detector = OnnxComponentDetector(legacy_model, confidence=confidence)
        if legacy_detector.class_names == FULL_CLASS_NAMES:
            raise ValueError("legacy compatibility model must use the 7-class schema")
        detector = HybridComponentDetector(
            primary_detector,
            compatibility_detector=legacy_detector,
        )
        legacy_metadata = {
            "path": str(legacy_model),
            "sha256": file_sha256(legacy_model),
            "classes": list(legacy_detector.class_names),
        }

    per_class: dict[str, Counter[str]] = defaultdict(Counter)
    failures: list[dict[str, Any]] = []
    total_iou = 0.0
    matched_boxes = 0
    exact_images = 0
    started = perf_counter()

    for record in records:
        image_path = dataset / record["image"]
        ir_path = dataset / record["ir"]
        ir = json.loads(ir_path.read_text(encoding="utf-8"))
        ground_truth = [
            {
                "kind": component["kind"],
                "box": tuple(float(value) for value in component["bodyBox"]),
            }
            for component in ir["components"]
        ]
        with Image.open(image_path) as opened:
            predictions = detector.detect(opened)

        unmatched = set(range(len(ground_truth)))
        image_matches: list[dict[str, Any]] = []
        image_false_positives: list[dict[str, Any]] = []
        for prediction in sorted(
            predictions, key=lambda item: item.confidence, reverse=True
        ):
            candidates = [
                index
                for index in unmatched
                if ground_truth[index]["kind"] == prediction.kind
            ]
            best_index = None
            best_iou = 0.0
            for index in candidates:
                current_iou = intersection_over_union(
                    tuple(float(value) for value in prediction.body_box),
                    ground_truth[index]["box"],
                )
                if current_iou > best_iou:
                    best_index = index
                    best_iou = current_iou
            if best_index is not None and best_iou >= match_iou:
                unmatched.remove(best_index)
                per_class[prediction.kind]["tp"] += 1
                total_iou += best_iou
                matched_boxes += 1
                image_matches.append(
                    {
                        "kind": prediction.kind,
                        "confidence": prediction.confidence,
                        "iou": best_iou,
                    }
                )
            else:
                per_class[prediction.kind]["fp"] += 1
                image_false_positives.append(
                    {
                        "kind": prediction.kind,
                        "confidence": prediction.confidence,
                        "bodyBox": list(prediction.body_box),
                    }
                )

        image_false_negatives = [ground_truth[index] for index in sorted(unmatched)]
        for missed in image_false_negatives:
            per_class[missed["kind"]]["fn"] += 1

        if not image_false_positives and not image_false_negatives:
            exact_images += 1
        else:
            failures.append(
                {
                    "image": record["image"],
                    "semanticHash": record["semanticHash"],
                    "falsePositives": image_false_positives,
                    "falseNegatives": image_false_negatives,
                    "matches": image_matches,
                }
            )

    elapsed_seconds = perf_counter() - started
    class_metrics: dict[str, dict[str, float | int]] = {}
    total = Counter()
    for class_name in FULL_CLASS_NAMES:
        counts = per_class[class_name]
        total.update(counts)
        precision = ratio(counts["tp"], counts["tp"] + counts["fp"])
        recall = ratio(counts["tp"], counts["tp"] + counts["fn"])
        class_metrics[class_name] = {
            "tp": counts["tp"],
            "fp": counts["fp"],
            "fn": counts["fn"],
            "precisionAtThreshold": precision,
            "recallAtThreshold": recall,
            "f1AtThreshold": ratio(
                2 * counts["tp"],
                2 * counts["tp"] + counts["fp"] + counts["fn"],
            ),
        }

    precision = ratio(total["tp"], total["tp"] + total["fp"])
    recall = ratio(total["tp"], total["tp"] + total["fn"])
    return {
        "evaluationVersion": "onnx-component-eval/0.1",
        "model": {
            "path": str(model),
            "sha256": file_sha256(model),
            "classes": list(primary_detector.class_names),
            "inputShape": primary_detector.session.get_inputs()[0].shape,
            "outputShape": primary_detector.session.get_outputs()[0].shape,
            "legacyCompatibilityModel": legacy_metadata,
        },
        "dataset": {
            "path": str(dataset),
            "manifestSha256": file_sha256(manifest_path),
            "samples": len(records),
            "uniqueSemanticHashes": len(
                {record["semanticHash"] for record in records}
            ),
        },
        "settings": {
            "confidence": confidence,
            "nmsIou": primary_detector.nms_iou,
            "matchIou": match_iou,
            "detectorMode": (
                "hybrid-with-profile-routed-legacy-compensation"
                if legacy_model is not None
                else "onnx-only"
            ),
        },
        "overall": {
            "tp": total["tp"],
            "fp": total["fp"],
            "fn": total["fn"],
            "precisionAtThreshold": precision,
            "recallAtThreshold": recall,
            "f1AtThreshold": ratio(
                2 * total["tp"], 2 * total["tp"] + total["fp"] + total["fn"]
            ),
            "meanMatchedIou": total_iou / matched_boxes if matched_boxes else 0.0,
            "exactImages": exact_images,
            "exactImageRate": ratio(exact_images, len(records)),
            "elapsedSeconds": elapsed_seconds,
            "millisecondsPerImage": elapsed_seconds * 1000.0 / len(records),
        },
        "classes": class_metrics,
        "failures": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confidence", type=float, default=0.35)
    parser.add_argument("--match-iou", type=float, default=0.5)
    parser.add_argument("--legacy-model", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"output already exists: {args.output}")
    result = evaluate(
        args.model,
        args.dataset,
        args.confidence,
        args.match_iou,
        args.legacy_model,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({key: result[key] for key in ("model", "dataset", "settings", "overall")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
