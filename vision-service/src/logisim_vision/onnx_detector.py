"""ONNX component detection with the fixed-profile compensation path."""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

from .dataset import CLASS_NAMES as LEGACY_CLASS_NAMES
from .template_detector import FixedScaleTemplateDetector, TemplateDetection


FULL_CLASS_NAMES = (*LEGACY_CLASS_NAMES, "XOR", "XNOR")


class OnnxComponentDetector:
    """Run the exported YOLO detector without depending on Ultralytics."""

    def __init__(
        self,
        model_path: Path,
        confidence: float = 0.35,
        nms_iou: float = 0.7,
        input_size: int = 800,
    ) -> None:
        try:
            import onnxruntime as ort
        except ImportError as exception:
            raise RuntimeError(
                "onnxruntime is required to use models/best.onnx"
            ) from exception
        if not model_path.is_file():
            raise FileNotFoundError(model_path)
        self.confidence = confidence
        self.nms_iou = nms_iou
        self.input_size = input_size
        self.session = ort.InferenceSession(
            str(model_path), providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name
        self.output_name = self.session.get_outputs()[0].name
        self.class_names = self._class_names_for_output_shape(
            self.session.get_outputs()[0].shape
        )

    def detect(self, image: Image.Image) -> list[TemplateDetection]:
        tensor, ratio, left, top = self._letterbox(image)
        output = self.session.run([self.output_name], {self.input_name: tensor})[0]
        boxes, scores, classes = self._decode(
            output, image.size, ratio, left, top
        )
        detections: list[TemplateDetection] = []
        for box, score, class_id in zip(boxes, scores, classes, strict=True):
            x0, y0, x1, y1 = (int(round(value)) for value in box)
            if x0 < x1 and y0 < y1:
                detections.append(
                    TemplateDetection(
                        self.class_names[int(class_id)],
                        (x0, y0, x1, y1),
                        float(score),
                    )
                )
        detections.sort(key=lambda item: (item.body_box[0], item.body_box[1]))
        return detections

    @staticmethod
    def _class_names_for_output_shape(shape: list[object]) -> tuple[str, ...]:
        if len(shape) != 3 or not isinstance(shape[1], int):
            raise RuntimeError(f"Unsupported YOLO output shape: {shape!r}")
        class_count = shape[1] - 4
        if class_count == len(LEGACY_CLASS_NAMES):
            return LEGACY_CLASS_NAMES
        if class_count == len(FULL_CLASS_NAMES):
            return FULL_CLASS_NAMES
        raise RuntimeError(
            "Unsupported detector class count "
            f"{class_count}; expected {len(LEGACY_CLASS_NAMES)} or "
            f"{len(FULL_CLASS_NAMES)}"
        )

    def _letterbox(
        self, image: Image.Image
    ) -> tuple[np.ndarray, float, int, int]:
        image = image.convert("RGB")
        width, height = image.size
        ratio = min(self.input_size / width, self.input_size / height)
        resized_width = round(width * ratio)
        resized_height = round(height * ratio)
        left = round((self.input_size - resized_width) / 2 - 0.1)
        top = round((self.input_size - resized_height) / 2 - 0.1)
        resized = image.resize(
            (resized_width, resized_height), Image.Resampling.BILINEAR
        )
        # The real-image calibration used white Logisim-like padding.
        canvas = np.full((self.input_size, self.input_size, 3), 255, dtype=np.uint8)
        canvas[top : top + resized_height, left : left + resized_width] = np.asarray(
            resized
        )
        tensor = canvas.astype(np.float32).transpose(2, 0, 1)[None] / 255.0
        return tensor, ratio, left, top

    def _decode(
        self,
        output: np.ndarray,
        original_size: tuple[int, int],
        ratio: float,
        left: int,
        top: int,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        predictions = output[0].T
        class_scores = predictions[:, 4:]
        classes = np.argmax(class_scores, axis=1)
        scores = class_scores[np.arange(len(class_scores)), classes]
        selected = scores >= self.confidence
        boxes = self._xywh_to_xyxy(predictions[selected, :4])
        scores = scores[selected]
        classes = classes[selected]
        if not len(boxes):
            return boxes, scores, classes

        boxes[:, [0, 2]] = (boxes[:, [0, 2]] - left) / ratio
        boxes[:, [1, 3]] = (boxes[:, [1, 3]] - top) / ratio
        width, height = original_size
        boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, width)
        boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, height)
        kept = self._class_aware_nms(boxes, scores, classes)
        return boxes[kept], scores[kept], classes[kept]

    @staticmethod
    def _xywh_to_xyxy(boxes: np.ndarray) -> np.ndarray:
        result = np.empty_like(boxes)
        result[:, 0] = boxes[:, 0] - boxes[:, 2] / 2
        result[:, 1] = boxes[:, 1] - boxes[:, 3] / 2
        result[:, 2] = boxes[:, 0] + boxes[:, 2] / 2
        result[:, 3] = boxes[:, 1] + boxes[:, 3] / 2
        return result

    @staticmethod
    def _box_iou(one: np.ndarray, many: np.ndarray) -> np.ndarray:
        x0 = np.maximum(one[0], many[:, 0])
        y0 = np.maximum(one[1], many[:, 1])
        x1 = np.minimum(one[2], many[:, 2])
        y1 = np.minimum(one[3], many[:, 3])
        intersection = np.maximum(0.0, x1 - x0) * np.maximum(0.0, y1 - y0)
        one_area = max(0.0, one[2] - one[0]) * max(0.0, one[3] - one[1])
        many_area = np.maximum(0.0, many[:, 2] - many[:, 0]) * np.maximum(
            0.0, many[:, 3] - many[:, 1]
        )
        return intersection / np.maximum(one_area + many_area - intersection, 1e-9)

    def _class_aware_nms(
        self, boxes: np.ndarray, scores: np.ndarray, classes: np.ndarray
    ) -> np.ndarray:
        kept: list[int] = []
        for class_id in np.unique(classes):
            indices = np.flatnonzero(classes == class_id)
            indices = indices[np.argsort(scores[indices])[::-1]]
            while len(indices):
                current = int(indices[0])
                kept.append(current)
                if len(indices) == 1:
                    break
                remaining = indices[1:]
                indices = remaining[
                    self._box_iou(boxes[current], boxes[remaining]) <= self.nms_iou
                ]
        return np.asarray(
            sorted(kept, key=lambda index: scores[index], reverse=True),
            dtype=np.int64,
        )


