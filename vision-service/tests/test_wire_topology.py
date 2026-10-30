from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
from PIL import Image, ImageDraw


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.dataset import generate_dataset  # noqa: E402
from logisim_vision.template_detector import TemplateDetection  # noqa: E402
from logisim_vision.topology import FixedScaleTopologyRecognizer  # noqa: E402
from logisim_vision.wire_extractor import GridProfile, OrthogonalWireExtractor  # noqa: E402


class WireTopologyTest(unittest.TestCase):
    def test_native_v04_black_wires_fanout_and_gridless_match_truth(self) -> None:
        fixture_root = Path(__file__).parent / "fixtures" / "native-v04"
        recognizer = FixedScaleTopologyRecognizer()
        self.assertEqual(len(list(fixture_root.glob("*.json"))), 4)

        for truth_path in sorted(fixture_root.glob("*.json")):
            with self.subTest(sample=truth_path.stem):
                truth = json.loads(truth_path.read_text(encoding="utf-8"))
                detections = [
                    TemplateDetection(
                        component["kind"],
                        tuple(round(value) for value in component["bodyBox"]),
                        1.0,
                        component["attributes"].get("size"),
                    )
                    for component in truth["components"]
                ]
                with Image.open(truth_path.with_suffix(".png")) as image:
                    actual = recognizer.recognize(
                        image,
                        "native-v04-test-source",
                        detections=detections,
                    )

                self.assertEqual(actual["unresolved"], [])
                self.assertEqual(actual["profile"]["gridPixels"], 10.0)
                self.assertEqual(actual["profile"]["offsetX"], 0.0)
                self.assertEqual(actual["profile"]["offsetY"], 0.0)
                self.assertEqual(
                    self._normalized_components(actual),
                    self._normalized_components(truth),
                )
                self.assertEqual(
                    self._normalized_nets(actual),
                    self._normalized_nets(truth),
                )

    def test_rescaled_native_render_preserves_topology(self) -> None:
        fixture_root = Path(__file__).parent / "fixtures" / "native-v04"
        truth_path = fixture_root / "native_000052_v00_e28eb59d08.json"
        truth = json.loads(truth_path.read_text(encoding="utf-8"))
        with Image.open(truth_path.with_suffix(".png")) as opened:
            source = opened.convert("RGB")
        recognizer = FixedScaleTopologyRecognizer()

        for factor in (0.75, 1.25, 1.5, 2.0):
            with self.subTest(factor=factor):
                scaled = source.resize(
                    (round(source.width * factor), round(source.height * factor)),
                    Image.Resampling.NEAREST,
                )
                detections = [
                    TemplateDetection(
                        component["kind"],
                        tuple(round(value * factor) for value in component["bodyBox"]),
                        1.0,
                        component["attributes"].get("size"),
                    )
                    for component in truth["components"]
                ]
                actual = recognizer.recognize(
                    scaled,
                    f"native-scaled-{factor}",
                    detections=detections,
                )
                self.assertAlmostEqual(
                    actual["profile"]["gridPixels"], 10.0 * factor, delta=0.2
                )
                self.assertEqual(actual["unresolved"], [])
                self.assertEqual(
                    self._normalized_nets_scaled(actual, factor),
                    self._normalized_nets(truth),
                )

    def test_native_profile_refines_quantized_half_scale_from_pin_anchors(self) -> None:
        detections = [
            TemplateDetection("INPUT_PIN", (25, 29, 35, 40), 1.0),
            TemplateDetection("INPUT_PIN", (24, 124, 35, 135), 1.0),
            TemplateDetection("OUTPUT_PIN", (350, 150, 360, 160), 1.0),
        ]

        profile = OrthogonalWireExtractor._native_profile(detections)

        self.assertAlmostEqual(profile.spacing, 5.0, delta=0.01)
        self.assertAlmostEqual(profile.scale, 0.5, delta=0.001)

    def test_terminal_tree_removes_resampling_loop_without_losing_sinks(self) -> None:
        edges = {
            ((0, 0), (1, 0)),
            ((1, 0), (2, 0)),
            ((2, 0), (3, 0)),
            ((1, 0), (1, 1)),
            ((1, 1), (2, 1)),
            ((2, 1), (2, 0)),
            ((2, 0), (2, -1)),
        }

        actual = FixedScaleTopologyRecognizer._terminal_tree_edges(
            edges, (0, 0), {(0, 0), (3, 0), (2, -1)}
        )

        self.assertEqual(
            actual,
            {
                ((0, 0), (1, 0)),
                ((1, 0), (2, 0)),
                ((2, 0), (3, 0)),
                ((2, -1), (2, 0)),
            },
        )

    def test_generated_point_to_point_topology_matches_truth(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dataset = Path(temporary) / "dataset"
            generate_dataset(dataset, count=12, seed=20261024)
            recognizer = FixedScaleTopologyRecognizer()

            for truth_path in sorted((dataset / "ir").rglob("*.json")):
                with self.subTest(sample=truth_path.name):
                    truth = json.loads(truth_path.read_text(encoding="utf-8"))
                    detections = []
                    for component in truth["components"]:
                        x0, y0, x1, y1 = component["bodyBox"]
                        detections.append(
                            TemplateDetection(
                                component["kind"], (x0, y0, x1, y1), 1.0
                            )
                        )
                    image_path = (
                        dataset
                        / "images"
                        / truth_path.parent.name
                        / f"{truth_path.stem}.png"
                    )
                    with Image.open(image_path) as image:
                        actual = recognizer.recognize(
                            image,
                            "test-source",
                            detections=detections,
                            profile=GridProfile(10.0, 0.0, 0.0),
                        )

                    self.assertEqual(actual["unresolved"], [])
                    self.assertEqual(len(actual["components"]), len(truth["components"]))
                    self.assertEqual(
                        self._normalized_endpoints(actual),
                        self._normalized_endpoints(truth),
                    )

    def test_real_grid_and_valid_chain_are_extracted(self) -> None:
        fixture = (
            Path(__file__).parent / "fixtures" / "real-logisim" / "T03_NOT.png"
        )
        with Image.open(fixture) as image:
            profile = OrthogonalWireExtractor().estimate_grid(image)
        self.assertAlmostEqual(profile.spacing, 12.5, places=1)

        actual = FixedScaleTopologyRecognizer().recognize_path(fixture)
        self.assertEqual(len(actual["components"]), 4)
        self.assertEqual(len(actual["nets"]), 2)
        self.assertEqual(
            {
                frozenset(
                    (endpoint["componentId"], endpoint["portId"])
                    for endpoint in net["endpoints"]
                )
                for net in actual["nets"]
            },
            {
                frozenset(
                    {("input_pin_001", "out"), ("not_001", "in0")}
                ),
                frozenset(
                    {("not_001", "out"), ("output_pin_001", "in")}
                ),
            },
        )

    def test_rescaled_real_circuit_preserves_topology(self) -> None:
        fixture = (
            Path(__file__).parent / "fixtures" / "real-logisim" / "T01_AND2.png"
        )
        with Image.open(fixture) as opened:
            source = opened.convert("RGB")
        recognizer = FixedScaleTopologyRecognizer()

        for factor in (0.5, 0.6, 0.8, 1.2, 1.6, 2.0):
            with self.subTest(factor=factor):
                scaled = source.resize(
                    (round(source.width * factor), round(source.height * factor)),
                    Image.Resampling.NEAREST,
                )
                actual = recognizer.recognize(scaled, f"scaled-{factor}")
                self.assertAlmostEqual(
                    actual["profile"]["gridPixels"], 12.5 * factor, delta=0.2
                )
                self.assertEqual(
                    [component["kind"] for component in actual["components"]],
                    ["INPUT_PIN", "INPUT_PIN", "AND", "OUTPUT_PIN"],
                )
                self.assertEqual(actual["unresolved"], [])
                self.assertEqual(len(actual["nets"]), 3)

    def test_real_four_way_crossings_do_not_short_independent_inputs(self) -> None:
        fixture = (
            Path(__file__).parent / "fixtures" / "real-logisim" / "T01_AND2.png"
        )
        actual = FixedScaleTopologyRecognizer().recognize_path(fixture)
        self.assertEqual(actual["unresolved"], [])
        self.assertEqual(len(actual["nets"]), 3)
        self.assertEqual(
            {
                frozenset(
                    (endpoint["componentId"], endpoint["portId"])
                    for endpoint in net["endpoints"]
                )
                for net in actual["nets"]
            },
            {
                frozenset({("input_pin_001", "out"), ("and_001", "in0")}),
                frozenset({("input_pin_002", "out"), ("and_001", "in1")}),
                frozenset({("and_001", "out"), ("output_pin_001", "in")}),
            },
        )

    def test_port_geometry_covers_every_supported_gate_size(self) -> None:
        profile = GridProfile(10.0, 0.0, 0.0)
        expected = {
            "NARROW": ((10, 10), (7, 9), (7, 11)),
            "MEDIUM": ((10, 10), (5, 8), (5, 12)),
            "WIDE": ((10, 10), (3, 8), (3, 12)),
        }
        heights = {"NARROW": 30, "MEDIUM": 50, "WIDE": 70}
        for size, (output, first_input, second_input) in expected.items():
            with self.subTest(size=size):
                height = heights[size]
                detection = TemplateDetection(
                    "AND",
                    (100 - height, 100 - height // 2, 100, 100 + height // 2),
                    1.0,
                    size,
                )
                anchor, ports, actual_size = FixedScaleTopologyRecognizer._port_geometry(
                    detection, profile
                )
                self.assertEqual(anchor, output)
                self.assertEqual(ports[1][2], first_input)
                self.assertEqual(ports[2][2], second_input)
                self.assertEqual(actual_size, size)

        narrow_not = TemplateDetection("NOT", (80, 92, 100, 108), 1.0, "NARROW")
        wide_not = TemplateDetection("NOT", (70, 91, 100, 109), 1.0, "WIDE")
        self.assertEqual(
            FixedScaleTopologyRecognizer._port_geometry(narrow_not, profile)[1][1][2],
            (8, 10),
        )
        self.assertEqual(
            FixedScaleTopologyRecognizer._port_geometry(wide_not, profile)[1][1][2],
            (7, 10),
        )

    def test_grid_region_builder_splits_cross_but_keeps_t_fanout(self) -> None:
        extractor = OrthogonalWireExtractor()
        cross = {
            (0, 0): {(-1, 0), (1, 0), (0, -1), (0, 1)},
            (-1, 0): {(0, 0)},
            (1, 0): {(0, 0)},
            (0, -1): {(0, 0)},
            (0, 1): {(0, 0)},
        }
        cross_regions = extractor._regions(cross, GridProfile(10.0, 0.0, 0.0))
        self.assertEqual(len(cross_regions), 2)
        self.assertEqual(sorted(len(region.edges) for region in cross_regions), [2, 2])
        junction_regions = extractor._regions(
            cross,
            GridProfile(10.0, 0.0, 0.0),
            {(0, 0)},
        )
        self.assertEqual(len(junction_regions), 1)
        self.assertEqual(len(junction_regions[0].edges), 4)

        fanout = {
            (0, 0): {(-1, 0), (1, 0), (0, 1)},
            (-1, 0): {(0, 0)},
            (1, 0): {(0, 0)},
            (0, 1): {(0, 0)},
        }
        fanout_regions = extractor._regions(fanout, GridProfile(10.0, 0.0, 0.0))
        self.assertEqual(len(fanout_regions), 1)
        self.assertEqual(len(fanout_regions[0].edges), 3)

    def test_parallel_thick_wires_do_not_create_short_grid_edges(self) -> None:
        mask = np.zeros((31, 31), dtype=bool)
        mask[:, 8:12] = True
        mask[:, 18:22] = True
        adjacency = OrthogonalWireExtractor()._grid_graph(
            mask, GridProfile(10.0, 0.0, 0.0)
        )

        self.assertIn((1, 2), adjacency[(1, 1)])
        self.assertIn((2, 2), adjacency[(2, 1)])
        self.assertNotIn((2, 1), adjacency[(1, 1)])

    def test_two_real_drivers_on_one_wire_are_rejected(self) -> None:
        image = Image.new("RGB", (200, 100), "white")
        draw = ImageDraw.Draw(image)
        draw.line((30, 20, 60, 20), fill="black", width=3)
        draw.line((30, 60, 60, 60), fill="black", width=3)
        draw.line((60, 20, 60, 60), fill="black", width=3)
        draw.line((60, 40, 180, 40), fill="black", width=3)
        detections = [
            TemplateDetection("INPUT_PIN", (10, 10, 30, 30), 1.0),
            TemplateDetection("INPUT_PIN", (10, 50, 30, 70), 1.0),
            TemplateDetection("OUTPUT_PIN", (160, 30, 180, 50), 1.0),
        ]

        actual = FixedScaleTopologyRecognizer().recognize(
            image,
            "multiple-driver-test",
            detections=detections,
            profile=GridProfile(10.0, 0.0, 0.0, native_v04=True),
        )

        self.assertIn(
            "MULTIPLE_DRIVERS",
            {issue["code"] for issue in actual["unresolved"]},
        )

    @staticmethod
    def _normalized_endpoints(ir: dict[str, object]) -> set[frozenset[tuple[object, ...]]]:
        components = ir["components"]
        by_id = {component["id"]: component for component in components}
        return {
            frozenset(
                (
                    by_id[endpoint["componentId"]]["kind"],
                    tuple(round(value) for value in by_id[endpoint["componentId"]]["anchor"]),
                    endpoint["portId"],
                )
                for endpoint in net["endpoints"]
            )
            for net in ir["nets"]
        }

    @staticmethod
    def _normalized_components(ir: dict[str, object]) -> list[tuple[object, ...]]:
        return sorted(
            (
                component["kind"],
                tuple(round(value) for value in component["anchor"]),
                tuple(
                    sorted(
                        (
                            port["id"],
                            port["direction"],
                            tuple(round(value) for value in port["point"]),
                        )
                        for port in component["ports"]
                    )
                ),
            )
            for component in ir["components"]
        )

    @staticmethod
    def _normalized_nets_scaled(
        ir: dict[str, object], factor: float
    ) -> list[tuple[object, ...]]:
        scaled = json.loads(json.dumps(ir))
        for component in scaled["components"]:
            component["anchor"] = [value / factor for value in component["anchor"]]
            for port in component["ports"]:
                port["point"] = [value / factor for value in port["point"]]
        for net in scaled["nets"]:
            net["junctions"] = [
                [value / factor for value in point] for point in net["junctions"]
            ]
        return WireTopologyTest._normalized_nets(scaled)

    @classmethod
    def _normalized_nets(cls, ir: dict[str, object]) -> list[tuple[object, ...]]:
        components = {component["id"]: component for component in ir["components"]}
        return sorted(
            (
                tuple(
                    sorted(
                        (
                            components[endpoint["componentId"]]["kind"],
                            tuple(
                                round(value)
                                for value in components[endpoint["componentId"]]["anchor"]
                            ),
                            endpoint["portId"],
                        )
                        for endpoint in net["endpoints"]
                    )
                ),
                tuple(
                    sorted(
                        tuple(round(value) for value in junction)
                        for junction in net["junctions"]
                    )
                ),
            )
            for net in ir["nets"]
        )


if __name__ == "__main__":
    unittest.main()
