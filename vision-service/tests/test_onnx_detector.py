from __future__ import annotations

from pathlib import Path
import sys
import unittest

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.onnx_detector import HybridComponentDetector  # noqa: E402
from logisim_vision.template_detector import TemplateDetection  # noqa: E402


class _DetectorStub:
    def __init__(self, detections: list[TemplateDetection]) -> None:
        self.detections = detections

    def detect(self, image: Image.Image) -> list[TemplateDetection]:
        return list(self.detections)


class HybridComponentDetectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.image = Image.new("RGB", (200, 100), "white")
        self.legacy_image = Image.new("RGB", (200, 100), (0, 100, 0))

    def test_unmatched_high_confidence_onnx_component_is_added(self) -> None:
        learned = _DetectorStub([TemplateDetection("AND", (80, 20, 140, 80), 0.95)])
        fixed = _DetectorStub([])
        result = HybridComponentDetector(learned, fixed).detect(self.legacy_image)
        self.assertEqual(result, learned.detections)

    def test_low_confidence_unmatched_component_is_ignored(self) -> None:
        learned = _DetectorStub([TemplateDetection("AND", (80, 20, 140, 80), 0.74)])
        result = HybridComponentDetector(learned, _DetectorStub([])).detect(
            self.legacy_image
        )
        self.assertEqual(result, [])

    def test_high_confidence_pin_corrects_small_not_ambiguity(self) -> None:
        learned_pin = TemplateDetection("INPUT_PIN", (8, 20, 55, 48), 0.99)
        fixed_not = TemplateDetection("NOT", (11, 23, 52, 45), 0.97)
        result = HybridComponentDetector(
            _DetectorStub([learned_pin]), _DetectorStub([fixed_not])
        ).detect(self.legacy_image)
        self.assertEqual(
            result,
            [TemplateDetection("INPUT_PIN", fixed_not.body_box, 0.99)],
        )

    def test_learned_gate_class_uses_fixed_geometry_on_disagreement(self) -> None:
        fixed = TemplateDetection("NAND", (80, 20, 130, 65), 0.96)
        learned = TemplateDetection("NOR", (79, 19, 132, 66), 0.99)
        result = HybridComponentDetector(
            _DetectorStub([learned]), _DetectorStub([fixed])
        ).detect(self.legacy_image)
        self.assertEqual(
            result,
            [TemplateDetection("NOR", fixed.body_box, learned.confidence)],
        )

    def test_legacy_compatibility_detector_corrects_old_profile_pin(self) -> None:
        fixed_not = TemplateDetection("NOT", (11, 23, 52, 45), 0.97)
        weak_full_pin = TemplateDetection("INPUT_PIN", (12, 24, 50, 44), 0.60)
        legacy_pin = TemplateDetection("INPUT_PIN", (8, 20, 55, 48), 0.99)
        result = HybridComponentDetector(
            _DetectorStub([weak_full_pin]),
            _DetectorStub([fixed_not]),
            compatibility_detector=_DetectorStub([legacy_pin]),
        ).detect(self.legacy_image)
        self.assertEqual(
            result,
            [TemplateDetection("INPUT_PIN", fixed_not.body_box, 0.99)],
        )

    def test_full_detector_promotes_legacy_or_to_xor(self) -> None:
        full_xor = TemplateDetection("XOR", (78, 18, 134, 68), 0.98)
        legacy_or = TemplateDetection("OR", (80, 20, 132, 66), 0.97)
        result = HybridComponentDetector(
            _DetectorStub([full_xor]),
            _DetectorStub([]),
            compatibility_detector=_DetectorStub([legacy_or]),
        ).detect(self.legacy_image)
        self.assertEqual(
            result,
            [TemplateDetection("XOR", legacy_or.body_box, 0.98)],
        )

    def test_native_black_wire_profile_skips_legacy_detector(self) -> None:
        full = TemplateDetection("AND", (80, 20, 130, 65), 0.98)
        false_template = TemplateDetection("NOT", (82, 22, 128, 63), 0.99)
        shifted_legacy = TemplateDetection("AND", (70, 15, 140, 70), 0.99)
        result = HybridComponentDetector(
            _DetectorStub([full]),
            _DetectorStub([false_template]),
            compatibility_detector=_DetectorStub([shifted_legacy]),
        ).detect(self.image)
        self.assertEqual(result, [full])


if __name__ == "__main__":
    unittest.main()
