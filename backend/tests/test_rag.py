import json
from pathlib import Path

from backend.services.rag import AgronomyKnowledgeBase


KNOWLEDGE_PATH = Path(__file__).parents[1] / "knowledge" / "agronomy_knowledge.json"


def knowledge_base():
    return AgronomyKnowledgeBase(KNOWLEDGE_PATH)


def test_exact_disease_and_crop_are_ranked_first():
    results = knowledge_base().search(
        crop="tomato",
        disease="tomato_leaf_mold",
        stage="плодоношение",
        sensors={"humidity": "88", "ph": "6.2", "ec": "2.5"},
        request_type="photo_analysis",
    )
    assert results[0].item["id"] == "umn-tomato-leaf-mold"
    assert all(
        "tomato" in result.item["crops"] or "all" in result.item["crops"]
        for result in results
    )


def test_unrelated_disease_document_is_not_retrieved():
    results = knowledge_base().search(
        crop="strawberry",
        disease="strawberry_powdery_mildew_leaf",
        stage="цветение",
        sensors={},
        request_type="photo_analysis",
    )
    ids = {result.item["id"] for result in results}
    assert "ucipm-strawberry-powdery-mildew" in ids
    assert "umn-tomato-leaf-mold" not in ids


def test_sensor_fallback_uses_crop_specific_ranges():
    kb = knowledge_base()
    results = kb.search(
        crop="tomato",
        disease="общая проверка здоровья",
        stage="рост",
        sensors={"ph": "7.1", "ec": "1.2"},
        request_type="sensor_based",
    )
    text = kb.grounded_fallback(
        crop="tomato",
        plant="томат",
        disease="общая проверка здоровья",
        sensors={"ph": "7.1", "ec": "1.2"},
        results=results,
    )
    assert "pH 7.1 выше справочного диапазона 6–6.5" in text
    assert "EC 1.2 ниже справочного диапазона 2–4" in text
    assert len([line for line in text.splitlines() if line.strip()]) == 5
    assert "[1]" not in text


def test_sources_do_not_expose_untrusted_document_text():
    result = knowledge_base().search(
        crop="cucumber",
        disease="",
        stage="рост",
        sensors={},
        request_type="sensor_based",
        limit=1,
    )[0]
    assert set(result.source()) == {"id", "title", "organization", "url"}


def test_every_model_class_has_disease_specific_knowledge():
    classes_path = Path(__file__).parents[1] / "ml" / "artifacts" / "classes.txt"
    classes = {
        line.strip()
        for line in classes_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    covered = {
        disease
        for item in knowledge_base().items
        for disease in item.get("diseases", [])
    }
    assert classes <= covered


def test_knowledge_file_has_consistent_structure():
    payload = json.loads(KNOWLEDGE_PATH.read_text(encoding="utf-8"))
    items = payload["items"]
    ids = [item["id"] for item in items]

    assert payload["reviewed_at"] == "2026-09-13"
    assert len(ids) == len(set(ids))
    assert all(item["url"].startswith("https://") for item in items)
    assert all(item["facts"] and all(fact.strip() for fact in item["facts"]) for item in items)

    for item in items:
        crops = set(item["crops"])
        for disease in item["diseases"]:
            disease_crop = disease.split("_", 1)[0]
            assert "all" in crops or disease_crop in crops


def test_corrected_diseases_use_matching_sources():
    items = {item["id"]: item for item in knowledge_base().items}

    assert "pepper_cercospora_leaf_spot" not in items["umn-bacterial-spot"]["diseases"]
    assert "pepper_cercospora_leaf_spot" in items["ncsu-pepper-cercospora"]["diseases"]
    assert set(items["umn-tomato-viruses"]["diseases"]) == {
        "tomato_mosaic_virus",
        "tomato_yellow_leaf_curl_virus",
    }
    assert set(items["wsu-pepper-eggplant-mosaic"]["diseases"]) == {
        "pepper_leaf_curl",
        "eggplant_mosaic_virus",
    }
    assert items["kstate-eggplant-little-leaf"]["diseases"] == ["eggplant_small_leaf"]


def test_disease_fallback_uses_two_grounded_facts():
    kb = knowledge_base()
    results = kb.search(
        crop="strawberry",
        disease="strawberry_leaf_spot",
        stage="плодоношение",
        sensors={"ph": "6.0", "ec": "2.0"},
        request_type="photo_analysis",
    )
    text = kb.grounded_fallback(
        crop="strawberry",
        plant="земляника",
        disease="strawberry_leaf_spot",
        sensors={"ph": "6.0", "ec": "2.0"},
        results=results,
    )
    assert "светлый центр" in text
    assert "Несколько возбудителей" in text
    assert len([line for line in text.splitlines() if line.strip()]) == 5
    assert "[1]" not in text


def test_repeated_fallback_varies_order_without_losing_sensor_facts():
    kb = knowledge_base()
    results = kb.search(
        crop="tomato",
        disease="общая проверка здоровья",
        stage="рост",
        sensors={"ph": "7.1", "ec": "1.2"},
        request_type="sensor_based",
    )
    args = {
        "crop": "tomato",
        "plant": "томат",
        "disease": "общая проверка здоровья",
        "sensors": {"ph": "7.1", "ec": "1.2"},
        "results": results,
    }
    first = kb.grounded_fallback(**args, variation=0)
    second = kb.grounded_fallback(**args, variation=1)

    assert first != second
    assert "pH 7.1" in first and "pH 7.1" in second
    assert "EC 1.2" in first and "EC 1.2" in second
