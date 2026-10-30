from __future__ import annotations

from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

from logisim_vision.source_truth import build_truth


def test_build_truth_uses_source_topology_and_deterministic_grid_alignment(
    tmp_path: Path,
) -> None:
    image = Image.new("RGB", (220, 110), "white")
    draw = ImageDraw.Draw(image)
    for x_value in range(0, image.width, 10):
        for y_value in range(0, image.height, 10):
            draw.point((x_value, y_value), fill=(128, 128, 128))
    draw.line((70, 50, 150, 50), fill=(0, 128, 0), width=3)
    image_path = tmp_path / "R001.png"
    image.save(image_path)

    circuit = ET.fromstring(
        """
        <circuit name="R001">
          <comp lib="0" loc="(60,30)" name="Pin">
            <a name="label" val="A"/>
          </comp>
          <comp lib="0" loc="(140,30)" name="Pin">
            <a name="facing" val="west"/>
            <a name="label" val="Y"/>
            <a name="type" val="output"/>
          </comp>
          <wire from="(60,30)" to="(140,30)"/>
        </circuit>
        """
    )

    truth, alignment = build_truth(image_path, circuit)

    assert alignment.shift == (1, 2)
    assert alignment.overlap == 1.0
    assert [component["kind"] for component in truth["components"]] == [
        "INPUT_PIN",
        "OUTPUT_PIN",
    ]
    assert truth["components"][0]["anchor"] == [70.0, 50.0]
    assert truth["components"][1]["anchor"] == [150.0, 50.0]
    assert truth["nets"][0]["endpoints"] == [
        {"componentId": "input_pin_001", "portId": "out"},
        {"componentId": "output_pin_001", "portId": "in"},
    ]
    assert truth["nets"][0]["junctions"] == []
    assert truth["unresolved"] == []
