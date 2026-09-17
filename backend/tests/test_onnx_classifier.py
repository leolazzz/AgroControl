import numpy as np
import pytest
from PIL import Image

from backend.ml.onnx_classifier import PlantDiseaseOnnxClassifier, stable_softmax


class FakeSession:
    def __init__(self, logits, embedding):
        self.outputs = [
            np.asarray([logits], dtype=np.float32),
            np.asarray([embedding], dtype=np.float32),
            np.asarray([[20.0]], dtype=np.float32),
        ]

    def run(self, _output_names, _feeds):
        return self.outputs


def fake_classifier(logits, embedding, threshold=0.60, distance_threshold=0.20):
    classifier = PlantDiseaseOnnxClassifier.__new__(PlantDiseaseOnnxClassifier)
    classifier.classes = ["cucumber_healthy", "tomato_healthy", "tomato_late_blight"]
    classifier.model_name = "fake"
    classifier.threshold = threshold
    classifier.metadata = {
        "temperature": 1.0,
        "abstain_threshold": threshold,
        "healthy_threshold": threshold,
        "plant_gate_enabled": True,
        "plant_threshold": 0.9,
        "prototype_centers": dict(
            zip(classifier.classes, [[[1.0, 0.0]], [[0.0, 1.0]], [[1.0, 0.0]]])
        ),
        "prototype_distance_thresholds": {
            name: distance_threshold for name in classifier.classes
        },
    }
    classifier.session = FakeSession(logits, embedding)
    classifier.input_name = "image"
    classifier.preprocess = lambda _path: np.zeros((1, 3, 8, 8), dtype=np.float32)
    return classifier


def test_stable_softmax_is_normalized_for_large_logits():
    values = stable_softmax(np.array([1000.0, 1001.0]))
    assert np.isclose(values.sum(), 1.0)
    assert values[1] > values[0]


def test_crop_conditioning_happens_before_softmax():
    classifier = fake_classifier([9.0, 2.0, 1.0], [0.0, 1.0])
    prediction = classifier.predict("unused.jpg", "tomato")
    assert prediction.class_name == "tomato_healthy"
    assert prediction.accepted is True
    assert prediction.confidence > 0.70


def test_prototype_distance_rejects_confident_ood_image():
    classifier = fake_classifier([0.0, 8.0, 1.0], [1.0, 0.0])
    prediction = classifier.predict("unused.jpg", "tomato")
    assert prediction.confidence > 0.99
    assert prediction.accepted is False
    assert prediction.rejection_reason == "out_of_distribution"


def test_confident_prediction_respects_calibrated_distance():

    embedding = [float(np.sqrt(1.0 - 0.97**2)), 0.97]
    classifier = fake_classifier(
        [0.0, 8.0, 0.0], embedding, threshold=0.50, distance_threshold=0.02
    )

    prediction = classifier.predict("unused.jpg", "tomato")

    assert prediction.confidence > 0.99
    assert prediction.prototype_distance == pytest.approx(0.03, abs=1e-5)
    assert prediction.distance_threshold == pytest.approx(0.02)
    assert prediction.accepted is False


def test_weak_prediction_respects_calibrated_distance():
    embedding = [float(np.sqrt(1.0 - 0.97**2)), 0.97]
    classifier = fake_classifier(
        [0.0, 0.4, 0.0], embedding, threshold=0.50, distance_threshold=0.02
    )

    prediction = classifier.predict("unused.jpg", "tomato")

    assert 0.50 < prediction.confidence < 0.70
    assert prediction.accepted is False
    assert prediction.rejection_reason == "out_of_distribution"


def test_healthy_prediction_requires_calibrated_confidence():
    embedding = [float(np.sqrt(1.0 - 0.97**2)), 0.97]
    classifier = fake_classifier(
        [0.0, 2.0, 0.0], embedding, threshold=0.50, distance_threshold=0.02
    )

    classifier.metadata["healthy_threshold"] = 0.95
    prediction = classifier.predict("unused.jpg", "tomato")

    assert 0.85 < prediction.confidence < 0.90
    assert prediction.class_name == "tomato_healthy"
    assert prediction.accepted is False
    assert prediction.rejection_reason == "low_confidence"


def test_low_confidence_has_explicit_rejection_reason():
    classifier = fake_classifier([0.0, 0.1, 0.0], [0.0, 1.0], threshold=0.80)
    prediction = classifier.predict("unused.jpg", "tomato")
    assert prediction.accepted is False
    assert prediction.rejection_reason == "low_confidence"


