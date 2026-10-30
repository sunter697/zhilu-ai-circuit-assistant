from __future__ import annotations

from pathlib import Path
import sys

import pytest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.onnx_detector import (  # noqa: E402
    FULL_CLASS_NAMES,
    OnnxComponentDetector,
)


def test_accepts_legacy_seven_class_output() -> None:
    names = OnnxComponentDetector._class_names_for_output_shape([1, 11, 13125])

    assert names == (
        "INPUT_PIN",
        "OUTPUT_PIN",
        "AND",
        "OR",
        "NOT",
        "NAND",
        "NOR",
    )


def test_accepts_full_nine_class_output() -> None:
    names = OnnxComponentDetector._class_names_for_output_shape([1, 13, 13125])

    assert names == FULL_CLASS_NAMES
    assert names[-2:] == ("XOR", "XNOR")


def test_rejects_unknown_class_count() -> None:
    with pytest.raises(RuntimeError, match="Unsupported detector class count 8"):
        OnnxComponentDetector._class_names_for_output_shape([1, 12, 13125])
