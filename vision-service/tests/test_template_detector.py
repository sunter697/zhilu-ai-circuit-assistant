from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import sys
import unittest

from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.template_detector import FixedScaleTemplateDetector  # noqa: E402


class FixedScaleTemplateDetectorTest(unittest.TestCase):
    def test_real_logisim_regression_set(self) -> None:
        fixture_root = Path(__file__).parent / "fixtures" / "real-logisim"
        expected = json.loads(
            (fixture_root / "expected-components.json").read_text(encoding="utf-8")
        )
        detector = FixedScaleTemplateDetector()

        self.assertEqual(len(expected), 20)
        for filename, expected_counts in expected.items():
            with self.subTest(filename=filename):
                image_path = fixture_root / filename
                self.assertTrue(image_path.is_file(), image_path)
                detections = detector.detect_path(image_path)
                actual_counts = dict(Counter(item.kind for item in detections))
                self.assertEqual(actual_counts, expected_counts)
                for detection in detections:
                    x0, y0, x1, y1 = detection.body_box
                    self.assertLess(x0, x1)
                    self.assertLess(y0, y1)
                    self.assertGreaterEqual(detection.confidence, 0.9)
                    if detection.kind not in ("INPUT_PIN", "OUTPUT_PIN"):
                        self.assertIn(detection.gate_size, ("NARROW", "MEDIUM", "WIDE"))

    def test_configuration_is_validated(self) -> None:
        with self.assertRaises(ValueError):
            FixedScaleTemplateDetector(dark_threshold=256)
        with self.assertRaises(ValueError):
            FixedScaleTemplateDetector(gap_radius=-1)

    def test_scale_normalization_preserves_component_counts(self) -> None:
        fixture_root = Path(__file__).parent / "fixtures" / "real-logisim"
        source_path = fixture_root / "T01_AND2.png"
        detector = FixedScaleTemplateDetector()
        with Image.open(source_path) as opened:
            source = opened.convert("RGB")
        reduced = source.resize(
            (round(source.width * 0.8), round(source.height * 0.8)),
            Image.Resampling.NEAREST,
        )

        expected = Counter(item.kind for item in detector.detect(source))
        actual = Counter(item.kind for item in detector.detect_scaled(reduced, 1.0))

        self.assertEqual(actual, expected)

    def test_scale_normalization_supports_half_to_double_zoom(self) -> None:
        fixture_root = Path(__file__).parent / "fixtures" / "real-logisim"
        source_path = fixture_root / "T04_NAND2.png"
        detector = FixedScaleTemplateDetector()
        with Image.open(source_path) as opened:
            source = opened.convert("RGB")

        expected = Counter(item.kind for item in detector.detect(source))
        for factor in (0.5, 0.6, 0.8, 1.2, 1.6):
            with self.subTest(factor=factor):
                resized = source.resize(
                    (round(source.width * factor), round(source.height * factor)),
                    Image.Resampling.NEAREST,
                )
                actual = Counter(
                    item.kind
                    for item in detector.detect_scaled(resized, 1.25 * factor)
                )
                self.assertEqual(actual, expected)

    def test_scale_is_validated(self) -> None:
        image = Image.new("RGB", (10, 10), "white")
        detector = FixedScaleTemplateDetector()
        with self.assertRaises(ValueError):
            detector.detect_scaled(image, 0.49)


if __name__ == "__main__":
    unittest.main()
