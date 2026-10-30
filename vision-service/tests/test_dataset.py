from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.dataset import CLASS_NAMES, generate_dataset, validate_dataset  # noqa: E402


class DatasetGeneratorTest(unittest.TestCase):
    def test_generation_is_deterministic_and_self_validating(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first_summary = generate_dataset(first, count=24, seed=20261021)
            second_summary = generate_dataset(second, count=24, seed=20261021)

            self.assertEqual(first_summary, second_summary)
            self.assertEqual(
                (first / "manifest.jsonl").read_bytes(),
                (second / "manifest.jsonl").read_bytes(),
            )
            first_images = sorted((first / "images").rglob("*.png"))
            second_images = sorted((second / "images").rglob("*.png"))
            self.assertEqual(len(first_images), 24)
            self.assertEqual(
                [path.read_bytes() for path in first_images],
                [path.read_bytes() for path in second_images],
            )

    def test_dataset_covers_all_submission_classes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "coverage"
            summary = generate_dataset(output, count=64, seed=73021)

            self.assertEqual(summary["samples"], 64)
            self.assertEqual(summary, validate_dataset(output))
            self.assertGreater(summary["splits"]["train"], 0)
            self.assertGreater(summary["splits"]["val"], 0)
            for class_name in CLASS_NAMES:
                self.assertGreater(summary["classes"][class_name], 0, class_name)
            self.assertTrue((output / "data.yaml").is_file())

    def test_existing_output_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "existing"
            output.mkdir()
            sentinel = output / "keep.txt"
            sentinel.write_text("keep", encoding="utf-8")

            with self.assertRaises(FileExistsError):
                generate_dataset(output, count=1, seed=1)
            self.assertEqual(sentinel.read_text(encoding="utf-8"), "keep")


if __name__ == "__main__":
    unittest.main()
