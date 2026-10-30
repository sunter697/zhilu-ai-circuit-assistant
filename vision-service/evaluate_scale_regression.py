"""Run end-to-end recognition on uniformly resized native Logisim images."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from io import BytesIO
import json
import math
import os
from pathlib import Path
import sys
from time import perf_counter
from typing import Any

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.app import RecognitionApplication  # noqa: E402


def component_mapping(
    truth: dict[str, Any], actual: dict[str, Any], factor: float
) -> tuple[dict[str, str], float]:
    expected_by_kind: dict[str, list[dict[str, Any]]] = defaultdict(list)
    actual_by_kind: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for component in truth["components"]:
        expected_by_kind[component["kind"]].append(component)
    for component in actual["components"]:
        actual_by_kind[component["kind"]].append(component)
    if Counter({key: len(value) for key, value in expected_by_kind.items()}) != Counter(
        {key: len(value) for key, value in actual_by_kind.items()}
    ):
        raise ValueError("component class counts differ")

    mapping: dict[str, str] = {}
    maximum_error = 0.0
    tolerance = max(2.0, factor * 3.5)
    for kind, expected_items in expected_by_kind.items():
        remaining = list(actual_by_kind[kind])
        for expected in expected_items:
            expected_x = float(expected["anchor"][0]) * factor
            expected_y = float(expected["anchor"][1]) * factor
            candidate = min(
                remaining,
                key=lambda item: math.hypot(
                    float(item["anchor"][0]) - expected_x,
                    float(item["anchor"][1]) - expected_y,
                ),
            )
            error = math.hypot(
                float(candidate["anchor"][0]) - expected_x,
                float(candidate["anchor"][1]) - expected_y,
            )
            if error > tolerance:
                raise ValueError(
                    f"{kind} anchor error {error:.3f} exceeds {tolerance:.3f}"
                )
            if expected.get("attributes", {}).get("size") != candidate.get(
                "attributes", {}
            ).get("size"):
                raise ValueError(f"{kind} gate size differs")
            maximum_error = max(maximum_error, error)
            mapping[candidate["id"]] = expected["id"]
            remaining.remove(candidate)
    return mapping, maximum_error


def endpoint_signature(
    net: dict[str, Any], component_ids: dict[str, str] | None = None
) -> frozenset[tuple[str, str]]:
    return frozenset(
        (
            component_ids.get(endpoint["componentId"], endpoint["componentId"])
            if component_ids is not None
            else endpoint["componentId"],
            endpoint["portId"],
        )
        for endpoint in net["endpoints"]
    )


def junction_signature(net: dict[str, Any], factor: float) -> tuple[tuple[int, int], ...]:
    return tuple(
        sorted(
            (round(float(point[0]) / factor), round(float(point[1]) / factor))
            for point in net["junctions"]
        )
    )


def topology_matches(
    truth: dict[str, Any], actual: dict[str, Any], mapping: dict[str, str], factor: float
) -> bool:
    expected = {
        endpoint_signature(net): junction_signature(net, 1.0) for net in truth["nets"]
    }
    observed = {
        endpoint_signature(net, mapping): junction_signature(net, factor)
        for net in actual["nets"]
    }
    return expected == observed


def evaluate(
    model: Path,
    dataset: Path,
    factors: list[float],
    sample_limit: int | None,
) -> dict[str, Any]:
    os.environ["LOGISIM_VISION_MODEL"] = str(model.resolve())
    records = [
        json.loads(line)
        for line in (dataset / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if sample_limit is not None:
        records = records[:sample_limit]
    if not records:
        raise ValueError("scale regression dataset is empty")

    application = RecognitionApplication()
    totals = {factor: Counter() for factor in factors}
    maximum_anchor_error = 0.0
    failures: list[dict[str, Any]] = []
    started = perf_counter()
    for record in records:
        truth = json.loads((dataset / record["ir"]).read_text(encoding="utf-8"))
        with Image.open(dataset / record["image"]) as opened:
            source = opened.convert("RGB")
        for factor in factors:
            totals[factor]["samples"] += 1
            resized = source.resize(
                (
                    max(1, round(source.width * factor)),
                    max(1, round(source.height * factor)),
                ),
                Image.Resampling.NEAREST,
            )
            encoded = BytesIO()
            resized.save(encoded, format="PNG")
            try:
                actual = application.recognize(encoded.getvalue())
                if not actual["unresolved"]:
                    totals[factor]["unresolvedFree"] += 1
                mapping, anchor_error = component_mapping(truth, actual, factor)
                maximum_anchor_error = max(maximum_anchor_error, anchor_error)
                if not actual["unresolved"] and topology_matches(
                    truth, actual, mapping, factor
                ):
                    totals[factor]["exactTopology"] += 1
                    continue
                reason = "topology or unresolved result differs"
            except Exception as exception:  # noqa: BLE001 - preserve evaluation failures
                reason = f"{type(exception).__name__}: {exception}"
            if len(failures) < 100:
                failures.append(
                    {
                        "image": record["image"],
                        "factor": factor,
                        "reason": reason,
                    }
                )

    elapsed = perf_counter() - started
    factor_results = {}
    for factor, counts in totals.items():
        samples = counts["samples"]
        factor_results[str(factor)] = {
            "samples": samples,
            "unresolvedFree": counts["unresolvedFree"],
            "exactTopology": counts["exactTopology"],
            "exactTopologyRate": counts["exactTopology"] / samples,
        }
    total_samples = sum(item["samples"] for item in factor_results.values())
    total_exact = sum(item["exactTopology"] for item in factor_results.values())
    return {
        "evaluationVersion": "native-scale-regression/0.1",
        "evaluationMode": "post-fix-regression",
        "acceptanceEligible": False,
        "runtime": application.health(),
        "dataset": str(dataset.resolve()),
        "records": len(records),
        "factors": factor_results,
        "overall": {
            "samples": total_samples,
            "exactTopology": total_exact,
            "exactTopologyRate": total_exact / total_samples,
            "maximumAnchorErrorPixels": maximum_anchor_error,
            "elapsedSeconds": elapsed,
            "millisecondsPerImage": elapsed * 1000.0 / total_samples,
        },
        "failures": failures,
        "limitations": [
            "Images are uniformly resized with nearest-neighbor sampling.",
            "This is a regression test, not an independently collected screenshot benchmark.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--factors",
        type=float,
        nargs="+",
        default=[0.5, 0.6, 0.8, 1.2, 1.6, 2.0],
    )
    parser.add_argument("--samples", type=int)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"output already exists: {args.output}")
    if any(factor < 0.5 or factor > 2.0 for factor in args.factors):
        raise ValueError("scale factors must be between 0.5 and 2.0")
    result = evaluate(args.model, args.dataset, args.factors, args.samples)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(result["factors"] | {"overall": result["overall"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
