"""Build independent holdout truth from a Logisim ``.circ`` source project.

The learned detector is deliberately not used here.  Circuit semantics come
from the Logisim XML, while a deterministic green-wire/grid alignment maps
the source coordinates into a cropped canvas screenshot.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
import re
from typing import Iterable
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw

from .app import SERVICE_VERSION
from .real_holdout import BENCHMARK_SCHEMA, file_sha256
from .wire_extractor import GridProfile, OrthogonalWireExtractor


_POINT = re.compile(r"\((-?\d+),(-?\d+)\)")
_GATE_KINDS = {
    "AND Gate": "AND",
    "OR Gate": "OR",
    "NOT Gate": "NOT",
    "NAND Gate": "NAND",
    "NOR Gate": "NOR",
    "XOR Gate": "XOR",
    "XNOR Gate": "XNOR",
}


Point = tuple[int, int]


@dataclass(frozen=True)
class Segment:
    first: Point
    second: Point

    def __post_init__(self) -> None:
        if self.first[0] != self.second[0] and self.first[1] != self.second[1]:
            raise ValueError(f"non-orthogonal Logisim wire: {self}")

    def contains(self, point: Point) -> bool:
        return (
            min(self.first[0], self.second[0]) <= point[0] <= max(self.first[0], self.second[0])
            and min(self.first[1], self.second[1]) <= point[1] <= max(self.first[1], self.second[1])
            and (
                point[0] == self.first[0]
                if self.first[0] == self.second[0]
                else point[1] == self.first[1]
            )
        )

    @property
    def endpoints(self) -> frozenset[Point]:
        return frozenset((self.first, self.second))


@dataclass(frozen=True)
class Port:
    id: str
    direction: str
    point: Point


@dataclass(frozen=True)
class SourceComponent:
    id: str
    kind: str
    label: str
    anchor: Point
    ports: tuple[Port, ...]
    attributes: dict[str, object]


@dataclass(frozen=True)
class Alignment:
    profile: GridProfile
    shift: Point
    matched_edges: int
    total_edges: int

    @property
    def overlap(self) -> float:
        return self.matched_edges / self.total_edges if self.total_edges else 0.0

    def image_point(self, point: Point) -> tuple[float, float]:
        node = (
            point[0] // 10 + self.shift[0],
            point[1] // 10 + self.shift[1],
        )
        return self.profile.image_point(node)


class _UnionFind:
    def __init__(self, size: int) -> None:
        self.parents = list(range(size))

    def find(self, value: int) -> int:
        while self.parents[value] != value:
            self.parents[value] = self.parents[self.parents[value]]
            value = self.parents[value]
        return value

    def union(self, first: int, second: int) -> None:
        first_root = self.find(first)
        second_root = self.find(second)
        if first_root != second_root:
            self.parents[second_root] = first_root


def _point(value: str) -> Point:
    matched = _POINT.fullmatch(value)
    if matched is None:
        raise ValueError(f"invalid Logisim point: {value!r}")
    result = (int(matched.group(1)), int(matched.group(2)))
    if result[0] % 10 or result[1] % 10:
        raise ValueError(f"holdout source is not on the ten-unit grid: {result}")
    return result


def _attributes(element: ET.Element) -> dict[str, str]:
    return {
        item.attrib["name"]: item.attrib.get("val", "")
        for item in element.findall("a")
    }


def load_circuits(path: Path) -> dict[str, ET.Element]:
    root = ET.parse(path).getroot()
    return {
        circuit.attrib["name"]: circuit
        for circuit in root.findall("circuit")
        if circuit.attrib.get("name")
    }


def circuit_segments(circuit: ET.Element) -> list[Segment]:
    return [
        Segment(_point(wire.attrib["from"]), _point(wire.attrib["to"]))
        for wire in circuit.findall("wire")
    ]


def _gate_input_points(anchor: Point, kind: str, segments: Iterable[Segment]) -> list[Point]:
    expected_y = {anchor[1]} if kind == "NOT" else {anchor[1] - 10, anchor[1] + 10}
    candidates = {
        endpoint
        for segment in segments
        for endpoint in segment.endpoints
        if endpoint[1] in expected_y
        and anchor[0] - 70 <= endpoint[0] < anchor[0]
    }
    selected: list[Point] = []
    for y_value in sorted(expected_y):
        on_row = [point for point in candidates if point[1] == y_value]
        if not on_row:
            raise ValueError(f"cannot locate {kind} input at y={y_value} near {anchor}")
        selected.append(max(on_row, key=lambda point: point[0]))
    return selected


def circuit_components(circuit: ET.Element, segments: list[Segment]) -> list[SourceComponent]:
    raw: list[tuple[str, str, Point, tuple[Port, ...], dict[str, object]]] = []
    for element in circuit.findall("comp"):
        name = element.attrib.get("name", "")
        anchor = _point(element.attrib["loc"])
        attrs = _attributes(element)
        label = attrs.get("label", "")
        attributes: dict[str, object] = {"bitWidth": 1}
        if name == "Pin":
            output_pin = attrs.get("type") == "output"
            kind = "OUTPUT_PIN" if output_pin else "INPUT_PIN"
            ports = (
                Port("in" if output_pin else "out", "INPUT" if output_pin else "OUTPUT", anchor),
            )
        elif name in _GATE_KINDS:
            kind = _GATE_KINDS[name]
            input_points = _gate_input_points(anchor, kind, segments)
            ports = (Port("out", "OUTPUT", anchor),) + tuple(
                Port(f"in{index}", "INPUT", point)
                for index, point in enumerate(input_points)
            )
            attributes["inputs"] = len(input_points)
            attributes["size"] = "WIDE" if kind == "NOT" else "NARROW"
        else:
            raise ValueError(f"unsupported holdout component: {name!r}")
        raw.append((kind, label, anchor, ports, attributes))

    counters: Counter[str] = Counter()
    result: list[SourceComponent] = []
    for kind, label, anchor, ports, attributes in sorted(
        raw, key=lambda item: (item[2][0], item[2][1], item[0])
    ):
        counters[kind] += 1
        result.append(
            SourceComponent(
                f"{kind.lower()}_{counters[kind]:03d}",
                kind,
                label,
                anchor,
                ports,
                attributes,
            )
        )
    return result


def _intersection(first: Segment, second: Segment) -> Point | None:
    if first.first[0] == first.second[0] and second.first[1] == second.second[1]:
        point = (first.first[0], second.first[1])
    elif first.first[1] == first.second[1] and second.first[0] == second.second[0]:
        point = (second.first[0], first.first[1])
    else:
        return None
    return point if first.contains(point) and second.contains(point) else None


def _segments_connect(first: Segment, second: Segment) -> bool:
    first_vertical = first.first[0] == first.second[0]
    second_vertical = second.first[0] == second.second[0]
    if first_vertical == second_vertical:
        if first_vertical and first.first[0] != second.first[0]:
            return False
        if not first_vertical and first.first[1] != second.first[1]:
            return False
        first_values = (first.first[1], first.second[1]) if first_vertical else (first.first[0], first.second[0])
        second_values = (second.first[1], second.second[1]) if second_vertical else (second.first[0], second.second[0])
        return max(min(first_values), min(second_values)) <= min(max(first_values), max(second_values))
    crossing = _intersection(first, second)
    if crossing is None:
        return False
    # Interior/interior crossings are bridges in Logisim.  A T endpoint or a
    # shared endpoint is a real electrical junction.
    return crossing in first.endpoints or crossing in second.endpoints


def _net_groups(segments: list[Segment]) -> list[list[Segment]]:
    union = _UnionFind(len(segments))
    for first_index, first in enumerate(segments):
        for second_index in range(first_index + 1, len(segments)):
            if _segments_connect(first, segments[second_index]):
                union.union(first_index, second_index)
    groups: dict[int, list[Segment]] = defaultdict(list)
    for index, segment in enumerate(segments):
        groups[union.find(index)].append(segment)
    return list(groups.values())


def _junctions(segments: list[Segment], ports: Iterable[Port]) -> list[Point]:
    candidates = {point for segment in segments for point in segment.endpoints}
    candidates.update(port.point for port in ports)
    for first_index, first in enumerate(segments):
        for second in segments[first_index + 1 :]:
            if _segments_connect(first, second):
                crossing = _intersection(first, second)
                if crossing is not None:
                    candidates.add(crossing)

    edges: set[tuple[Point, Point]] = set()
    for segment in segments:
        points = [point for point in candidates if segment.contains(point)]
        points.sort(key=lambda point: (point[1], point[0]) if segment.first[0] == segment.second[0] else (point[0], point[1]))
        for first, second in zip(points, points[1:]):
            if first != second:
                edges.add(tuple(sorted((first, second))))
    degree: Counter[Point] = Counter()
    for first, second in edges:
        degree[first] += 1
        degree[second] += 1
    return sorted(point for point, count in degree.items() if count >= 3)


def _unit_edges(segments: Iterable[Segment]) -> set[tuple[Point, Point]]:
    result: set[tuple[Point, Point]] = set()
    for segment in segments:
        first = (segment.first[0] // 10, segment.first[1] // 10)
        second = (segment.second[0] // 10, segment.second[1] // 10)
        if first[0] == second[0]:
            for y_value in range(min(first[1], second[1]), max(first[1], second[1])):
                result.add(((first[0], y_value), (first[0], y_value + 1)))
        else:
            for x_value in range(min(first[0], second[0]), max(first[0], second[0])):
                result.add(((x_value, first[1]), (x_value + 1, first[1])))
    return result


def align_source(image: Image.Image, segments: list[Segment]) -> Alignment:
    extractor = OrthogonalWireExtractor()
    profile = extractor.estimate_grid(image)
    extraction = extractor.extract(image, [], profile)
    observed = {edge for region in extraction.regions for edge in region.edges}
    source = _unit_edges(segments)
    votes: Counter[Point] = Counter()
    for first, second in source:
        orientation = (second[0] - first[0], second[1] - first[1])
        for observed_first, observed_second in observed:
            observed_orientation = (
                observed_second[0] - observed_first[0],
                observed_second[1] - observed_first[1],
            )
            if observed_orientation == orientation:
                votes[(observed_first[0] - first[0], observed_first[1] - first[1])] += 1
    if not votes:
        raise ValueError("source wires could not be aligned to the screenshot")
    shift = votes.most_common(1)[0][0]
    matched = sum(
        (
            (first[0] + shift[0], first[1] + shift[1]),
            (second[0] + shift[0], second[1] + shift[1]),
        )
        in observed
        for first, second in source
    )
    alignment = Alignment(profile, shift, matched, len(source))
    if alignment.overlap < 0.55:
        raise ValueError(f"wire alignment overlap is too low: {alignment.overlap:.3f}")
    return alignment


def _body_box(component: SourceComponent, alignment: Alignment) -> list[float]:
    anchor_x, anchor_y = alignment.image_point(component.anchor)
    spacing = alignment.profile.spacing
    if component.kind == "INPUT_PIN":
        return [anchor_x - 4 * spacing, anchor_y - spacing, anchor_x, anchor_y + spacing]
    if component.kind == "OUTPUT_PIN":
        return [anchor_x, anchor_y - spacing, anchor_x + 4 * spacing, anchor_y + spacing]
    inputs = [alignment.image_point(port.point) for port in component.ports if port.direction == "INPUT"]
    return [
        min(point[0] for point in inputs),
        min(point[1] for point in inputs) - spacing,
        anchor_x,
        max(point[1] for point in inputs) + spacing,
    ]


def _rounded(point: tuple[float, float]) -> list[float]:
    return [round(point[0], 3), round(point[1], 3)]


def build_truth(
    image_path: Path,
    circuit: ET.Element,
) -> tuple[dict[str, object], Alignment]:
    segments = circuit_segments(circuit)
    components = circuit_components(circuit, segments)
    with Image.open(image_path) as opened:
        image = opened.convert("RGB")
        alignment = align_source(image, segments)
        width, height = image.size

    component_by_port: list[tuple[SourceComponent, Port]] = [
        (component, port)
        for component in components
        for port in component.ports
    ]
    nets: list[dict[str, object]] = []
    for group in _net_groups(segments):
        connected = [
            (component, port)
            for component, port in component_by_port
            if any(segment.contains(port.point) for segment in group)
        ]
        if not connected:
            raise ValueError("source contains a wire group without a component port")
        if len(connected) < 2:
            raise ValueError("source contains a wire group with fewer than two component ports")
        drivers = [item for item in connected if item[1].direction == "OUTPUT"]
        if len(drivers) != 1:
            raise ValueError(f"source wire group has {len(drivers)} drivers")
        all_ports = [port for _, port in connected]
        nets.append(
            {
                "endpoints": [
                    {"componentId": component.id, "portId": port.id}
                    for component, port in sorted(
                        connected,
                        key=lambda item: (
                            item[1].direction != "OUTPUT",
                            item[0].id,
                            item[1].id,
                        ),
                    )
                ],
                "segments": [
                    _rounded(alignment.image_point(segment.first))
                    + _rounded(alignment.image_point(segment.second))
                    for segment in group
                ],
                "junctions": [
                    _rounded(alignment.image_point(point))
                    for point in _junctions(group, all_ports)
                ],
                "confidence": 1.0,
            }
        )

    nets.sort(key=lambda net: json.dumps(net["endpoints"], sort_keys=True))
    for index, net in enumerate(nets, start=1):
        net["id"] = f"net_{index:03d}"
    ir_components = [
        {
            "id": component.id,
            "kind": component.kind,
            "bodyBox": [round(value, 3) for value in _body_box(component, alignment)],
            "anchor": _rounded(alignment.image_point(component.anchor)),
            "facing": "EAST",
            "attributes": component.attributes,
            "ports": [
                {
                    "id": port.id,
                    "direction": port.direction,
                    "point": _rounded(alignment.image_point(port.point)),
                }
                for port in component.ports
            ],
            "confidence": 1.0,
            **({"label": component.label} if component.label else {}),
        }
        for component in components
    ]
    semantic_payload = {
        "components": [
            (component["id"], component["kind"], component["anchor"])
            for component in ir_components
        ],
        "nets": [net["endpoints"] for net in nets],
    }
    return (
        {
            "schemaVersion": "vision-ir/0.1",
            "source": {
                "sha256": file_sha256(image_path),
                "width": width,
                "height": height,
            },
            "profile": {
                "theme": "light",
                "gateShape": "ansi",
                "gridPixels": alignment.profile.spacing,
                "scale": alignment.profile.scale,
                "offsetX": alignment.profile.offset_x,
                "offsetY": alignment.profile.offset_y,
            },
            "components": ir_components,
            "nets": nets,
            "unresolved": [],
            "semanticHash": sha256(
                json.dumps(semantic_payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
            ).hexdigest(),
        },
        alignment,
    )


def render_overlay(image_path: Path, truth: dict[str, object], output: Path) -> None:
    with Image.open(image_path) as opened:
        image = opened.convert("RGB")
    draw = ImageDraw.Draw(image)
    for net in truth["nets"]:  # type: ignore[index]
        for x_value, y_value in net["junctions"]:  # type: ignore[index]
            draw.ellipse(
                (x_value - 5, y_value - 5, x_value + 5, y_value + 5),
                outline=(255, 0, 0),
                width=2,
            )
    for component in truth["components"]:  # type: ignore[index]
        x_value, y_value = component["anchor"]  # type: ignore[index]
        draw.line((x_value - 5, y_value, x_value + 5, y_value), fill=(255, 0, 255), width=2)
        draw.line((x_value, y_value - 5, x_value, y_value + 5), fill=(255, 0, 255), width=2)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG")


def render_contact_sheet(overlays: list[Path], output: Path) -> None:
    columns = 4
    cell_width = 320
    cell_height = 250
    rows = (len(overlays) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * cell_width, rows * cell_height), "white")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(overlays):
        with Image.open(path) as opened:
            preview = opened.convert("RGB")
        preview.thumbnail((cell_width - 20, cell_height - 30))
        left = (index % columns) * cell_width + (cell_width - preview.width) // 2
        top = (index // columns) * cell_height + 25
        sheet.paste(preview, (left, top))
        draw.text(
            (
                (index % columns) * cell_width + 8,
                (index // columns) * cell_height + 5,
            ),
            path.stem,
            fill="black",
        )
    sheet.save(output, format="PNG")


def prepare_dataset(
    dataset: Path,
    source_project: Path,
    expression_plan: Path,
    model_path: Path,
) -> dict[str, object]:
    circuits = load_circuits(source_project)
    plan = json.loads(expression_plan.read_text(encoding="utf-8"))
    expected_cases = {record["id"]: record for record in plan["cases"]}
    images = sorted((dataset / "images").glob("R*.png"))
    if len(images) < 20:
        raise ValueError("at least 20 images are required")
    if not model_path.is_file():
        raise FileNotFoundError(model_path)

    prepared: list[tuple[Path, dict[str, object], Alignment]] = []
    for image_path in images:
        case_id = image_path.stem
        if case_id not in circuits or case_id not in expected_cases:
            raise ValueError(f"missing source circuit or plan case for {case_id}")
        truth, alignment = build_truth(image_path, circuits[case_id])
        expected_gates = Counter(expected_cases[case_id]["expectedGateCounts"])
        component_counts = Counter(
            component["kind"]
            for component in truth["components"]  # type: ignore[index]
        )
        actual_gates = component_counts.copy()
        del actual_gates["INPUT_PIN"]
        del actual_gates["OUTPUT_PIN"]
        if expected_gates != actual_gates:
            raise ValueError(
                f"{case_id} gate counts disagree with the frozen expression plan: "
                f"expected {expected_gates}, got {actual_gates}"
            )
        expected_inputs = len(expected_cases[case_id]["inputs"])
        if component_counts["INPUT_PIN"] != expected_inputs:
            raise ValueError(
                f"{case_id} input pin count disagrees with the frozen expression plan: "
                f"expected {expected_inputs}, got {component_counts['INPUT_PIN']}"
            )
        if component_counts["OUTPUT_PIN"] != 1:
            raise ValueError(
                f"{case_id} must contain exactly one output pin, "
                f"got {component_counts['OUTPUT_PIN']}"
            )
        all_ports = {
            (component["id"], port["id"])
            for component in truth["components"]  # type: ignore[index]
            for port in component["ports"]
        }
        connected_ports = {
            (endpoint["componentId"], endpoint["portId"])
            for net in truth["nets"]  # type: ignore[index]
            for endpoint in net["endpoints"]
        }
        if all_ports != connected_ports:
            missing_ports = sorted(all_ports - connected_ports)
            extra_ports = sorted(connected_ports - all_ports)
            raise ValueError(
                f"{case_id} port coverage mismatch: "
                f"unconnected={missing_ports}, unknown={extra_ports}"
            )
        prepared.append((image_path, truth, alignment))

    # Do not leave a half-updated draft when a later case fails validation.
    annotations = dataset / "annotations"
    overlays = dataset / "review-overlays"
    annotations.mkdir(parents=True, exist_ok=True)
    cases: list[dict[str, object]] = []
    overlay_paths: list[Path] = []
    for image_path, truth, alignment in prepared:
        case_id = image_path.stem
        annotation_path = annotations / f"{case_id}.json"
        annotation_path.write_text(
            json.dumps(truth, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        overlay_path = overlays / image_path.name
        render_overlay(image_path, truth, overlay_path)
        overlay_paths.append(overlay_path)
        cases.append(
            {
                "id": case_id,
                "image": f"images/{image_path.name}",
                "imageSha256": file_sha256(image_path),
                "expectedIr": f"annotations/{annotation_path.name}",
                "expectedIrSha256": file_sha256(annotation_path),
                "capture": {
                    "applicationVersion": "Logisim-evolution v5.1.0dev",
                    "method": "cropped-canvas-screenshot",
                    "theme": "light",
                    "scalePercent": round(alignment.profile.scale * 100),
                    "collector": "project-author",
                    "sourceCircuit": f"source/{source_project.name}#{case_id}",
                    "wireAlignmentOverlap": round(alignment.overlap, 6),
                    "notes": "Truth derived from the saved .circ source without running the learned recognizer.",
                },
            }
        )

    render_contact_sheet(overlay_paths, dataset / "review-contact-sheet.png")
    manifest = {
        "schemaVersion": BENCHMARK_SCHEMA,
        "datasetRole": "independent-holdout",
        "frozen": False,
        "frozenAt": "",
        "declarations": {
            "modelAndThresholdsFrozenBeforeCollection": True,
            "notUsedForTraining": True,
            "notUsedForThresholdSelection": True,
            "annotationCompletedBeforeEvaluation": False,
        },
        "expectedRuntime": {
            "serviceVersion": SERVICE_VERSION,
            "modelSha256": file_sha256(model_path),
            "detectorModePrefix": "onnx-9-class",
        },
        "cases": cases,
    }
    (dataset / "manifest.draft.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest


__all__ = [
    "Alignment",
    "build_truth",
    "circuit_components",
    "circuit_segments",
    "load_circuits",
    "prepare_dataset",
]
