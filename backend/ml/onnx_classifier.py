from __future__ import annotations
import json
from pathlib import Path
from dataclasses import dataclass
from typing import Sequence
import numpy as np
from PIL import Image, ImageOps
from backend.ml.field_v3_policy import decide_v3


@dataclass(frozen=True)
class OnnxPrediction:
    class_index: int
    class_name: str
    confidence: float
    prototype_distance: float | None
    accepted: bool
    rejection_reason: str | None
    distance_threshold: float | None = None


class InvalidImageQuality(ValueError):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def stable_softmax(values: np.ndarray) -> np.ndarray:
    values = np.asarray(values, dtype=np.float64)
    exponent = np.exp(values - np.max(values))
    return exponent / max(float(exponent.sum()), 1e-12)


class PlantDiseaseOnnxClassifier:

    def __init__(
        self,
        model_path: str,
        metadata_path: str,
        providers: Sequence[str] | None = None,
    ):
        try:
            import onnxruntime as ort
        except ImportError as exc:
            raise RuntimeError("onnxruntime is not installed") from exc
        metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
        self.metadata = metadata
        self.decision_policy_version = int(metadata.get("decision_policy_version", 0))
        if self.decision_policy_version != 3:
            raise ValueError("Unsupported decision policy")
        self.classes = [str(value) for value in metadata["classes"]]
        self.model_name = str(metadata.get("model", "unknown"))
        self.img_size = int(metadata["imgsz"])
        self.threshold = float(metadata.get("abstain_threshold", 0.8))
        normalization = metadata.get("normalization", {})
        self.mean = np.asarray(
            normalization.get("mean", [0.485, 0.456, 0.406]), dtype=np.float32
        ).reshape(1, 1, 3)
        self.std = np.asarray(
            normalization.get("std", [0.229, 0.224, 0.225]), dtype=np.float32
        ).reshape(1, 1, 3)
        if providers is None:
            available = set(ort.get_available_providers())
            providers = [
                name
                for name in ("CUDAExecutionProvider", "CPUExecutionProvider")
                if name in available
            ]
        self.session = ort.InferenceSession(str(model_path), providers=list(providers))
        self.input_name = self.session.get_inputs()[0].name

    def preprocess(self, image_path: str) -> np.ndarray:
        with Image.open(image_path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            image = image.resize(
                (self.img_size, self.img_size), Image.Resampling.BICUBIC
            )
            array = np.asarray(image, dtype=np.float32) / 255.0
        luminance = (
            0.2126 * array[:, :, 0] + 0.7152 * array[:, :, 1] + 0.0722 * array[:, :, 2]
        )
        mean_luminance = float(luminance.mean())
        p05, median_luminance, p95 = (
            float(value) for value in np.percentile(luminance, (5, 50, 95))
        )
        contrast = p95 - p05
        horizontal_detail = float(np.mean(np.abs(np.diff(luminance, axis=1))))
        vertical_detail = float(np.mean(np.abs(np.diff(luminance, axis=0))))
        detail = (horizontal_detail + vertical_detail) / 2.0
        strong_edge_fraction = (
            float(np.mean(np.abs(np.diff(luminance, axis=1)) > 0.035))
            + float(np.mean(np.abs(np.diff(luminance, axis=0)) > 0.035))
        ) / 2.0
        red, green, blue = (array[:, :, 0], array[:, :, 1], array[:, :, 2])
        warm_red_fraction = float(
            np.mean((red > 0.15) & (red - green > 0.065) & (red - blue > 0.055))
        )
        plant_green_fraction = float(
            np.mean((green > 0.12) & (green > red * 1.04) & (green > blue * 1.07))
        )
        if (
            mean_luminance < 0.055
            or p95 < 0.1
            or (median_luminance < 0.08 and p95 < 0.22)
            or (p95 < 0.18 and contrast < 0.1 and (detail < 0.015))
        ):
            raise InvalidImageQuality("image_too_dark")
        if mean_luminance > 0.985:
            raise InvalidImageQuality("image_too_bright")
        if (
            warm_red_fraction > 0.62
            and plant_green_fraction < 0.015
            and (strong_edge_fraction < 0.12 or detail < 0.035)
        ):
            raise InvalidImageQuality("camera_obscured")
        if contrast < 0.035 or float(luminance.std()) < 0.012:
            raise InvalidImageQuality("blank_image")
        if strong_edge_fraction < 0.006 or (
            detail < 0.008 and strong_edge_fraction < 0.02
        ):
            raise InvalidImageQuality("image_too_blurry")
        array = (array - self.mean) / self.std
        return np.transpose(array, (2, 0, 1))[None].astype(np.float32, copy=False)

    def predict(
        self, image_path: str, crop_prefix: str | None = None
    ) -> OnnxPrediction:
        try:
            model_input = self.preprocess(image_path)
        except InvalidImageQuality as exc:
            return OnnxPrediction(-1, "unknown", 0.0, None, False, exc.reason)
        outputs = self.session.run(None, {self.input_name: model_input})
        if len(outputs) != 3:
            raise ValueError("Expected logits, embedding and plant score")
        return OnnxPrediction(
            **decide_v3(
                outputs[0][0],
                outputs[1][0],
                outputs[2][0],
                self.classes,
                self.metadata,
                crop_prefix,
            )
        )