class HybridComponentDetector:
    """Combine fixed-profile geometry with high-confidence ONNX detections.

    Native black-wire images use the full nine-class detector directly.  Fixed-profile
    detections preserve the calibrated pin and gate geometry only for the legacy green
    screenshot path, where ONNX may add an unmatched high-confidence component,
    correct the known pin/NOT ambiguity, and promote an overlapping legacy gate to
    XOR or XNOR when the full nine-class model identifies the extended class.
    """

    _GATE_KINDS = frozenset(("AND", "OR", "NAND", "NOR", "XOR", "XNOR"))

    def __init__(
        self,
        onnx_detector: OnnxComponentDetector,
        template_detector: FixedScaleTemplateDetector | None = None,
        compatibility_detector: OnnxComponentDetector | None = None,
    ) -> None:
        self.onnx_detector = onnx_detector
        self.template_detector = template_detector or FixedScaleTemplateDetector()
        self.compatibility_detector = compatibility_detector

    def detect(self, image: Image.Image) -> list[TemplateDetection]:
        legacy_profile = self._uses_legacy_green_profile(image)
        learned = self.onnx_detector.detect(image)
        if not legacy_profile:
            learned.sort(
                key=lambda item: (item.body_box[0], item.body_box[1], item.kind)
            )
            return learned

        result = list(self._template_detections(image, legacy_profile))
        # The week-one detector remains much better calibrated for the original
        # real-screenshot profile.  Merge it first when the full nine-class
        # detector is deployed, then let the full detector contribute the new
        # XOR/XNOR classes and any missing high-confidence base component.
        if (
            self.compatibility_detector is not None
            and legacy_profile
        ):
            self._merge(result, self.compatibility_detector.detect(image))
        self._merge(result, learned)

        result.sort(key=lambda item: (item.body_box[0], item.body_box[1], item.kind))
        return result

    def _template_detections(
        self, image: Image.Image, legacy_profile: bool
    ) -> list[TemplateDetection]:
        if not legacy_profile or not hasattr(self.template_detector, "detect_scaled"):
            return self.template_detector.detect(image)

        # Import lazily to keep the component detector usable on its own and
        # reuse the same grid estimator as the topology stage.
        from .wire_extractor import OrthogonalWireExtractor

        try:
            scale = OrthogonalWireExtractor().estimate_grid(image).scale
        except ValueError:
            return self.template_detector.detect(image)
        return self.template_detector.detect_scaled(image, scale)

    def _merge(
        self,
        result: list[TemplateDetection],
        learned: list[TemplateDetection],
    ) -> None:
        for candidate in learned:
            overlaps = [
                self._component_overlap(candidate.body_box, template.body_box)
                for template in result
            ]
            best_index = (
                max(range(len(result)), key=overlaps.__getitem__)
                if overlaps
                else None
            )
            if best_index is None or overlaps[best_index] < 0.25:
                if candidate.confidence >= 0.75:
                    result.append(candidate)
                continue

            template = result[best_index]
            if template.kind == candidate.kind:
                result[best_index] = TemplateDetection(
                    template.kind,
                    template.body_box,
                    max(template.confidence, candidate.confidence),
                    template.gate_size,
                )
            elif (
                template.kind == "NOT"
                and candidate.kind in ("INPUT_PIN", "OUTPUT_PIN")
                and candidate.confidence >= 0.75
            ):
                result[best_index] = TemplateDetection(
                    candidate.kind,
                    template.body_box,
                    max(template.confidence, candidate.confidence),
                    None,
                )
            elif (
                template.kind in self._GATE_KINDS
                and candidate.kind in self._GATE_KINDS
                and candidate.confidence >= 0.75
            ):
                result[best_index] = TemplateDetection(
                    candidate.kind,
                    template.body_box,
                    max(template.confidence, candidate.confidence),
                    template.gate_size,
                )

    @staticmethod
    def _component_overlap(
        first: tuple[int, int, int, int], second: tuple[int, int, int, int]
    ) -> float:
        x0 = max(first[0], second[0])
        y0 = max(first[1], second[1])
        x1 = min(first[2], second[2])
        y1 = min(first[3], second[3])
        intersection = max(0, x1 - x0) * max(0, y1 - y0)
        first_area = (first[2] - first[0]) * (first[3] - first[1])
        second_area = (second[2] - second[0]) * (second[3] - second[1])
        iou = intersection / max(first_area + second_area - intersection, 1)
        containment = intersection / max(min(first_area, second_area), 1)
        return max(iou, containment)

    @staticmethod
    def _uses_legacy_green_profile(image: Image.Image) -> bool:
        """Identify the original Logisim screenshot palette.

        The legacy screenshots contain thousands of dark-green wire pixels,
        while the native v0.4 training renderer uses black wires.  Routing on
        that stable palette feature prevents the compatibility model from
        changing the exact boxes produced by the nine-class model on v0.4.
        """
        pixels = np.asarray(image.convert("RGB"), dtype=np.int16)
        red = pixels[:, :, 0]
        green = pixels[:, :, 1]
        blue = pixels[:, :, 2]
        legacy_green = (
            (green >= 70) & ((green - red) >= 35) & ((green - blue) >= 35)
        )
        return int(np.count_nonzero(legacy_green)) >= 100


__all__ = ["FULL_CLASS_NAMES", "HybridComponentDetector", "OnnxComponentDetector"]
