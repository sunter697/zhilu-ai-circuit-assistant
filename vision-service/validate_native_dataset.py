"""Validate a portable native Logisim vision dataset without the Java project."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from typing import Any


CLASS_NAMES = (
    "INPUT_PIN",
    "OUTPUT_PIN",
    "AND",
    "OR",
    "NOT",
    "NAND",
    "NOR",
    "XOR",
    "XNOR",
)
SPLITS = {"train", "val", "test"}


class DatasetError(ValueError):
    """Raised when the copied dataset is incomplete or inconsistent."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DatasetError(message)


def read_json_lines(path: Path) -> list[dict[str, Any]]:
    require(path.is_file(), f"missing file: {path}")
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise DatasetError(f"{path}:{line_number}: invalid JSON: {error}") from error
        require(isinstance(value, dict), f"{path}:{line_number}: expected object")
        records.append(value)
    require(records, f"no records in {path}")
    return records


def resolve_member(root: Path, relative: Any, field: str) -> Path:
    require(isinstance(relative, str) and relative, f"invalid {field} path: {relative!r}")
    path = (root / relative).resolve()
    require(path.is_relative_to(root), f"{field} escapes dataset directory: {relative}")
    require(path.is_file(), f"missing {field}: {relative}")
    return path


def parse_yolo(
    path: Path,
    components: list[dict[str, Any]],
    width: float,
    height: float,
) -> Counter[str]:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    require(len(lines) == len(components), f"{path}: label/component count mismatch")
    counts: Counter[str] = Counter()
    for line_number, (line, component) in enumerate(zip(lines, components), 1):
        parts = line.split()
        require(len(parts) == 5, f"{path}:{line_number}: expected 5 YOLO fields")
        try:
            class_id = int(parts[0])
            values = tuple(float(value) for value in parts[1:])
        except ValueError as error:
            raise DatasetError(f"{path}:{line_number}: invalid YOLO number") from error
        require(0 <= class_id < len(CLASS_NAMES), f"{path}:{line_number}: class out of range")
        require(all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in values),
                f"{path}:{line_number}: normalized box out of range")
        kind = component.get("kind")
        require(kind in CLASS_NAMES, f"{path}:{line_number}: unsupported component kind {kind!r}")
        require(CLASS_NAMES[class_id] == kind,
                f"{path}:{line_number}: label class does not match IR component")
        box = component.get("bodyBox")
        require(isinstance(box, list) and len(box) == 4,
                f"{path}:{line_number}: invalid bodyBox")
        x0, y0, x1, y1 = (float(value) for value in box)
        expected = (
            (x0 + x1) / (2.0 * width),
            (y0 + y1) / (2.0 * height),
            (x1 - x0) / width,
            (y1 - y0) / height,
        )
        require(all(abs(actual - wanted) <= 1e-7 for actual, wanted in zip(values, expected)),
                f"{path}:{line_number}: YOLO box does not match IR bodyBox")
        counts[kind] += 1
    return counts


def load_excluded_hashes(path: Path | None) -> set[str]:
    if path is None:
        return set()
    if path.is_dir():
        path = path / "manifest.jsonl"
    hashes = {record.get("semanticHash") for record in read_json_lines(path)}
    require(all(isinstance(value, str) and value for value in hashes),
            f"invalid semanticHash in exclusion file: {path}")
    return hashes  # type: ignore[return-value]


