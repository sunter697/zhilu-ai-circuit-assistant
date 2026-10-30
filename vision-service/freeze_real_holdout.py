"""Freeze a reviewed holdout draft without running circuit recognition."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.onnx_detector import FULL_CLASS_NAMES  # noqa: E402
from logisim_vision.real_holdout import (  # noqa: E402
    _validate_truth,
    file_sha256,
    load_benchmark,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    args = parser.parse_args()
    root = args.dataset.resolve()
    draft_path = root / "manifest.draft.json"
    target = root / "manifest.json"
    if target.exists():
        raise FileExistsError(f"frozen manifest already exists: {target}")
    draft = json.loads(draft_path.read_text(encoding="utf-8"))
    cases = draft.get("cases")
    if not isinstance(cases, list) or len(cases) < 20:
        raise ValueError("the reviewed draft must contain at least 20 cases")

    represented: Counter[str] = Counter()
    junctions = 0
    junction_images = 0
    for record in cases:
        image_path = root / record["image"]
        truth_path = root / record["expectedIr"]
        if file_sha256(image_path) != record["imageSha256"]:
            raise ValueError(f"image changed after preparation: {record['id']}")
        if file_sha256(truth_path) != record["expectedIrSha256"]:
            raise ValueError(f"annotation changed after preparation: {record['id']}")
        truth = json.loads(truth_path.read_text(encoding="utf-8"))
        with Image.open(image_path) as opened:
            size = opened.size
        _validate_truth(truth, record["imageSha256"], size, record["id"])
        represented.update(component["kind"] for component in truth["components"])
        all_ports = {
            (component["id"], port["id"])
            for component in truth["components"]
            for port in component.get("ports", [])
        }
        connected_ports = {
            (endpoint["componentId"], endpoint["portId"])
            for net in truth["nets"]
            for endpoint in net["endpoints"]
        }
        if all_ports != connected_ports:
            raise ValueError(
                f"case {record['id']} has unconnected or unknown ports: "
                f"unconnected={sorted(all_ports - connected_ports)}, "
                f"unknown={sorted(connected_ports - all_ports)}"
            )
        current_junctions = sum(len(net.get("junctions", [])) for net in truth["nets"])
        junctions += current_junctions
        junction_images += int(current_junctions > 0)

    missing = sorted(set(FULL_CLASS_NAMES) - represented.keys())
    if missing:
        raise ValueError("holdout is missing classes: " + ", ".join(missing))
    if junctions == 0:
        raise ValueError("holdout must contain at least one junction")
    if junction_images < 5:
        raise ValueError("holdout collection plan requires at least five junction images")

    declarations = draft.get("declarations", {})
    for name in (
        "modelAndThresholdsFrozenBeforeCollection",
        "notUsedForTraining",
        "notUsedForThresholdSelection",
    ):
        if declarations.get(name) is not True:
            raise ValueError(f"independence declaration is not true: {name}")
    declarations["annotationCompletedBeforeEvaluation"] = True
    draft["declarations"] = declarations
    draft["frozen"] = True
    draft["frozenAt"] = (
        datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )
    with target.open("x", encoding="utf-8") as output:
        json.dump(draft, output, ensure_ascii=False, indent=2)
        output.write("\n")

    loaded = load_benchmark(root)
    print(
        f"Frozen {len(loaded['cases'])} cases at {draft['frozenAt']}; "
        f"classes={len(represented)}, junctions={junctions}, "
        f"junctionImages={junction_images}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
