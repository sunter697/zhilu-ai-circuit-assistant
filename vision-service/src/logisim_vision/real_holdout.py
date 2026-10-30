"""Validation and evaluation for independently collected Logisim screenshots."""

from __future__ import annotations

from collections import Counter, defaultdict
from hashlib import sha256
from math import hypot
import json
from pathlib import Path
import re
from time import perf_counter
from typing import Any

from PIL import Image

from .app import RecognitionApplication
from .onnx_detector import FULL_CLASS_NAMES


BENCHMARK_SCHEMA = "logisim-real-holdout/0.1"
REQUIRED_DECLARATIONS = (
    "modelAndThresholdsFrozenBeforeCollection",
    "notUsedForTraining",
    "notUsedForThresholdSelection",
    "annotationCompletedBeforeEvaluation",
)
CASE_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def f1_score(tp: int, fp: int, fn: int) -> float:
    return ratio(2 * tp, 2 * tp + fp + fn)


def _safe_relative_path(root: Path, value: Any, field: str) -> Path:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} must be a non-empty relative path")
    relative = Path(value)
    if relative.is_absolute():
        raise ValueError(f"{field} must be relative to the benchmark directory")
    resolved = (root / relative).resolve()
    try:
        resolved.relative_to(root)
    except ValueError as exception:
        raise ValueError(f"{field} escapes the benchmark directory") from exception
    if not resolved.is_file():
        raise FileNotFoundError(resolved)
    return resolved


def _verified_hash(path: Path, expected: Any, field: str) -> str:
    if not isinstance(expected, str) or SHA256_PATTERN.fullmatch(expected) is None:
        raise ValueError(f"{field} must be a lowercase SHA-256 value")
    actual = file_sha256(path)
    if actual != expected:
        raise ValueError(
            f"{field} mismatch for {path.name}: expected {expected}, got {actual}"
        )
    return actual


def _validate_truth(
    truth: Any,
    image_hash: str,
    image_size: tuple[int, int],
    case_id: str,
) -> dict[str, Any]:
    if not isinstance(truth, dict) or truth.get("schemaVersion") != "vision-ir/0.1":
        raise ValueError(f"case {case_id} expectedIr must use vision-ir/0.1")
    source = truth.get("source")
    if not isinstance(source, dict):
        raise ValueError(f"case {case_id} expectedIr is missing source metadata")
    if source.get("sha256") != image_hash:
        raise ValueError(f"case {case_id} expectedIr source SHA-256 does not match image")
    if (source.get("width"), source.get("height")) != image_size:
        raise ValueError(f"case {case_id} expectedIr source dimensions do not match image")
    components = truth.get("components")
    nets = truth.get("nets")
    if not isinstance(components, list) or not components:
        raise ValueError(f"case {case_id} expectedIr must contain components")
    if not isinstance(nets, list):
        raise ValueError(f"case {case_id} expectedIr nets must be an array")
    if truth.get("unresolved") != []:
        raise ValueError(f"case {case_id} ground truth must not contain unresolved issues")

    component_ids: set[str] = set()
    for component in components:
        if not isinstance(component, dict):
            raise ValueError(f"case {case_id} has an invalid component")
        component_id = component.get("id")
        kind = component.get("kind")
        anchor = component.get("anchor")
        if not isinstance(component_id, str) or component_id in component_ids:
            raise ValueError(f"case {case_id} has a missing or duplicate component id")
        component_ids.add(component_id)
        if kind not in FULL_CLASS_NAMES:
            raise ValueError(f"case {case_id} uses unsupported component kind {kind!r}")
        if (
            not isinstance(anchor, list)
            or len(anchor) != 2
            or not all(isinstance(value, (int, float)) for value in anchor)
        ):
            raise ValueError(f"case {case_id} component {component_id} has invalid anchor")

    for net in nets:
        if not isinstance(net, dict) or not isinstance(net.get("endpoints"), list):
            raise ValueError(f"case {case_id} has an invalid net")
        for endpoint in net["endpoints"]:
            if endpoint.get("componentId") not in component_ids:
                raise ValueError(f"case {case_id} net references an unknown component")
            if not isinstance(endpoint.get("portId"), str):
                raise ValueError(f"case {case_id} net endpoint has an invalid port id")
        junctions = net.get("junctions", [])
        if not isinstance(junctions, list):
            raise ValueError(f"case {case_id} net junctions must be an array")
    return truth


