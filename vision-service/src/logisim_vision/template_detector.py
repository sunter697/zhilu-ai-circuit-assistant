"""Profile-calibrated Logisim component detector used by the hybrid recognizer.

The trained detector remains the primary path.  This detector deliberately uses
the much narrower one-week profile: light background, ANSI symbols, east-facing
components and the native Logisim zoom level.  Under those constraints the dark
component outlines can be separated from green wires, blue labels and grid
dots.  Small gaps caused by wires crossing an outline are joined before the
outline is classified.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


DETECTOR_VERSION = "profile-calibrated-template/0.3"


@dataclass(frozen=True)
class TemplateDetection:
    """One component found by the profile-calibrated fallback detector."""

    kind: str
    body_box: tuple[int, int, int, int]
    confidence: float
    gate_size: str | None = None


@dataclass(frozen=True)
class _Outline:
    body_box: tuple[int, int, int, int]
    dark_pixels: int

    @property
    def width(self) -> int:
        return self.body_box[2] - self.body_box[0]

    @property
    def height(self) -> int:
        return self.body_box[3] - self.body_box[1]

    @property
    def center_x(self) -> float:
        return (self.body_box[0] + self.body_box[2]) / 2.0


class FixedScaleTemplateDetector:
    """Detect supported Logisim components from their dark outlines.

    This remains a deterministic compensation path rather than a general
    computer-vision detector.  Measurements are normalized by the detected
    Logisim grid scale, while gate height selects NARROW, MEDIUM, or WIDE.
    The learned detector remains responsible for XOR/XNOR discrimination.
    """

    def __init__(self, dark_threshold: int = 90, gap_radius: int = 2) -> None:
        if not 0 <= dark_threshold <= 255:
            raise ValueError("dark_threshold must be between 0 and 255")
        if gap_radius < 0:
            raise ValueError("gap_radius must not be negative")
        self.dark_threshold = dark_threshold
        self.gap_radius = gap_radius

    def detect_path(self, path: Path) -> list[TemplateDetection]:
        with Image.open(path) as image:
            return self.detect(image)

    def detect(self, image: Image.Image) -> list[TemplateDetection]:
        """Detect the original 125% screenshot profile."""

        return self.detect_scaled(image, 1.25)

    def detect_scaled(
        self,
        image: Image.Image,
        scale: float,
        reference_scale: float = 1.25,
    ) -> list[TemplateDetection]:
        """Detect a legacy screenshot after normalizing its measured scale.

        Logisim scales both component dimensions and stroke area.  Keeping the
        image untouched avoids interpolation artefacts; only the classification
        measurements are converted to the calibrated 125% reference profile.
        The returned boxes always remain in original image coordinates.
        """

        if not 0.5 <= scale <= 2.5:
            raise ValueError("scale must be between 0.5 and 2.5")
        if not 0.5 <= reference_scale <= 2.0:
            raise ValueError("reference_scale must be between 0.5 and 2.0")
        rgb = np.asarray(image.convert("RGB"))
        if rgb.ndim != 3 or rgb.shape[0] == 0 or rgb.shape[1] == 0:
            raise ValueError("image must contain at least one RGB pixel")

        dark = np.max(rgb, axis=2) < self.dark_threshold
        joined = self._dilate(dark, self.gap_radius)
        outlines = self._connected_outlines(dark, joined, self.gap_radius)
        image_width = image.width

        detections: list[TemplateDetection] = []
        for outline in outlines:
            classified = self._classify_scaled(
                outline,
                image_width,
                scale / reference_scale,
                reference_scale,
            )
            if classified is not None:
                detections.append(classified)
        detections.sort(key=lambda item: (item.body_box[0], item.body_box[1], item.kind))
        return detections

    @staticmethod
    def _classify_scaled(
        outline: _Outline,
        image_width: int,
        scale_ratio: float,
        reference_scale: float = 1.25,
    ) -> TemplateDetection | None:
        width = outline.width / scale_ratio
        height = outline.height / scale_ratio
        pixels = outline.dark_pixels / (scale_ratio * scale_ratio)

        logical_width = width / reference_scale
        logical_height = height / reference_scale

        # Pin bounds vary slightly between the native renderer and copied UI
        # screenshots because the value glyph touches the symbol outline.
        # Classify in logical units and check pins before gates so a 100%-zoom
        # pin cannot be mistaken for a small NOR outline.
        if (
            32 <= logical_width <= 43
            and 17 <= logical_height <= 25
            and pixels >= 140
        ):
            kind = "INPUT_PIN" if outline.center_x < image_width / 2.0 else "OUTPUT_PIN"
            return TemplateDetection(kind, outline.body_box, 0.98)

        if (
            16 <= logical_width <= 38
            and 8 <= logical_height <= 23
            and logical_width / max(logical_height, 1.0) >= 1.35
            and pixels >= 75
        ):
            gate_size = "NARROW" if logical_width < 25 else "WIDE"
            return TemplateDetection("NOT", outline.body_box, 0.97, gate_size)

        gate_size = FixedScaleTemplateDetector._multi_input_size(logical_height)
        if gate_size is not None:
            nominal = {"NARROW": 30.0, "MEDIUM": 50.0, "WIDE": 70.0}[gate_size]
            # A normal ANSI gate is approximately square.  An output bubble
            # adds one logical grid unit to the connected dark outline.
            if not nominal - 7.0 <= logical_width <= nominal + 17.0:
                return None
            inverted = logical_width - logical_height >= 5.0
            # Stroke area grows roughly with the configured logical gate size.
            # Separate bubble/no-bubble thresholds retain the calibrated
            # AND-vs-OR margin even after nearest-neighbour UI scaling.
            stroke_score = pixels / nominal
            and_like = stroke_score >= (12.3 if inverted else 9.6)
            if inverted:
                kind = "NAND" if and_like else "NOR"
            else:
                kind = "AND" if and_like else "OR"
            return TemplateDetection(kind, outline.body_box, 0.96, gate_size)

        return None

    @staticmethod
    def _multi_input_size(logical_height: float) -> str | None:
        if 22.0 <= logical_height < 40.0:
            return "NARROW"
        if 40.0 <= logical_height < 60.0:
            return "MEDIUM"
        if 60.0 <= logical_height <= 80.0:
            return "WIDE"
        return None

    @staticmethod
    def _dilate(mask: np.ndarray, radius: int) -> np.ndarray:
        if radius == 0:
            return mask.copy()
        height, width = mask.shape
        result = mask.copy()
        for offset_y in range(-radius, radius + 1):
            for offset_x in range(-radius, radius + 1):
                target_y = slice(max(0, offset_y), min(height, height + offset_y))
                target_x = slice(max(0, offset_x), min(width, width + offset_x))
                source_y = slice(max(0, -offset_y), min(height, height - offset_y))
                source_x = slice(max(0, -offset_x), min(width, width - offset_x))
                result[target_y, target_x] |= mask[source_y, source_x]
        return result

    @staticmethod
    def _connected_outlines(
        dark: np.ndarray, joined: np.ndarray, radius: int
    ) -> list[_Outline]:
        height, width = joined.shape
        visited = np.zeros_like(joined, dtype=bool)
        outlines: list[_Outline] = []

        for start_y, start_x in zip(*np.where(joined)):
            if visited[start_y, start_x]:
                continue
            visited[start_y, start_x] = True
            pending = [(int(start_y), int(start_x))]
            min_x = max_x = int(start_x)
            min_y = max_y = int(start_y)

            while pending:
                y_value, x_value = pending.pop()
                min_x = min(min_x, x_value)
                max_x = max(max_x, x_value)
                min_y = min(min_y, y_value)
                max_y = max(max_y, y_value)
                for delta_y in (-1, 0, 1):
                    for delta_x in (-1, 0, 1):
                        if delta_x == 0 and delta_y == 0:
                            continue
                        next_y = y_value + delta_y
                        next_x = x_value + delta_x
                        if (
                            0 <= next_y < height
                            and 0 <= next_x < width
                            and joined[next_y, next_x]
                            and not visited[next_y, next_x]
                        ):
                            visited[next_y, next_x] = True
                            pending.append((next_y, next_x))

            x0 = max(0, min_x + radius)
            y0 = max(0, min_y + radius)
            x1 = min(width, max_x - radius + 1)
            y1 = min(height, max_y - radius + 1)
            if x1 <= x0 or y1 <= y0:
                continue
            dark_pixels = int(np.count_nonzero(dark[y0:y1, x0:x1]))
            if dark_pixels >= 50:
                outlines.append(_Outline((x0, y0, x1, y1), dark_pixels))
        return outlines

    @staticmethod
    def _classify(
        outline: _Outline, image_width: int
    ) -> TemplateDetection | None:
        return FixedScaleTemplateDetector._classify_scaled(
            outline, image_width, 1.0, 1.25
        )


__all__ = ["DETECTOR_VERSION", "FixedScaleTemplateDetector", "TemplateDetection"]