def test_black_image_is_rejected_before_onnx_inference(tmp_path):
    classifier = fake_classifier([0.0, 20.0, 0.0], [0.0, 1.0], threshold=0.0)
    del classifier.preprocess
    classifier.img_size = 32
    classifier.mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(
        1, 1, 3
    )
    classifier.std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(
        1, 1, 3
    )
    image_path = tmp_path / "black.png"
    Image.new("RGB", (64, 64), color=(0, 0, 0)).save(image_path)

    prediction = classifier.predict(str(image_path), "tomato")

    assert prediction.class_name == "unknown"
    assert prediction.class_index == -1
    assert prediction.confidence == 0.0
    assert prediction.accepted is False
    assert prediction.rejection_reason == "image_too_dark"


def test_dark_red_covered_camera_frame_is_rejected(tmp_path):
    classifier = fake_classifier([0.0, 20.0, 0.0], [0.0, 1.0], threshold=0.0)
    del classifier.preprocess
    classifier.img_size = 64
    classifier.mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(
        1, 1, 3
    )
    classifier.std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(
        1, 1, 3
    )
    rng = np.random.default_rng(7)
    covered = np.empty((96, 96, 3), dtype=np.uint8)
    covered[:] = (58, 16, 13)
    covered = np.clip(
        covered.astype(np.int16) + rng.integers(-5, 6, covered.shape), 0, 255
    ).astype(np.uint8)
    image_path = tmp_path / "covered-camera.jpg"
    Image.fromarray(covered).save(image_path, quality=95)

    prediction = classifier.predict(str(image_path), "tomato")

    assert prediction.accepted is False
    assert prediction.class_name == "unknown"
    assert prediction.rejection_reason == "image_too_dark"


def test_bright_red_lens_occlusion_is_rejected(tmp_path):
    classifier = fake_classifier([0.0, 20.0, 0.0], [0.0, 1.0], threshold=0.0)
    del classifier.preprocess
    classifier.img_size = 96
    classifier.mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(
        1, 1, 3
    )
    classifier.std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(
        1, 1, 3
    )

    height, width = 180, 180
    yy, xx = np.mgrid[-1 : 1 : complex(height), -1 : 1 : complex(width)]
    glow = np.clip(1.0 - np.sqrt(xx * xx + yy * yy), 0.0, 1.0)
    rng = np.random.default_rng(11)
    image = np.stack(
        [75 + 145 * glow, 20 + 68 * glow, 14 + 48 * glow],
        axis=-1,
    )
    image += rng.normal(0, 5, image.shape)
    image = np.clip(image, 0, 255).astype(np.uint8)
    image_path = tmp_path / "finger-over-lens.jpg"
    Image.fromarray(image).save(image_path, quality=92)

    prediction = classifier.predict(str(image_path), "tomato")

    assert prediction.accepted is False
    assert prediction.class_name == "unknown"
    assert prediction.confidence == 0.0
    assert prediction.rejection_reason == "camera_obscured"


def test_neutral_smooth_lens_occlusion_is_rejected_as_blurry(tmp_path):
    classifier = fake_classifier([0.0, 20.0, 0.0], [0.0, 1.0], threshold=0.0)
    del classifier.preprocess
    classifier.img_size = 96
    classifier.mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(
        1, 1, 3
    )
    classifier.std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(
        1, 1, 3
    )

    height, width = 180, 180
    gradient = np.linspace(75, 155, width, dtype=np.float32)[None, :]
    image = np.repeat(gradient, height, axis=0)
    image = np.stack([image, image * 0.97, image * 0.94], axis=-1).astype(np.uint8)
    image_path = tmp_path / "smooth-covered-camera.png"
    Image.fromarray(image).save(image_path)

    prediction = classifier.predict(str(image_path), "tomato")

    assert prediction.accepted is False
    assert prediction.class_name == "unknown"
    assert prediction.rejection_reason == "image_too_blurry"


def test_visible_leaf_on_black_background_is_not_rejected_as_dark(tmp_path):
    classifier = fake_classifier([0.0, 20.0, 0.0], [0.0, 1.0], threshold=0.0)
    del classifier.preprocess
    classifier.img_size = 64
    classifier.mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(
        1, 1, 3
    )
    classifier.std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(
        1, 1, 3
    )
    image = np.zeros((96, 96, 3), dtype=np.uint8)
    image[18:78, 22:74] = (35, 155, 48)
    image[22:74:4, 24:72] = (55, 195, 65)
    image_path = tmp_path / "leaf-on-black.png"
    Image.fromarray(image).save(image_path)

    prediction = classifier.predict(str(image_path), "tomato")

    assert prediction.accepted is True
    assert prediction.class_name == "tomato_healthy"


def test_nonplant_score_rejects_confident_disease():
    classifier = fake_classifier([0.0, 8.0, 1.0], [0.0, 1.0])
    classifier.session.outputs[2][:] = -20.0
    prediction = classifier.predict("unused.jpg", "tomato")
    assert prediction.confidence > 0.99
    assert not prediction.accepted
    assert prediction.rejection_reason == "out_of_distribution"
