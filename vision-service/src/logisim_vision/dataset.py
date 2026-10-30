"""Deterministic synthetic data generation for the one-week vision prototype.

The generator deliberately supports only the first delivery profile: east-facing,
single-bit, point-to-point combinational circuits with one output.  It uses image
assets already shipped by Logisim-evolution instead of drawing look-alike gates.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass, field
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path
import platform
import random
from typing import Iterable, Sequence

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
from PIL import __version__ as PILLOW_VERSION


GENERATOR_VERSION = "week1-dataset/0.1"
SCHEMA_VERSION = "vision-ir/0.1"
CANVAS_WIDTH = 800
CANVAS_HEIGHT = 520
GRID = 10

CLASS_NAMES = (
    "INPUT_PIN",
    "OUTPUT_PIN",
    "AND",
    "OR",
    "NOT",
    "NAND",
    "NOR",
)
CLASS_IDS = {name: index for index, name in enumerate(CLASS_NAMES)}
GATE_KINDS = ("AND", "OR", "NOT", "NAND", "NOR")
BINARY_KINDS = frozenset(("AND", "OR", "NAND", "NOR"))


@dataclass(frozen=True)
class AssetSpec:
    filename: str
    scale: float
    source_anchor: tuple[float, float]


# Source anchors are the centers of the connection markers in the documentation
# renders.  The chosen scales reproduce Logisim's 50/60-unit gate lengths and
# 20-unit input spacing to within one antialiased pixel.
ASSET_SPECS = {
    "INPUT_PIN": AssetSpec("pinin1.png", 0.5, (106.0, 63.5)),
    "OUTPUT_PIN": AssetSpec("pinout1.png", 0.5, (23.0, 64.0)),
    "AND": AssetSpec("ansandgate.png", 5.0 / 9.0, (100.5, 63.5)),
    "OR": AssetSpec("ansorgate.png", 5.0 / 9.0, (96.5, 62.5)),
    "NOT": AssetSpec("ansnotgate.png", 30.0 / 106.0, (116.0, 61.0)),
    "NAND": AssetSpec("ansnandgate.png", 5.0 / 9.0, (114.5, 64.5)),
    "NOR": AssetSpec("ansnorgate.png", 5.0 / 9.0, (114.5, 60.5)),
}


@dataclass
class ExprNode:
    kind: str
    children: list["ExprNode"] = field(default_factory=list)
    component_id: str = ""
    depth: int = 0
    anchor: tuple[int, int] = (0, 0)


@dataclass(frozen=True)
class Component:
    component_id: str
    kind: str
    anchor: tuple[int, int]
    attributes: dict[str, object]


@dataclass(frozen=True)
class Net:
    net_id: str
    source_id: str
    source_port: str
    sink_id: str
    sink_port: str
    segments: tuple[tuple[int, int, int, int], ...]


class TemplateLibrary:
    """Loads and normalizes the native Logisim documentation images once."""

    def __init__(self, asset_root: Path):
        self._sprites: dict[str, tuple[Image.Image, tuple[float, float]]] = {}
        missing = [spec.filename for spec in ASSET_SPECS.values() if not (asset_root / spec.filename).is_file()]
        if missing:
            raise FileNotFoundError(
                f"Logisim image assets are missing from {asset_root}: {', '.join(missing)}"
            )
        for kind, spec in ASSET_SPECS.items():
            source = Image.open(asset_root / spec.filename).convert("RGB")
            sprite = self._white_to_transparent(source)
            width = max(1, round(sprite.width * spec.scale))
            height = max(1, round(sprite.height * spec.scale))
            sprite = sprite.resize((width, height), Image.Resampling.LANCZOS)
            anchor = (spec.source_anchor[0] * spec.scale, spec.source_anchor[1] * spec.scale)
            self._sprites[kind] = (sprite, anchor)

    @staticmethod
    def _white_to_transparent(source: Image.Image) -> Image.Image:
        rgba = source.convert("RGBA")
        alpha = Image.new("L", source.size)
        pixels = (
            source.get_flattened_data()
            if hasattr(source, "get_flattened_data")
            else source.getdata()
        )
        alpha.putdata([
            max(255 - red, 255 - green, 255 - blue)
            for red, green, blue in pixels
        ])
        rgba.putalpha(alpha)
        return rgba

    def paste(
        self, canvas: Image.Image, kind: str, anchor: tuple[int, int]
    ) -> tuple[int, int, int, int]:
        sprite, source_anchor = self._sprites[kind]
        left = round(anchor[0] - source_anchor[0])
        top = round(anchor[1] - source_anchor[1])
        canvas.alpha_composite(sprite, (left, top))
        local_box = sprite.getbbox()
        if local_box is None:
            raise ValueError(f"Template {kind} is empty")
        return (
            left + local_box[0],
            top + local_box[1],
            left + local_box[2],
            top + local_box[3],
        )


def _snap(value: float) -> int:
    return int(round(value / GRID) * GRID)


def _build_expression(rng: random.Random, gate_count: int) -> ExprNode:
    if gate_count == 0:
        return ExprNode("INPUT_PIN")
    kind = rng.choice(GATE_KINDS)
    if kind == "NOT":
        return ExprNode(kind, [_build_expression(rng, gate_count - 1)])
    left_gates = rng.randrange(gate_count)
    right_gates = gate_count - 1 - left_gates
    return ExprNode(
        kind,
        [
            _build_expression(rng, left_gates),
            _build_expression(rng, right_gates),
        ],
    )


def _signature(node: ExprNode) -> str:
    if node.kind == "INPUT_PIN":
        return "INPUT"
    child_signatures = [_signature(child) for child in node.children]
    if node.kind in BINARY_KINDS:
        child_signatures.sort()
    return f"{node.kind}({','.join(child_signatures)})"


def _walk_leaves(node: ExprNode) -> Iterable[ExprNode]:
    if node.kind == "INPUT_PIN":
        yield node
    else:
        for child in node.children:
            yield from _walk_leaves(child)


def _walk_gates_postorder(node: ExprNode) -> Iterable[ExprNode]:
    for child in node.children:
        yield from _walk_gates_postorder(child)
    if node.kind != "INPUT_PIN":
        yield node


def _assign_depth(node: ExprNode) -> int:
    if node.kind == "INPUT_PIN":
        node.depth = 0
    else:
        node.depth = max(_assign_depth(child) for child in node.children) + 1
    return node.depth


def _assign_layout(root: ExprNode, rng: random.Random) -> tuple[list[Component], list[Net]]:
    leaves = list(_walk_leaves(root))
    gates = list(_walk_gates_postorder(root))
    root_depth = _assign_depth(root)

    x_jitter = rng.choice((-10, 0, 10))
    y_jitter = rng.choice((-10, 0, 10))
    if len(leaves) == 1:
        leaf_ys = [260]
    else:
        leaf_ys = [
            _snap(70 + index * (380 / (len(leaves) - 1)))
            for index in range(len(leaves))
        ]

    for index, (leaf, y_value) in enumerate(zip(leaves, leaf_ys), start=1):
        leaf.component_id = f"pin_in_{index:03d}"
        leaf.anchor = (90 + x_jitter, y_value + y_jitter)

    if root_depth == 1:
        gate_x = {1: 400}
    else:
        gate_x = {
            depth: _snap(220 + (depth - 1) * (380 / (root_depth - 1)))
            for depth in range(1, root_depth + 1)
        }

    for index, gate in enumerate(gates, start=1):
        gate.component_id = f"gate_{index:03d}"
        child_y = sum(child.anchor[1] for child in gate.children) / len(gate.children)
        gate.anchor = (gate_x[gate.depth] + x_jitter, _snap(child_y))

    components: list[Component] = []
    for index, leaf in enumerate(leaves):
        components.append(
            Component(
                leaf.component_id,
                "INPUT_PIN",
                leaf.anchor,
                {"label": _input_label(index), "bitWidth": 1},
            )
        )
    for gate in gates:
        components.append(
            Component(
                gate.component_id,
                gate.kind,
                gate.anchor,
                {"inputs": len(gate.children), "bitWidth": 1},
            )
        )

    output_anchor = (710 + x_jitter, root.anchor[1])
    output = Component(
        "pin_out_001", "OUTPUT_PIN", output_anchor, {"label": "Y", "bitWidth": 1}
    )
    components.append(output)

    nets: list[Net] = []
    net_number = 1
    for gate in gates:
        for input_index, child in enumerate(gate.children):
            start = _port_position(child.kind, child.anchor, "out")
            sink_port = f"in{input_index}"
            end = _port_position(gate.kind, gate.anchor, sink_port)
            nets.append(
                Net(
                    f"net_{net_number:03d}",
                    child.component_id,
                    "out",
                    gate.component_id,
                    sink_port,
                    _route(start, end, input_index, len(gate.children)),
                )
            )
            net_number += 1

    output_start = _port_position(root.kind, root.anchor, "out")
    output_end = _port_position(output.kind, output.anchor, "in")
    nets.append(
        Net(
            f"net_{net_number:03d}",
            root.component_id,
            "out",
            output.component_id,
            "in",
            _route(output_start, output_end, 0, 1),
        )
    )
    return components, nets


def _input_label(index: int) -> str:
    if index < 26:
        return chr(ord("A") + index)
    return f"I{index + 1}"


def _port_position(kind: str, anchor: tuple[int, int], port_id: str) -> tuple[int, int]:
    x_value, y_value = anchor
    if kind == "INPUT_PIN":
        if port_id != "out":
            raise ValueError(f"Invalid port {port_id} for {kind}")
        return anchor
    if kind == "OUTPUT_PIN":
        if port_id != "in":
            raise ValueError(f"Invalid port {port_id} for {kind}")
        return anchor
    if port_id == "out":
        return anchor
    if kind == "NOT" and port_id == "in0":
        return (x_value - 30, y_value)
    if kind in BINARY_KINDS and port_id in ("in0", "in1"):
        length = 60 if kind in ("NAND", "NOR") else 50
        y_offset = -20 if port_id == "in0" else 20
        return (x_value - length, y_value + y_offset)
    raise ValueError(f"Invalid port {port_id} for {kind}")


def _route(
    start: tuple[int, int], end: tuple[int, int], input_index: int, input_count: int
) -> tuple[tuple[int, int, int, int], ...]:
    if start[1] == end[1]:
        return ((start[0], start[1], end[0], end[1]),)
    channel_offset = 30 if input_count == 2 and input_index == 0 else 20
    turn_x = end[0] - channel_offset
    if turn_x <= start[0]:
        turn_x = _snap((start[0] + end[0]) / 2)
    points = (start, (turn_x, start[1]), (turn_x, end[1]), end)
    segments = []
    for first, second in zip(points, points[1:]):
        if first != second:
            segments.append((first[0], first[1], second[0], second[1]))
    return tuple(segments)


def _render_sample(
    templates: TemplateLibrary,
    components: Sequence[Component],
    nets: Sequence[Net],
    rng: random.Random,
) -> tuple[Image.Image, dict[str, tuple[int, int, int, int]]]:
    background = rng.randint(250, 255)
    canvas = Image.new("RGBA", (CANVAS_WIDTH, CANVAS_HEIGHT), (background,) * 3 + (255,))
    draw = ImageDraw.Draw(canvas)
    wire_color = rng.choice(((0, 112, 0, 255), (0, 120, 0, 255), (8, 104, 8, 255)))
    for net in nets:
        for segment in net.segments:
            draw.line(segment, fill=wire_color, width=3)

    boxes: dict[str, tuple[int, int, int, int]] = {}
    for component in components:
        boxes[component.component_id] = templates.paste(
            canvas, component.kind, component.anchor
        )

    rendered = canvas.convert("RGB")
    brightness = rng.uniform(0.98, 1.02)
    rendered = ImageEnhance.Brightness(rendered).enhance(brightness)
    if rng.random() < 0.2:
        rendered = rendered.filter(ImageFilter.GaussianBlur(radius=0.18))
    return rendered, boxes


def _encode_png(image: Image.Image) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False, compress_level=6)
    return buffer.getvalue()


def _build_ir(
    image_digest: str,
    semantic_hash: str,
    components: Sequence[Component],
    boxes: dict[str, tuple[int, int, int, int]],
    nets: Sequence[Net],
) -> dict[str, object]:
    ir_components = []
    for component in components:
        ir_components.append(
            {
                "id": component.component_id,
                "kind": component.kind,
                "bodyBox": list(boxes[component.component_id]),
                "anchor": list(component.anchor),
                "facing": "EAST",
                "attributes": component.attributes,
                "ports": [],
                "confidence": 1.0,
            }
        )
    ir_nets = []
    for net in nets:
        ir_nets.append(
            {
                "id": net.net_id,
                "endpoints": [
                    {"componentId": net.source_id, "portId": net.source_port},
                    {"componentId": net.sink_id, "portId": net.sink_port},
                ],
                "segments": [list(segment) for segment in net.segments],
                "junctions": [],
                "confidence": 1.0,
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "sha256": image_digest,
            "width": CANVAS_WIDTH,
            "height": CANVAS_HEIGHT,
        },
        "profile": {
            "theme": "light",
            "gateShape": "ansi",
            "gridPixels": float(GRID),
            "scale": 1.0,
            "offsetX": 0.0,
            "offsetY": 0.0,
        },
        "components": ir_components,
        "nets": ir_nets,
        "unresolved": [],
        "semanticHash": semantic_hash,
    }


def _yolo_text(
    components: Sequence[Component], boxes: dict[str, tuple[int, int, int, int]]
) -> str:
    lines = []
    for component in components:
        x0, y0, x1, y1 = boxes[component.component_id]
        center_x = ((x0 + x1) / 2) / CANVAS_WIDTH
        center_y = ((y0 + y1) / 2) / CANVAS_HEIGHT
        width = (x1 - x0) / CANVAS_WIDTH
        height = (y1 - y0) / CANVAS_HEIGHT
        lines.append(
            f"{CLASS_IDS[component.kind]} {center_x:.8f} {center_y:.8f} "
            f"{width:.8f} {height:.8f}"
        )
    return "\n".join(lines) + "\n"


def _find_asset_root(explicit: Path | None) -> Path:
    if explicit is not None:
        return explicit.resolve()
    relative = Path("src/main/resources/doc/img-libs")
    starts = (Path.cwd().resolve(), Path(__file__).resolve())
    checked: set[Path] = set()
    for start in starts:
        for base in (start, *start.parents):
            candidate = base / relative
            if candidate not in checked and (candidate / "ansandgate.png").is_file():
                return candidate
            checked.add(candidate)
    raise FileNotFoundError(
        "Cannot locate src/main/resources/doc/img-libs; pass --asset-root explicitly"
    )


def _split_for_hash(semantic_hash: str, val_fraction: float) -> str:
    threshold = int(val_fraction * (1 << 32))
    return "val" if int(semantic_hash[:8], 16) < threshold else "train"


def generate_dataset(
    output: Path,
    count: int,
    seed: int,
    val_fraction: float = 0.15,
    asset_root: Path | None = None,
    progress: bool = False,
) -> dict[str, object]:
    """Generate a new dataset directory and return its validated summary."""
    if count <= 0:
        raise ValueError("count must be positive")
    if not 0.0 < val_fraction < 1.0:
        raise ValueError("val_fraction must be between zero and one")
    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"Output directory already exists: {output}")

    templates = TemplateLibrary(_find_asset_root(asset_root))
    for split in ("train", "val"):
        (output / "images" / split).mkdir(parents=True, exist_ok=False)
        (output / "labels" / split).mkdir(parents=True, exist_ok=False)
        (output / "ir" / split).mkdir(parents=True, exist_ok=False)

    master_rng = random.Random(seed)
    seen_topologies: set[str] = set()
    records: list[dict[str, object]] = []
    class_counts: Counter[str] = Counter()
    split_counts: Counter[str] = Counter()
    max_attempts = max(10_000, count * 200)
    attempts = 0

    while len(records) < count:
        attempts += 1
        if attempts > max_attempts:
            raise RuntimeError(
                f"Could not generate {count} unique topologies after {max_attempts} attempts"
            )
        gate_count = master_rng.randint(1, 5)
        root = _build_expression(master_rng, gate_count)
        signature = _signature(root)
        semantic_hash = sha256(signature.encode("utf-8")).hexdigest()
        if semantic_hash in seen_topologies:
            continue
        if sum(1 for _ in _walk_leaves(root)) + gate_count + 1 < 4:
            continue
        seen_topologies.add(semantic_hash)

        sample_seed = master_rng.getrandbits(64)
        sample_rng = random.Random(sample_seed)
        components, nets = _assign_layout(root, sample_rng)
        image, boxes = _render_sample(templates, components, nets, sample_rng)
        image_bytes = _encode_png(image)
        image_digest = sha256(image_bytes).hexdigest()
        ir = _build_ir(image_digest, semantic_hash, components, boxes, nets)
        yolo = _yolo_text(components, boxes)
        split = _split_for_hash(semantic_hash, val_fraction)
        index = len(records) + 1
        stem = f"sample_{index:06d}_{semantic_hash[:10]}"

        image_rel = Path("images") / split / f"{stem}.png"
        label_rel = Path("labels") / split / f"{stem}.txt"
        ir_rel = Path("ir") / split / f"{stem}.json"
        (output / image_rel).write_bytes(image_bytes)
        (output / label_rel).write_text(yolo, encoding="utf-8", newline="\n")
        (output / ir_rel).write_text(
            json.dumps(ir, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
            newline="\n",
        )

        for component in components:
            class_counts[component.kind] += 1
        split_counts[split] += 1
        records.append(
            {
                "index": index,
                "seed": sample_seed,
                "generatorVersion": GENERATOR_VERSION,
                "semanticHash": semantic_hash,
                "topology": signature,
                "split": split,
                "image": image_rel.as_posix(),
                "label": label_rel.as_posix(),
                "ir": ir_rel.as_posix(),
                "imageSha256": image_digest,
            }
        )
        if progress and (index % 100 == 0 or index == count):
            print(f"generated {index}/{count}")

    manifest_text = "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        for record in records
    )
    (output / "manifest.jsonl").write_text(manifest_text, encoding="utf-8", newline="\n")
    (output / "data.yaml").write_text(
        _data_yaml(), encoding="utf-8", newline="\n"
    )
    summary = {
        "generatorVersion": GENERATOR_VERSION,
        "runtime": {
            "python": platform.python_version(),
            "pillow": PILLOW_VERSION,
        },
        "seed": seed,
        "samples": count,
        "uniqueSemanticHashes": len(seen_topologies),
        "splits": {name: split_counts[name] for name in ("train", "val")},
        "classes": {name: class_counts[name] for name in CLASS_NAMES},
    }
    (output / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return validate_dataset(output)


def _data_yaml() -> str:
    lines = ["path: .", "train: images/train", "val: images/val", "names:"]
    lines.extend(f"  {index}: {name}" for index, name in enumerate(CLASS_NAMES))
    return "\n".join(lines) + "\n"


def validate_dataset(dataset_root: Path) -> dict[str, object]:
    """Validate files, YOLO ranges, hashes, IR references and topology grouping."""
    dataset_root = dataset_root.resolve()
    manifest_path = dataset_root / "manifest.jsonl"
    if not manifest_path.is_file():
        raise ValueError(f"Missing manifest: {manifest_path}")
    records = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not records:
        raise ValueError("Dataset manifest is empty")

    semantic_splits: dict[str, str] = {}
    class_counts: Counter[str] = Counter()
    split_counts: Counter[str] = Counter()
    for record in records:
        split = record["split"]
        if split not in ("train", "val"):
            raise ValueError(f"Invalid split: {split}")
        semantic_hash = record["semanticHash"]
        previous_split = semantic_splits.setdefault(semantic_hash, split)
        if previous_split != split:
            raise ValueError(f"Topology {semantic_hash} appears in multiple splits")

        image_path = dataset_root / record["image"]
        label_path = dataset_root / record["label"]
        ir_path = dataset_root / record["ir"]
        for path in (image_path, label_path, ir_path):
            if not path.is_file():
                raise ValueError(f"Missing sample file: {path}")
        image_bytes = image_path.read_bytes()
        image_digest = sha256(image_bytes).hexdigest()
        if image_digest != record["imageSha256"]:
            raise ValueError(f"Image hash mismatch: {image_path}")
        with Image.open(BytesIO(image_bytes)) as image:
            if image.size != (CANVAS_WIDTH, CANVAS_HEIGHT):
                raise ValueError(f"Unexpected image dimensions: {image_path}")

        ir = json.loads(ir_path.read_text(encoding="utf-8"))
        _validate_ir(ir, semantic_hash, image_digest)
        labels = _parse_yolo(label_path)
        if len(labels) != len(ir["components"]):
            raise ValueError(f"Label/component count mismatch: {label_path}")
        for class_id, *_ in labels:
            kind = CLASS_NAMES[class_id]
            class_counts[kind] += 1
        split_counts[split] += 1

    return {
        "samples": len(records),
        "uniqueSemanticHashes": len(semantic_splits),
        "splits": {name: split_counts[name] for name in ("train", "val")},
        "classes": {name: class_counts[name] for name in CLASS_NAMES},
    }


def _parse_yolo(path: Path) -> list[tuple[int, float, float, float, float]]:
    labels = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        values = line.split()
        if len(values) != 5:
            raise ValueError(f"Invalid YOLO row at {path}:{line_number}")
        class_id = int(values[0])
        if not 0 <= class_id < len(CLASS_NAMES):
            raise ValueError(f"Invalid class id at {path}:{line_number}")
        center_x, center_y, width, height = map(float, values[1:])
        if not all(math.isfinite(value) for value in (center_x, center_y, width, height)):
            raise ValueError(f"Non-finite YOLO value at {path}:{line_number}")
        if not 0.0 <= center_x <= 1.0 or not 0.0 <= center_y <= 1.0:
            raise ValueError(f"YOLO center outside image at {path}:{line_number}")
        if not 0.0 < width <= 1.0 or not 0.0 < height <= 1.0:
            raise ValueError(f"YOLO size outside image at {path}:{line_number}")
        if center_x - width / 2 < -1e-7 or center_x + width / 2 > 1.0 + 1e-7:
            raise ValueError(f"YOLO x bounds outside image at {path}:{line_number}")
        if center_y - height / 2 < -1e-7 or center_y + height / 2 > 1.0 + 1e-7:
            raise ValueError(f"YOLO y bounds outside image at {path}:{line_number}")
        labels.append((class_id, center_x, center_y, width, height))
    return labels


def _validate_ir(ir: dict[str, object], semantic_hash: str, image_digest: str) -> None:
    if ir.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Unexpected Vision IR schema version")
    if ir.get("semanticHash") != semantic_hash:
        raise ValueError("Vision IR semantic hash does not match manifest")
    source = ir.get("source")
    if not isinstance(source, dict) or source.get("sha256") != image_digest:
        raise ValueError("Vision IR source hash does not match image")
    if source.get("width") != CANVAS_WIDTH or source.get("height") != CANVAS_HEIGHT:
        raise ValueError("Vision IR source dimensions are invalid")

    components = ir.get("components")
    nets = ir.get("nets")
    if not isinstance(components, list) or not components:
        raise ValueError("Vision IR must contain components")
    if not isinstance(nets, list) or not nets:
        raise ValueError("Vision IR must contain nets")
    by_id: dict[str, dict[str, object]] = {}
    expected_ports: set[tuple[str, str]] = set()
    boxes: list[tuple[str, list[float]]] = []
    for component in components:
        component_id = component.get("id")
        kind = component.get("kind")
        if not isinstance(component_id, str) or component_id in by_id:
            raise ValueError(f"Invalid or duplicate component id: {component_id}")
        if kind not in CLASS_IDS:
            raise ValueError(f"Invalid component kind: {kind}")
        box = component.get("bodyBox")
        if not isinstance(box, list) or len(box) != 4:
            raise ValueError(f"Invalid body box for {component_id}")
        x0, y0, x1, y1 = box
        if not 0 <= x0 < x1 <= CANVAS_WIDTH or not 0 <= y0 < y1 <= CANVAS_HEIGHT:
            raise ValueError(f"Body box outside image for {component_id}")
        boxes.append((component_id, box))
        by_id[component_id] = component
        if kind == "INPUT_PIN":
            expected_ports.add((component_id, "out"))
        elif kind == "OUTPUT_PIN":
            expected_ports.add((component_id, "in"))
        elif kind == "NOT":
            expected_ports.update(((component_id, "out"), (component_id, "in0")))
        else:
            expected_ports.update(
                ((component_id, "out"), (component_id, "in0"), (component_id, "in1"))
            )

    _validate_nonoverlapping_boxes(boxes)
    used_ports: set[tuple[str, str]] = set()
    net_ids: set[str] = set()
    for net in nets:
        net_id = net.get("id")
        if not isinstance(net_id, str) or net_id in net_ids:
            raise ValueError(f"Invalid or duplicate net id: {net_id}")
        net_ids.add(net_id)
        endpoints = net.get("endpoints")
        if not isinstance(endpoints, list) or len(endpoints) != 2:
            raise ValueError(f"Point-to-point net {net_id} must have two endpoints")
        endpoint_keys = []
        for endpoint in endpoints:
            key = (endpoint.get("componentId"), endpoint.get("portId"))
            if key not in expected_ports:
                raise ValueError(f"Net {net_id} references unknown port {key}")
            if key in used_ports:
                raise ValueError(f"Port {key} is connected more than once")
            used_ports.add(key)
            endpoint_keys.append(key)
        if endpoint_keys[0][1] != "out" or endpoint_keys[1][1] == "out":
            raise ValueError(f"Net {net_id} must run from one output to one input")

        segments = net.get("segments")
        if not isinstance(segments, list) or not segments:
            raise ValueError(f"Net {net_id} contains no segments")
        previous_end = None
        for segment in segments:
            if not isinstance(segment, list) or len(segment) != 4:
                raise ValueError(f"Net {net_id} has an invalid segment")
            x0, y0, x1, y1 = segment
            if (x0, y0) == (x1, y1) or (x0 != x1 and y0 != y1):
                raise ValueError(f"Net {net_id} has a non-Manhattan segment")
            if previous_end is not None and previous_end != (x0, y0):
                raise ValueError(f"Net {net_id} segments are not continuous")
            previous_end = (x1, y1)

        source_component = by_id[endpoint_keys[0][0]]
        sink_component = by_id[endpoint_keys[1][0]]
        source_position = _port_position(
            source_component["kind"], tuple(source_component["anchor"]), endpoint_keys[0][1]
        )
        sink_position = _port_position(
            sink_component["kind"], tuple(sink_component["anchor"]), endpoint_keys[1][1]
        )
        first = segments[0]
        last = segments[-1]
        if (first[0], first[1]) != source_position or (last[2], last[3]) != sink_position:
            raise ValueError(f"Net {net_id} does not terminate at its declared ports")

    if used_ports != expected_ports:
        missing = sorted(expected_ports - used_ports)
        raise ValueError(f"Unconnected component ports: {missing}")


def _validate_nonoverlapping_boxes(boxes: Sequence[tuple[str, list[float]]]) -> None:
    for index, (first_id, first) in enumerate(boxes):
        for second_id, second in boxes[index + 1 :]:
            overlap_x = min(first[2], second[2]) - max(first[0], second[0])
            overlap_y = min(first[3], second[3]) - max(first[1], second[1])
            if overlap_x > 2 and overlap_y > 2:
                raise ValueError(f"Component boxes overlap: {first_id}, {second_id}")


def _build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate deterministic Logisim circuit images, YOLO labels and Vision IR"
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--output", type=Path, help="new dataset directory")
    action.add_argument("--validate-only", type=Path, help="validate an existing dataset")
    parser.add_argument("--count", type=int, default=500, help="number of samples")
    parser.add_argument("--seed", type=int, default=20261021, help="master random seed")
    parser.add_argument(
        "--val-fraction", type=float, default=0.15, help="validation split fraction"
    )
    parser.add_argument(
        "--asset-root", type=Path, help="directory containing Logisim img-libs PNG files"
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_argument_parser().parse_args(argv)
    if args.validate_only is not None:
        summary = validate_dataset(args.validate_only)
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        return 0
    summary = generate_dataset(
        output=args.output,
        count=args.count,
        seed=args.seed,
        val_fraction=args.val_fraction,
        asset_root=args.asset_root,
        progress=True,
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
