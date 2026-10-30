"""Evaluate native wire and topology recovery with ground-truth component boxes."""

from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import sys
from time import perf_counter
from typing import Any

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.template_detector import TemplateDetection  # noqa: E402
from logisim_vision.topology import FixedScaleTopologyRecognizer  # noqa: E402
from logisim_vision.app import RecognitionApplication  # noqa: E402


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def rounded_point(values: list[float]) -> tuple[int, int]:
    return round(values[0]), round(values[1])


def component_descriptor(component: dict[str, Any]) -> tuple[Any, ...]:
    return (
        component["kind"],
        rounded_point(component["anchor"]),
        tuple(
            sorted(
                (
                    port["id"],
                    port["direction"],
                    rounded_point(port["point"]),
                )
                for port in component["ports"]
            )
        ),
    )


def unit_edges(segments: list[list[float]], spacing: int = 10) -> tuple[Any, ...]:
    edges: set[tuple[tuple[int, int], tuple[int, int]]] = set()
    for segment in segments:
        x0, y0, x1, y1 = (round(value) for value in segment)
        if x0 == x1:
            start, end = sorted((y0, y1))
            points = [(x0, value) for value in range(start, end + 1, spacing)]
        else:
            start, end = sorted((x0, x1))
            points = [(value, y0) for value in range(start, end + 1, spacing)]
        for first, second in zip(points, points[1:], strict=False):
            edges.add(tuple(sorted((first, second))))
    return tuple(sorted(edges))


def normalized_graph(document: dict[str, Any]) -> tuple[Any, ...]:
    components = {
        component["id"]: component_descriptor(component)
        for component in document["components"]
    }
    nets = []
    for net in document["nets"]:
        endpoints = tuple(
            sorted(
                (components[endpoint["componentId"]], endpoint["portId"])
                for endpoint in net["endpoints"]
            )
        )
        junctions = tuple(sorted(rounded_point(point) for point in net["junctions"]))
        nets.append((endpoints, junctions, unit_edges(net["segments"])))
    return tuple(sorted(components.values())), tuple(sorted(nets))


def evaluate(dataset: Path, split: str, end_to_end: bool = False) -> dict[str, Any]:
    dataset = dataset.resolve()
    manifest_path = dataset / "manifest.jsonl"
    records = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    selected = [record for record in records if record.get("split") == split]
    if not selected:
        raise ValueError(f"dataset has no records in split {split!r}")

    recognizer = FixedScaleTopologyRecognizer()
    application = RecognitionApplication() if end_to_end else None
    exact = 0
    unresolved_free = 0
    failures: list[dict[str, Any]] = []
    started = perf_counter()
    for record in selected:
        truth_path = dataset / record["ir"]
        truth = json.loads(truth_path.read_text(encoding="utf-8"))
        image_path = dataset / record["image"]
        try:
            if application is not None:
                actual = application.recognize(image_path.read_bytes())
            else:
                detections = [
                    TemplateDetection(
                        component["kind"],
                        tuple(round(value) for value in component["bodyBox"]),
                        1.0,
                    )
                    for component in truth["components"]
                ]
                with Image.open(image_path) as image:
                    actual = recognizer.recognize(
                        image,
                        record["imageSha256"],
                        detections=detections,
                    )
        except Exception as exception:  # noqa: BLE001 - evaluation records failures
            if len(failures) < 50:
                failures.append(
                    {
                        "image": record["image"],
                        "family": record["family"],
                        "renderProfile": record["renderProfile"],
                        "exception": f"{type(exception).__name__}: {exception}",
                    }
                )
            continue
        if not actual["unresolved"]:
            unresolved_free += 1
        matches = not actual["unresolved"] and normalized_graph(actual) == normalized_graph(truth)
        if matches:
            exact += 1
        elif len(failures) < 50:
            failures.append(
                {
                    "image": record["image"],
                    "family": record["family"],
                    "renderProfile": record["renderProfile"],
                    "unresolved": actual["unresolved"],
                    "expectedComponents": len(truth["components"]),
                    "actualComponents": len(actual["components"]),
                    "expectedNets": len(truth["nets"]),
                    "actualNets": len(actual["nets"]),
                }
            )

    elapsed = perf_counter() - started
    return {
        "evaluationVersion": "native-topology-eval/0.1",
        "mode": "onnx-end-to-end" if end_to_end else "ground-truth-component-boxes",
        "runtime": application.health() if application is not None else None,
        "dataset": {
            "path": str(dataset),
            "manifestSha256": file_sha256(manifest_path),
            "split": split,
            "samples": len(selected),
            "uniqueSemanticHashes": len(
                {record["semanticHash"] for record in selected}
            ),
        },
        "overall": {
            "unresolvedFreeImages": unresolved_free,
            "unresolvedFreeRate": unresolved_free / len(selected),
            "exactGraphImages": exact,
            "exactGraphRate": exact / len(selected),
            "elapsedSeconds": elapsed,
            "millisecondsPerImage": elapsed * 1000.0 / len(selected),
        },
        "failures": failures,
        "limitations": (
            [
                "This is an end-to-end evaluation on native generated images.",
                "It does not replace an independently collected real screenshot benchmark.",
            ]
            if end_to_end
            else [
                "This isolates wire, port and topology recovery by using ground-truth component boxes.",
                "It is not an end-to-end detector evaluation.",
            ]
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--split", choices=("train", "val", "test"), default="test")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--end-to-end", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"output already exists: {args.output}")
    result = evaluate(args.dataset, args.split, args.end_to_end)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result["dataset"] | result["overall"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
