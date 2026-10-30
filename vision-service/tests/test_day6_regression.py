from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import sys
import unittest

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.app import RecognitionApplication, ServiceInputError  # noqa: E402


FIXTURE_ROOT = Path(__file__).parent / "fixtures" / "day6"
MODEL_PATH = SERVICE_ROOT / "models" / "best.onnx"
try:
    import onnxruntime  # noqa: F401

    HAS_ONNX_RUNTIME = True
except ImportError:
    HAS_ONNX_RUNTIME = False


@unittest.skipUnless(
    MODEL_PATH.is_file() and HAS_ONNX_RUNTIME,
    "Day-6 end-to-end regression requires models/best.onnx and onnxruntime",
)
class Day6RegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = json.loads(
            (FIXTURE_ROOT / "manifest.json").read_text(encoding="utf-8")
        )
        cls.application = RecognitionApplication()
        if (
            cls.application.detector_mode
            != "onnx-9-class-with-profile-routed-legacy-compensation"
        ):
            raise AssertionError(
                f"Unexpected detector mode: {cls.application.detector_mode}"
            )

    def test_health_fingerprints_the_frozen_runtime_model(self) -> None:
        health = self.application.health()
        self.assertEqual(health["serviceVersion"], "full-v09/0.1")
        self.assertEqual(
            health["modelSha256"],
            "1fce32b1b4f5a7f52754264be401d6d6a766eadd75c30305852d5761fa92318b",
        )
        self.assertTrue(str(health["detectorMode"]).startswith("onnx-9-class"))

    def test_fifteen_valid_images_match_frozen_topology(self) -> None:
        self.assertEqual(len(self.manifest["valid"]), 15)
        self.assertEqual(
            Counter(case["category"] for case in self.manifest["valid"]),
            {"simple": 5, "multilevel": 5, "nand-nor": 5},
        )
        for case in self.manifest["valid"]:
            with self.subTest(case=case["id"]):
                actual = self._recognize(case)
                expected = json.loads(
                    (FIXTURE_ROOT / case["expectedIr"]).read_text(encoding="utf-8")
                )
                self.assertEqual(actual["unresolved"], [])
                self.assertEqual(len(actual["components"]), case["componentCount"])
                self.assertEqual(len(actual["nets"]), case["netCount"])
                self.assertEqual(
                    Counter(component["kind"] for component in actual["components"]),
                    case["componentKinds"],
                )
                self.assertEqual(
                    self._normalized_endpoints(actual),
                    self._normalized_endpoints(expected),
                )

    def test_three_demo_images_are_repeatable_five_times(self) -> None:
        by_id = {case["id"]: case for case in self.manifest["valid"]}
        for case_id in ("S01_AND", "M02_AND_NOT_OR", "N04_THREE_NAND"):
            case = by_id[case_id]
            results = [self._recognize(case) for _ in range(5)]
            with self.subTest(case=case_id):
                self.assertTrue(all(not result["unresolved"] for result in results))
                self.assertEqual(
                    len({result["semanticHash"] for result in results}), 1
                )

    def test_full_detector_preserves_twenty_real_image_counts(self) -> None:
        real_root = FIXTURE_ROOT.parent / "real-logisim"
        expected = json.loads(
            (real_root / "expected-components.json").read_text(encoding="utf-8")
        )
        detector = self.application.recognizer.component_detector
        for filename, expected_counts in expected.items():
            with self.subTest(image=filename):
                with Image.open(real_root / filename) as image:
                    actual = detector.detect(image)
                self.assertEqual(
                    Counter(item.kind for item in actual), expected_counts
                )

    def test_four_native_v04_images_match_exact_graph(self) -> None:
        native_root = FIXTURE_ROOT.parent / "native-v04"
        truth_paths = sorted(native_root.glob("*.json"))
        self.assertEqual(len(truth_paths), 4)
        for truth_path in truth_paths:
            with self.subTest(image=truth_path.stem):
                truth = json.loads(truth_path.read_text(encoding="utf-8"))
                actual = self.application.recognize(
                    truth_path.with_suffix(".png").read_bytes()
                )
                self.assertEqual(actual["unresolved"], [])
                self.assertEqual(
                    self._normalized_topology(actual),
                    self._normalized_topology(truth),
                )

    def test_four_invalid_images_are_rejected_deterministically(self) -> None:
        self.assertEqual(len(self.manifest["invalid"]), 4)
        for case in self.manifest["invalid"]:
            with self.subTest(case=case["id"]):
                image_bytes = (FIXTURE_ROOT / case["image"]).read_bytes()
                if "expectedServiceCode" in case:
                    with self.assertRaises(ServiceInputError) as raised:
                        self.application.recognize(image_bytes)
                    self.assertEqual(raised.exception.code, case["expectedServiceCode"])
                else:
                    actual = self.application.recognize(image_bytes)
                    self.assertIn(
                        case["expectedIssueCode"],
                        {issue["code"] for issue in actual["unresolved"]},
                    )

    @classmethod
    def _recognize(cls, case: dict[str, object]) -> dict[str, object]:
        return cls.application.recognize((FIXTURE_ROOT / case["image"]).read_bytes())

    @staticmethod
    def _normalized_endpoints(
        ir: dict[str, object],
    ) -> set[frozenset[tuple[object, ...]]]:
        by_id = {component["id"]: component for component in ir["components"]}
        return {
            frozenset(
                (
                    by_id[endpoint["componentId"]]["kind"],
                    tuple(
                        round(value / 10.0) * 10
                        for value in by_id[endpoint["componentId"]]["anchor"]
                    ),
                    endpoint["portId"],
                )
                for endpoint in net["endpoints"]
            )
            for net in ir["nets"]
        }

    @staticmethod
    def _normalized_topology(ir: dict[str, object]) -> tuple[object, ...]:
        components = {
            component["id"]: (
                component["kind"],
                tuple(round(value) for value in component["anchor"]),
            )
            for component in ir["components"]
        }
        return tuple(
            sorted(
                (
                    tuple(
                        sorted(
                            (
                                components[endpoint["componentId"]],
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
        )


if __name__ == "__main__":
    unittest.main()