def load_benchmark(dataset: Path, minimum_cases: int = 20) -> dict[str, Any]:
    """Load a frozen benchmark and reject data that cannot support a holdout claim."""

    root = dataset.resolve()
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("benchmark manifest must be a JSON object")
    if manifest.get("schemaVersion") != BENCHMARK_SCHEMA:
        raise ValueError(f"manifest must use {BENCHMARK_SCHEMA}")
    if manifest.get("datasetRole") != "independent-holdout":
        raise ValueError("datasetRole must be independent-holdout")
    if manifest.get("frozen") is not True:
        raise ValueError("benchmark must be frozen before evaluation")
    if not isinstance(manifest.get("frozenAt"), str) or not manifest["frozenAt"]:
        raise ValueError("frozenAt is required")
    declarations = manifest.get("declarations")
    if not isinstance(declarations, dict):
        raise ValueError("benchmark declarations are required")
    missing = [name for name in REQUIRED_DECLARATIONS if declarations.get(name) is not True]
    if missing:
        raise ValueError(
            "independent holdout declarations must be true: " + ", ".join(missing)
        )
    expected_runtime = manifest.get("expectedRuntime")
    if not isinstance(expected_runtime, dict):
        raise ValueError("expectedRuntime is required")
    if not isinstance(expected_runtime.get("serviceVersion"), str):
        raise ValueError("expectedRuntime.serviceVersion is required")
    if (
        not isinstance(expected_runtime.get("modelSha256"), str)
        or SHA256_PATTERN.fullmatch(expected_runtime["modelSha256"]) is None
    ):
        raise ValueError("expectedRuntime.modelSha256 must be a lowercase SHA-256 value")
    if not isinstance(expected_runtime.get("detectorModePrefix"), str):
        raise ValueError("expectedRuntime.detectorModePrefix is required")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) < minimum_cases:
        raise ValueError(f"benchmark must contain at least {minimum_cases} cases")

    loaded_cases: list[dict[str, Any]] = []
    case_ids: set[str] = set()
    image_hashes: set[str] = set()
    for record in cases:
        if not isinstance(record, dict):
            raise ValueError("each benchmark case must be an object")
        case_id = record.get("id")
        if not isinstance(case_id, str) or CASE_ID_PATTERN.fullmatch(case_id) is None:
            raise ValueError("case id must contain only letters, numbers, '-' or '_'")
        if case_id in case_ids:
            raise ValueError(f"duplicate case id: {case_id}")
        case_ids.add(case_id)
        if not isinstance(record.get("capture"), dict):
            raise ValueError(f"case {case_id} must record capture metadata")

        image_path = _safe_relative_path(root, record.get("image"), "image")
        image_hash = _verified_hash(
            image_path, record.get("imageSha256"), "imageSha256"
        )
        if image_hash in image_hashes:
            raise ValueError(f"case {case_id} duplicates another benchmark image")
        image_hashes.add(image_hash)
        with Image.open(image_path) as opened:
            if opened.format != "PNG":
                raise ValueError(f"case {case_id} image must be PNG")
            image_size = opened.size

        truth_path = _safe_relative_path(root, record.get("expectedIr"), "expectedIr")
        _verified_hash(
            truth_path, record.get("expectedIrSha256"), "expectedIrSha256"
        )
        truth = _validate_truth(
            json.loads(truth_path.read_text(encoding="utf-8")),
            image_hash,
            image_size,
            case_id,
        )
        loaded_cases.append(
            record
            | {
                "_imagePath": image_path,
                "_truthPath": truth_path,
                "_truth": truth,
            }
        )
    return {
        "root": root,
        "manifestPath": manifest_path,
        "manifest": manifest,
        "cases": loaded_cases,
    }


def _match_components(
    expected: list[dict[str, Any]],
    actual: list[dict[str, Any]],
    tolerance: float,
) -> tuple[dict[str, str], Counter[str], Counter[str], Counter[str]]:
    adjacency: dict[int, list[tuple[float, int]]] = defaultdict(list)
    for expected_index, expected_component in enumerate(expected):
        for actual_index, actual_component in enumerate(actual):
            if expected_component.get("kind") != actual_component.get("kind"):
                continue
            expected_anchor = expected_component.get("anchor", [float("inf")] * 2)
            actual_anchor = actual_component.get("anchor", [float("inf")] * 2)
            if len(expected_anchor) != 2 or len(actual_anchor) != 2:
                continue
            distance = hypot(
                float(expected_anchor[0]) - float(actual_anchor[0]),
                float(expected_anchor[1]) - float(actual_anchor[1]),
            )
            if distance <= tolerance:
                adjacency[expected_index].append((distance, actual_index))
    actual_matches = _maximum_matching(adjacency, len(expected))
    actual_to_expected: dict[str, str] = {}
    matched_by_kind: Counter[str] = Counter()
    for actual_index, expected_index in actual_matches.items():
        expected_component = expected[expected_index]
        actual_component = actual[actual_index]
        actual_to_expected[str(actual_component.get("id"))] = str(
            expected_component["id"]
        )
        matched_by_kind[str(expected_component["kind"])] += 1
    expected_counts = Counter(str(item.get("kind")) for item in expected)
    actual_counts = Counter(str(item.get("kind")) for item in actual)
    false_positives = actual_counts - matched_by_kind
    false_negatives = expected_counts - matched_by_kind
    return actual_to_expected, matched_by_kind, false_positives, false_negatives


