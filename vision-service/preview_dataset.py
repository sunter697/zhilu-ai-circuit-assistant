"""Create a labeled contact sheet for human dataset inspection."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import random

from PIL import Image, ImageDraw


COLORS = {
    "INPUT_PIN": "#1f77b4",
    "OUTPUT_PIN": "#17becf",
    "AND": "#d62728",
    "OR": "#ff7f0e",
    "NOT": "#9467bd",
    "NAND": "#2ca02c",
    "NOR": "#8c564b",
    "XOR": "#e377c2",
    "XNOR": "#7f7f7f",
}


def create_contact_sheet(
    dataset: Path,
    output: Path,
    count: int = 30,
    seed: int = 20261021,
    family: str | None = None,
    render_profile: str | None = None,
) -> None:
    dataset = dataset.resolve()
    records = [
        json.loads(line)
        for line in (dataset / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    available_families = sorted(
        {record.get("family", "legacy") for record in records}
    )
    if family is not None:
        records = [record for record in records if record.get("family") == family]
        if not records:
            raise ValueError(
                f"family {family!r} has no samples; available: "
                + ", ".join(available_families)
            )
    available_render_profiles = sorted(
        {record.get("renderProfile", "legacy-native") for record in records}
    )
    if render_profile is not None:
        records = [
            record
            for record in records
            if record.get("renderProfile", "legacy-native") == render_profile
        ]
        if not records:
            raise ValueError(
                f"render profile {render_profile!r} has no samples; available: "
                + ", ".join(available_render_profiles)
            )
    if count <= 0 or count > len(records):
        raise ValueError(f"count must be between 1 and {len(records)}")
    selected = random.Random(seed).sample(records, count)

    columns = 5
    thumb_width = 320
    thumb_height = 208
    caption_height = 22
    rows = math.ceil(count / columns)
    sheet = Image.new(
        "RGB", (columns * thumb_width, rows * (thumb_height + caption_height)), "white"
    )
    for index, record in enumerate(selected):
        image = Image.open(dataset / record["image"]).convert("RGB")
        ir = json.loads((dataset / record["ir"]).read_text(encoding="utf-8"))
        draw = ImageDraw.Draw(image)
        for component in ir["components"]:
            color = COLORS.get(component["kind"], "#000000")
            draw.rectangle(component["bodyBox"], outline=color, width=3)
            x0, y0, _, _ = component["bodyBox"]
            draw.text((x0, max(0, y0 - 12)), component["kind"], fill=color)
        image.thumbnail((thumb_width, thumb_height), Image.Resampling.LANCZOS)
        cell_x = (index % columns) * thumb_width
        cell_y = (index // columns) * (thumb_height + caption_height)
        sheet.paste(image, (cell_x, cell_y))
        record_index = record.get("index", record.get("topologyIndex", index + 1))
        variant = record.get("variant")
        variant_text = "" if variant is None else f" v{variant:02d}"
        family_text = record.get("family", "legacy")
        render_profile_text = record.get("renderProfile", "legacy-native")
        caption = (
            f"{family_text}/{render_profile_text}  #{record_index:04d}{variant_text}  "
            f"{record['split']}  {record['semanticHash'][:8]}"
        )
        ImageDraw.Draw(sheet).text(
            (cell_x + 4, cell_y + thumb_height + 3), caption, fill="black"
        )

    output = output.resolve()
    if output.exists():
        raise FileExistsError(f"Output image already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, format="PNG")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument(
        "--family", help="Only sample one manifest family, for example layered-dag"
    )
    parser.add_argument(
        "--render-profile",
        help="Only sample one render profile, for example soft-resample",
    )
    args = parser.parse_args()
    create_contact_sheet(
        args.dataset,
        args.output,
        args.count,
        args.seed,
        args.family,
        args.render_profile,
    )
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
