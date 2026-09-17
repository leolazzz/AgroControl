import numpy as np


def scores_v3(logits, embedding, plant_logit, classes, metadata, crop_prefix=None):
    allowed = [
        i
        for i, name in enumerate(classes)
        if crop_prefix is None or name.startswith(crop_prefix + "_")
    ]
    if not allowed:
        raise ValueError(f"Unsupported crop: {crop_prefix}")
    temperature = float(metadata["temperature"])
    if not np.isfinite(temperature) or temperature <= 0:
        raise ValueError("Invalid calibration temperature")
    values = np.asarray(logits, dtype=np.float64)[allowed] / temperature
    exp = np.exp(values - values.max())
    probabilities = exp / exp.sum()
    local = int(probabilities.argmax())
    index = allowed[local]
    vector = np.asarray(embedding, dtype=np.float64)
    vector = vector / max(float(np.linalg.norm(vector)), 1e-12)
    centers = np.asarray(
        metadata["prototype_centers"][classes[index]], dtype=np.float64
    )
    centers = centers / np.maximum(
        np.linalg.norm(centers, axis=1, keepdims=True), 1e-12
    )
    distance = float(1 - np.max(centers @ vector))
    logit = float(np.asarray(plant_logit).reshape(-1)[0])
    plant_probability = float(1 / (1 + np.exp(-np.clip(logit, -80, 80))))
    return (index, float(probabilities[local]), distance, plant_probability)


def decide_v3(logits, embedding, plant_logit, classes, metadata, crop_prefix=None):
    index, confidence, distance, plant_probability = scores_v3(
        logits, embedding, plant_logit, classes, metadata, crop_prefix
    )
    name = classes[index]
    threshold = float(metadata["prototype_distance_thresholds"][name])
    confidence_threshold = float(metadata["abstain_threshold"])
    if name.endswith("_healthy"):
        confidence_threshold = max(
            confidence_threshold, float(metadata["healthy_threshold"])
        )
    if metadata.get("plant_gate_enabled") and plant_probability < float(
        metadata["plant_threshold"]
    ):
        reason = "out_of_distribution"
    elif confidence < confidence_threshold:
        reason = "low_confidence"
    elif distance > threshold:
        reason = "out_of_distribution"
    else:
        reason = None
    return dict(
        class_index=index,
        class_name=name,
        confidence=confidence,
        prototype_distance=distance,
        accepted=reason is None,
        rejection_reason=reason,
        distance_threshold=threshold,
    )