def _maximum_matching(
    adjacency: dict[int, list[tuple[float, int]]], left_size: int
) -> dict[int, int]:
    """Find a maximum-cardinality deterministic tolerance matching."""

    right_to_left: dict[int, int] = {}

    def assign(left: int, visited_right: set[int]) -> bool:
        for _, right in sorted(adjacency.get(left, [])):
            if right in visited_right:
                continue
            visited_right.add(right)
            previous = right_to_left.get(right)
            if previous is None or assign(previous, visited_right):
                right_to_left[right] = left
                return True
        return False

    left_order = sorted(range(left_size), key=lambda index: (len(adjacency[index]), index))
    for left in left_order:
        assign(left, set())
    return right_to_left


def _net_key(net: dict[str, Any], component_map: dict[str, str] | None) -> tuple[Any, ...]:
    endpoints = []
    for endpoint in net.get("endpoints", []):
        component_id = str(endpoint.get("componentId"))
        if component_map is not None:
            component_id = component_map.get(component_id, f"unmatched:{component_id}")
        endpoints.append((component_id, str(endpoint.get("portId"))))
    return tuple(sorted(endpoints))


def _match_points(
    expected: list[list[float]], actual: list[list[float]], tolerance: float
) -> tuple[int, int, int]:
    adjacency: dict[int, list[tuple[float, int]]] = defaultdict(list)
    for expected_index, expected_point in enumerate(expected):
        for actual_index, actual_point in enumerate(actual):
            if len(expected_point) != 2 or len(actual_point) != 2:
                continue
            distance = hypot(
                float(expected_point[0]) - float(actual_point[0]),
                float(expected_point[1]) - float(actual_point[1]),
            )
            if distance <= tolerance:
                adjacency[expected_index].append((distance, actual_index))
    true_positives = len(_maximum_matching(adjacency, len(expected)))
    return true_positives, len(actual) - true_positives, len(expected) - true_positives


def _match_topology(
    expected_nets: list[dict[str, Any]],
    actual_nets: list[dict[str, Any]],
    component_map: dict[str, str],
    tolerance: float,
) -> tuple[int, int, int, int, int, int]:
    unmatched_actual = set(range(len(actual_nets)))
    net_true_positives = 0
    junction_tp = 0
    junction_fp = 0
    junction_fn = 0
    for expected_net in expected_nets:
        expected_key = _net_key(expected_net, None)
        actual_index = next(
            (
                index
                for index in sorted(unmatched_actual)
                if _net_key(actual_nets[index], component_map) == expected_key
            ),
            None,
        )
        expected_junctions = expected_net.get("junctions", [])
        if actual_index is None:
            junction_fn += len(expected_junctions)
            continue
        unmatched_actual.remove(actual_index)
        net_true_positives += 1
        actual_junctions = actual_nets[actual_index].get("junctions", [])
        current_tp, current_fp, current_fn = _match_points(
            expected_junctions, actual_junctions, tolerance
        )
        junction_tp += current_tp
        junction_fp += current_fp
        junction_fn += current_fn
    for actual_index in unmatched_actual:
        junction_fp += len(actual_nets[actual_index].get("junctions", []))
    return (
        net_true_positives,
        len(actual_nets) - net_true_positives,
        len(expected_nets) - net_true_positives,
        junction_tp,
        junction_fp,
        junction_fn,
    )