def parse_data_yaml(path: Path) -> tuple[set[str], dict[int, str]]:
    declared_splits: set[str] = set()
    names: dict[int, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        split_match = re.match(r"^\s*(train|val|test)\s*:\s*(\S.*)$", line)
        if split_match:
            declared_splits.add(split_match.group(1))
            continue
        name_match = re.match(r"^\s*(\d+)\s*:\s*([A-Z_]+)\s*$", line)
        if name_match:
            names[int(name_match.group(1))] = name_match.group(2)
    return declared_splits, names


def validate(
    dataset: Path,
    expected_manifest_sha256: str | None,
    exclusion: Path | None,
) -> dict[str, Any]:
    root = dataset.resolve()
    require(root.is_dir(), f"dataset directory does not exist: {root}")
    manifest_path = root / "manifest.jsonl"
    summary_path = root / "summary.json"
    data_yaml_path = root / "data.yaml"
    require(summary_path.is_file(), f"missing file: {summary_path}")
    require(data_yaml_path.is_file(), f"missing file: {data_yaml_path}")

    manifest_sha256 = sha256_file(manifest_path)
    if expected_manifest_sha256 is not None:
        require(
            manifest_sha256.lower() == expected_manifest_sha256.lower(),
            "manifest SHA-256 mismatch: "
            f"expected {expected_manifest_sha256.lower()}, got {manifest_sha256}",
        )

    records = read_json_lines(manifest_path)
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    require(isinstance(summary, dict), f"{summary_path}: expected JSON object")
    excluded_hashes = load_excluded_hashes(exclusion)

    splits: Counter[str] = Counter()
    class_counts: Counter[str] = Counter()
    gate_size_counts: Counter[str] = Counter()
    render_profiles: Counter[str] = Counter()
    roles: set[str] = set()
    generators: set[str] = set()
    semantic_splits: dict[str, str] = {}
    semantic_families: dict[str, str] = {}
    semantic_variants: dict[str, set[int]] = {}
    member_paths: set[Path] = set()
    junction_count = 0

    for record_number, record in enumerate(records, 1):
        prefix = f"manifest record {record_number}"
        split = record.get("split")
        require(split in SPLITS, f"{prefix}: invalid split {split!r}")
        role = record.get("datasetRole")
        require(role in {"training", "evaluation"}, f"{prefix}: invalid datasetRole {role!r}")
        generator = record.get("generatorVersion")
        require(isinstance(generator, str) and generator, f"{prefix}: missing generatorVersion")
        semantic_hash = record.get("semanticHash")
        require(isinstance(semantic_hash, str) and len(semantic_hash) == 64,
                f"{prefix}: invalid semanticHash")
        require(semantic_hash not in excluded_hashes,
                f"{prefix}: semanticHash overlaps exclusion set: {semantic_hash}")
        family = record.get("family")
        require(isinstance(family, str) and family, f"{prefix}: missing family")
        profile = record.get("renderProfile")
        require(isinstance(profile, str) and profile, f"{prefix}: missing renderProfile")
        variant = record.get("variant")
        require(isinstance(variant, int) and variant >= 0, f"{prefix}: invalid variant")
        if variant == 0:
            require(profile == "native-grid", f"{prefix}: variant 0 is not native-grid")
        else:
            require(profile != "native-grid", f"{prefix}: augmented variant is native-grid")

        old_split = semantic_splits.setdefault(semantic_hash, split)
        require(old_split == split, f"{prefix}: semanticHash crosses dataset splits")
        old_family = semantic_families.setdefault(semantic_hash, family)
        require(old_family == family, f"{prefix}: semanticHash changes family")
        variants = semantic_variants.setdefault(semantic_hash, set())
        require(variant not in variants, f"{prefix}: duplicate semanticHash/variant")
        variants.add(variant)

        image_path = resolve_member(root, record.get("image"), "image")
        label_path = resolve_member(root, record.get("label"), "label")
        ir_path = resolve_member(root, record.get("ir"), "IR")
        for member_path in (image_path, label_path, ir_path):
            require(
                member_path not in member_paths,
                f"{prefix}: duplicate member path {member_path}",
            )
            member_paths.add(member_path)
        require(image_path.parent.name == split, f"{prefix}: image directory does not match split")
        require(label_path.parent.name == split, f"{prefix}: label directory does not match split")
        require(ir_path.parent.name == split, f"{prefix}: IR directory does not match split")

        image_sha256 = sha256_file(image_path)
        require(image_sha256 == record.get("imageSha256"), f"{prefix}: image SHA-256 mismatch")
        ir = json.loads(ir_path.read_text(encoding="utf-8"))
        require(ir.get("schemaVersion") == "vision-ir/0.1", f"{prefix}: wrong IR schema")
        require(ir.get("semanticHash") == semantic_hash, f"{prefix}: IR semanticHash mismatch")
        source = ir.get("source")
        require(isinstance(source, dict), f"{prefix}: missing IR source")
        require(source.get("sha256") == image_sha256, f"{prefix}: IR image SHA-256 mismatch")
        width = float(source.get("width", 0))
        height = float(source.get("height", 0))
        require(width > 0 and height > 0, f"{prefix}: invalid image dimensions")
        components = ir.get("components")
        nets = ir.get("nets")
        require(isinstance(components, list), f"{prefix}: components must be a list")
        require(isinstance(nets, list), f"{prefix}: nets must be a list")
        require(len(components) == record.get("componentCount"),
                f"{prefix}: component count mismatch")
        require(len(nets) == record.get("netCount"), f"{prefix}: net count mismatch")
        for component_index, component in enumerate(components):
            kind = component.get("kind")
            attributes = component.get("attributes")
            require(
                isinstance(attributes, dict),
                f"{prefix}: component {component_index} attributes must be an object",
            )
            gate_size = attributes.get("size")
            if kind == "NOT":
                require(
                    gate_size in {"NARROW", "WIDE"},
                    f"{prefix}: NOT component {component_index} has invalid size {gate_size!r}",
                )
                gate_size_counts[gate_size] += 1
            elif kind in {"AND", "OR", "NAND", "NOR", "XOR", "XNOR"}:
                require(
                    gate_size in {"NARROW", "MEDIUM", "WIDE"},
                    f"{prefix}: gate component {component_index} has invalid size {gate_size!r}",
                )
                gate_size_counts[gate_size] += 1
            else:
                require(
                    gate_size is None,
                    f"{prefix}: pin component {component_index} must not have a gate size",
                )
        current_junctions = sum(len(net.get("junctions", [])) for net in nets)
        require(current_junctions == record.get("junctionCount"),
                f"{prefix}: junction count mismatch")
        class_counts.update(parse_yolo(label_path, components, width, height))

        splits[split] += 1
        render_profiles[profile] += 1
        roles.add(role)
        generators.add(generator)
        junction_count += current_junctions

    require(len(roles) == 1, "manifest mixes training and evaluation roles")
    role = next(iter(roles))
    if role == "evaluation":
        require(set(splits) == {"test"}, "evaluation dataset contains non-test samples")
    declared_splits, yaml_names = parse_data_yaml(data_yaml_path)
    expected_declared_splits = {"test"} if role == "evaluation" else SPLITS
    require(
        declared_splits == expected_declared_splits,
        "data.yaml split declarations do not match dataset role",
    )
    require(
        yaml_names == dict(enumerate(CLASS_NAMES)),
        "data.yaml class names do not match the fixed 9-class schema",
    )
    require(summary.get("datasetRole") == role, "summary datasetRole mismatch")
    require(summary.get("samples") == len(records), "summary sample count mismatch")
    require(summary.get("topologies") == len(semantic_splits), "summary topology count mismatch")
    expected_splits = {split: splits[split] for split in ("train", "val", "test")}
    require(summary.get("splits") == expected_splits, "summary split counts mismatch")
    require(summary.get("families") == dict(Counter(semantic_families.values())),
            "summary family counts mismatch")
    require(summary.get("renderProfiles") == dict(render_profiles),
            "summary render profile counts mismatch")
    require(summary.get("classes") == dict(class_counts), "summary class counts mismatch")
    if "gateSizes" in summary:
        require(
            summary.get("gateSizes") == dict(gate_size_counts),
            "summary gate size counts mismatch",
        )
    else:
        require(
            all(version != "native-logisim-dataset/0.5" for version in generators),
            "native-logisim-dataset/0.5 summary is missing gateSizes",
        )

    return {
        "status": "OK",
        "dataset": str(root),
        "generatorVersions": sorted(generators),
        "datasetRole": role,
        "manifestSha256": manifest_sha256,
        "samples": len(records),
        "uniqueSemanticHashes": len(semantic_splits),
        "splits": expected_splits,
        "families": dict(Counter(semantic_families.values())),
        "renderProfiles": dict(render_profiles),
        "classes": dict(class_counts),
        "gateSizes": dict(gate_size_counts),
        "junctions": junction_count,
        "excludedHashesChecked": len(excluded_hashes),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--expect-manifest-sha256")
    parser.add_argument(
        "--exclude",
        type=Path,
        help="JSONL exclusion list or dataset directory that must not overlap",
    )
    args = parser.parse_args()
    try:
        result = validate(args.dataset, args.expect_manifest_sha256, args.exclude)
    except (DatasetError, OSError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
