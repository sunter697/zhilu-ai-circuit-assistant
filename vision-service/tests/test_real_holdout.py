from __future__ import annotations

from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.real_holdout import (  # noqa: E402
    _match_points,
    evaluate,
    load_benchmark,
)


class _FakeApplication:
    MODEL_SHA256 = "a" * 64

    def __init__(self, result: dict[str, object]) -> None:
        self.result = result

    def health(self) -> dict[str, object]:
        return {
            "status": "ok",
            "protocolVersion": "vision-ir/0.1",
            "serviceVersion": "test",
            "modelVersion": "test",
            "modelSha256": self.MODEL_SHA256,
            "detectorMode": "injected-test",
        }

    def recognize(self, image_bytes: bytes) -> dict[str, object]:
        if not image_bytes:
            raise AssertionError("test image must not be empty")
        return self.result


class RealHoldoutTest(unittest.TestCase):
    def test_exact_graph_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, truth = self._write_benchmark(Path(directory))
            result = evaluate(
                root,
                minimum_cases=1,
                application=_FakeApplication(truth),
            )
        self.assertEqual(result["overall"]["componentTp"], 2)
        self.assertEqual(result["overall"]["netTp"], 1)
        self.assertEqual(result["overall"]["exactGraphImages"], 1)
        self.assertEqual(result["failures"], [])
        self.assertFalse(result["acceptance"]["eligible"])
        self.assertIsNone(result["acceptance"]["passed"])

    def test_component_and_net_errors_are_separated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, truth = self._write_benchmark(Path(directory))
            actual = json.loads(json.dumps(truth))
            actual["components"][1]["kind"] = "AND"
            actual["nets"] = []
            result = evaluate(
                root,
                minimum_cases=1,
                application=_FakeApplication(actual),
            )
        self.assertEqual(result["overall"]["componentTp"], 1)
        self.assertEqual(result["overall"]["componentFp"], 1)
        self.assertEqual(result["overall"]["componentFn"], 1)
        self.assertEqual(result["overall"]["netFn"], 1)
        self.assertEqual(result["overall"]["exactGraphImages"], 0)
        self.assertEqual(len(result["failures"]), 1)

    def test_non_independent_manifest_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _ = self._write_benchmark(Path(directory))
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["declarations"]["notUsedForThresholdSelection"] = False
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "notUsedForThresholdSelection"):
                load_benchmark(root, minimum_cases=1)

    def test_runtime_model_fingerprint_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, truth = self._write_benchmark(Path(directory))
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["expectedRuntime"]["modelSha256"] = "b" * 64
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "model SHA-256"):
                evaluate(
                    root,
                    minimum_cases=1,
                    application=_FakeApplication(truth),
                )

    def test_regression_mode_never_claims_independent_acceptance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, truth = self._write_benchmark(Path(directory))
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["expectedRuntime"]["serviceVersion"] = "old-version"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            result = evaluate(
                root,
                minimum_cases=1,
                application=_FakeApplication(truth),
                official=False,
            )
        self.assertEqual(result["evaluationMode"], "post-result-regression")
        self.assertFalse(result["settings"]["official"])
        self.assertFalse(result["acceptance"]["eligible"])
        self.assertIsNone(result["acceptance"]["passed"])
        self.assertEqual(result["overall"]["exactGraphImages"], 1)

    def test_changed_image_is_rejected_by_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _ = self._write_benchmark(Path(directory))
            (root / "images" / "R001.png").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "imageSha256 mismatch"):
                load_benchmark(root, minimum_cases=1)

    def test_tolerance_matching_maximizes_the_number_of_points(self) -> None:
        self.assertEqual(
            _match_points([[0.0, 0.0], [4.0, 0.0]], [[3.0, 0.0], [7.0, 0.0]], 4.0),
            (2, 0, 0),
        )

    @staticmethod
    def _write_benchmark(root: Path) -> tuple[Path, dict[str, object]]:
        (root / "images").mkdir(parents=True)
        (root / "annotations").mkdir()
        image_buffer = BytesIO()
        Image.new("RGB", (100, 60), "white").save(image_buffer, format="PNG")
        image_bytes = image_buffer.getvalue()
        image_hash = sha256(image_bytes).hexdigest()
        image_path = root / "images" / "R001.png"
        image_path.write_bytes(image_bytes)
        truth = {
            "schemaVersion": "vision-ir/0.1",
            "source": {"sha256": image_hash, "width": 100, "height": 60},
            "profile": {
                "theme": "light",
                "gateShape": "ansi",
                "gridPixels": 10.0,
                "scale": 1.0,
                "offsetX": 0.0,
                "offsetY": 0.0,
            },
            "components": [
                {
                    "id": "in",
                    "kind": "INPUT_PIN",
                    "anchor": [10.0, 30.0],
                    "ports": [{"id": "out", "direction": "OUTPUT", "point": [10.0, 30.0]}],
                },
                {
                    "id": "out",
                    "kind": "OUTPUT_PIN",
                    "anchor": [90.0, 30.0],
                    "ports": [{"id": "in", "direction": "INPUT", "point": [90.0, 30.0]}],
                },
            ],
            "nets": [
                {
                    "id": "net",
                    "endpoints": [
                        {"componentId": "in", "portId": "out"},
                        {"componentId": "out", "portId": "in"},
                    ],
                    "segments": [[10.0, 30.0, 90.0, 30.0]],
                    "junctions": [],
                }
            ],
            "unresolved": [],
            "semanticHash": "test",
        }
        truth_path = root / "annotations" / "R001.json"
        truth_path.write_text(json.dumps(truth), encoding="utf-8")
        manifest = {
            "schemaVersion": "logisim-real-holdout/0.1",
            "datasetRole": "independent-holdout",
            "frozen": True,
            "frozenAt": "2026-10-26T00:00:00Z",
            "declarations": {
                "modelAndThresholdsFrozenBeforeCollection": True,
                "notUsedForTraining": True,
                "notUsedForThresholdSelection": True,
                "annotationCompletedBeforeEvaluation": True,
            },
            "expectedRuntime": {
                "serviceVersion": "test",
                "modelSha256": _FakeApplication.MODEL_SHA256,
                "detectorModePrefix": "injected-test",
            },
            "cases": [
                {
                    "id": "R001",
                    "image": "images/R001.png",
                    "imageSha256": image_hash,
                    "expectedIr": "annotations/R001.json",
                    "expectedIrSha256": sha256(truth_path.read_bytes()).hexdigest(),
                    "capture": {
                        "applicationVersion": "test",
                        "method": "export-png",
                        "theme": "light",
                        "scalePercent": 100,
                    },
                }
            ],
        }
        (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return root, truth


if __name__ == "__main__":
    unittest.main()