def evaluate(
    dataset: Path,
    minimum_cases: int = 20,
    position_tolerance: float = 8.0,
    application: Any | None = None,
    official: bool = True,
) -> dict[str, Any]:
    """Evaluate a frozen set in official or post-result regression mode."""

    if position_tolerance <= 0:
        raise ValueError("position_tolerance must be positive")
    benchmark = load_benchmark(dataset, minimum_cases)
    owns_application = application is None
    if application is None:
        application = RecognitionApplication()
    runtime = application.health()
    detector_mode = str(runtime.get("detectorMode", ""))
    expected_runtime = benchmark["manifest"]["expectedRuntime"]
    if official and runtime.get("serviceVersion") != expected_runtime["serviceVersion"]:
        raise RuntimeError(
            "service version does not match the frozen benchmark: "
            f"expected {expected_runtime['serviceVersion']!r}, "
            f"got {runtime.get('serviceVersion')!r}"
        )
    if official and runtime.get("modelSha256") != expected_runtime["modelSha256"]:
        raise RuntimeError("model SHA-256 does not match the frozen benchmark")
    if official and not detector_mode.startswith(expected_runtime["detectorModePrefix"]):
        raise RuntimeError(
            "detector mode does not match the frozen benchmark: "
            f"expected prefix {expected_runtime['detectorModePrefix']!r}, "
            f"got {detector_mode!r}"
        )
    if owns_application and not detector_mode.startswith("onnx-9-class"):
        raise RuntimeError(
            "independent evaluation requires the frozen 9-class ONNX detector; "
            f"current mode is {detector_mode!r}"
        )

    totals = Counter()
    per_class: dict[str, Counter[str]] = defaultdict(Counter)
    component_count_exact = 0
    component_localization_exact = 0
    unresolved_free = 0
    exact_graph = 0
    failures: list[dict[str, Any]] = []
    started = perf_counter()
    for record in benchmark["cases"]:
        truth = record["_truth"]
        expected_components = truth["components"]
        expected_nets = truth["nets"]
        case_started = perf_counter()
        exception_text = None
        try:
            actual = application.recognize(record["_imagePath"].read_bytes())
        except Exception as exception:  # noqa: BLE001 - failures are evaluation data
            actual = {"components": [], "nets": [], "unresolved": []}
            exception_text = f"{type(exception).__name__}: {exception}"
        elapsed_ms = (perf_counter() - case_started) * 1000.0
        actual_components = actual.get("components", [])
        actual_nets = actual.get("nets", [])
        unresolved = actual.get("unresolved", [])

        component_map, matched, component_fp, component_fn = _match_components(
            expected_components, actual_components, position_tolerance
        )
        expected_counts = Counter(item["kind"] for item in expected_components)
        actual_counts = Counter(item.get("kind") for item in actual_components)
        count_exact = expected_counts == actual_counts
        localized_exact = (
            len(component_map) == len(expected_components) == len(actual_components)
        )
        component_count_exact += int(count_exact)
        component_localization_exact += int(localized_exact)
        for kind in set(expected_counts) | set(actual_counts):
            per_class[str(kind)]["tp"] += matched[kind]
            per_class[str(kind)]["fp"] += component_fp[kind]
            per_class[str(kind)]["fn"] += component_fn[kind]

        net_tp, net_fp, net_fn, junction_tp, junction_fp, junction_fn = (
            _match_topology(
                expected_nets,
                actual_nets,
                component_map,
                position_tolerance,
            )
        )
        totals.update(
            {
                "componentTp": sum(matched.values()),
                "componentFp": sum(component_fp.values()),
                "componentFn": sum(component_fn.values()),
                "netTp": net_tp,
                "netFp": net_fp,
                "netFn": net_fn,
                "junctionTp": junction_tp,
                "junctionFp": junction_fp,
                "junctionFn": junction_fn,
            }
        )
        clean = exception_text is None and not unresolved
        unresolved_free += int(clean)
        graph_exact = (
            clean
            and localized_exact
            and net_fp == 0
            and net_fn == 0
            and junction_fp == 0
            and junction_fn == 0
        )
        exact_graph += int(graph_exact)
        if not graph_exact and len(failures) < 100:
            failures.append(
                {
                    "id": record["id"],
                    "image": record["image"],
                    "exception": exception_text,
                    "expectedComponentCounts": dict(sorted(expected_counts.items())),
                    "actualComponentCounts": dict(sorted(actual_counts.items())),
                    "componentTp": sum(matched.values()),
                    "componentFp": sum(component_fp.values()),
                    "componentFn": sum(component_fn.values()),
                    "netTp": net_tp,
                    "netFp": net_fp,
                    "netFn": net_fn,
                    "junctionTp": junction_tp,
                    "junctionFp": junction_fp,
                    "junctionFn": junction_fn,
                    "unresolved": unresolved,
                    "elapsedMilliseconds": elapsed_ms,
                }
            )

    elapsed = perf_counter() - started
    sample_count = len(benchmark["cases"])
    class_metrics: dict[str, dict[str, int | float]] = {}
    for kind in FULL_CLASS_NAMES:
        counts = per_class[kind]
        class_metrics[kind] = {
            "tp": counts["tp"],
            "fp": counts["fp"],
            "fn": counts["fn"],
            "precision": ratio(counts["tp"], counts["tp"] + counts["fp"]),
            "recall": ratio(counts["tp"], counts["tp"] + counts["fn"]),
            "f1": f1_score(counts["tp"], counts["fp"], counts["fn"]),
        }
    junction_f1 = (
        f1_score(
            totals["junctionTp"], totals["junctionFp"], totals["junctionFn"]
        )
        if totals["junctionTp"] + totals["junctionFp"] + totals["junctionFn"]
        else None
    )
    represented_classes = [
        kind for kind in FULL_CLASS_NAMES if per_class[kind]["tp"] + per_class[kind]["fn"]
    ]
    minimum_class_recall = min(
        (class_metrics[kind]["recall"] for kind in represented_classes),
        default=0.0,
    )
    overall = {
        **totals,
        "componentPrecision": ratio(
            totals["componentTp"], totals["componentTp"] + totals["componentFp"]
        ),
        "componentRecall": ratio(
            totals["componentTp"], totals["componentTp"] + totals["componentFn"]
        ),
        "componentF1": f1_score(
            totals["componentTp"], totals["componentFp"], totals["componentFn"]
        ),
        "componentCountExactImages": component_count_exact,
        "componentCountExactRate": ratio(component_count_exact, sample_count),
        "componentLocalizationExactImages": component_localization_exact,
        "componentLocalizationExactRate": ratio(
            component_localization_exact, sample_count
        ),
        "netPrecision": ratio(totals["netTp"], totals["netTp"] + totals["netFp"]),
        "netRecall": ratio(totals["netTp"], totals["netTp"] + totals["netFn"]),
        "netF1": f1_score(totals["netTp"], totals["netFp"], totals["netFn"]),
        "junctionF1": junction_f1,
        "unresolvedFreeImages": unresolved_free,
        "unresolvedFreeRate": ratio(unresolved_free, sample_count),
        "exactGraphImages": exact_graph,
        "exactGraphRate": ratio(exact_graph, sample_count),
        "elapsedSeconds": elapsed,
        "millisecondsPerImage": elapsed * 1000.0 / sample_count,
    }
    coverage_eligible = (
        sample_count >= 20
        and len(represented_classes) == len(FULL_CLASS_NAMES)
        and junction_f1 is not None
    )
    eligible = official and coverage_eligible
    acceptance_passed = (
        minimum_class_recall >= 0.98
        and overall["netF1"] >= 0.98
        and junction_f1 is not None
        and junction_f1 >= 0.95
        and overall["exactGraphRate"] >= 0.90
        and overall["millisecondsPerImage"] <= 5000.0
    )
    return {
        "evaluationVersion": "real-holdout-eval/0.1",
        "evaluationMode": "official-independent" if official else "post-result-regression",
        "benchmark": {
            "path": str(benchmark["root"]),
            "schemaVersion": BENCHMARK_SCHEMA,
            "manifestSha256": file_sha256(benchmark["manifestPath"]),
            "samples": sample_count,
            "frozenAt": benchmark["manifest"]["frozenAt"],
            "declarations": benchmark["manifest"]["declarations"],
            "expectedRuntime": expected_runtime,
        },
        "runtime": runtime,
        "settings": {
            "minimumCases": minimum_cases,
            "positionTolerancePixels": position_tolerance,
            "official": official,
        },
        "overall": overall,
        "classes": class_metrics,
        "acceptance": {
            "eligible": eligible,
            "passed": acceptance_passed if eligible else None,
            "coverageEligible": coverage_eligible,
            "representedClasses": represented_classes,
            "minimumRepresentedClassRecall": minimum_class_recall,
            "targets": {
                "minimumClassRecall": 0.98,
                "netF1": 0.98,
                "junctionF1": 0.95,
                "exactGraphRate": 0.90,
                "millisecondsPerImage": 5000.0,
            },
        },
        "failures": failures,
        "limitations": [
            "The benchmark only covers the capture profiles declared in its manifest.",
            "A model or threshold change invalidates this benchmark for unbiased final evaluation.",
            "Segment geometry is diagnostic; exact graph matching uses localized components, port endpoints, nets and junctions.",
            *(
                [
                    "Regression mode was used after the official result was observed; this result cannot support an independent acceptance claim."
                ]
                if not official
                else []
            ),
        ],
    }


__all__ = ["BENCHMARK_SCHEMA", "evaluate", "file_sha256", "load_benchmark"]
