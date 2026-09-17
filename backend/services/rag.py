from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


TOKEN_RE = re.compile(r"[a-zа-яё0-9]+", re.IGNORECASE)
DISEASE_STOPWORDS = {"tomato", "cucumber", "pepper", "eggplant", "strawberry", "leaf"}
DISEASE_LABELS = {
    "tomato_healthy": "томат без видимых признаков болезни",
    "tomato_bacterial_spot": "бактериальная пятнистость томата",
    "tomato_early_blight": "ранняя пятнистость томата",
    "tomato_late_blight": "фитофтороз томата",
    "tomato_leaf_mold": "листовая плесень томата",
    "tomato_septoria": "септориоз томата",
    "tomato_yellow_leaf_curl_virus": "вирус жёлтой курчавости листьев томата",
    "tomato_mosaic_virus": "вирусная мозаика томата",
    "tomato_powdery_mildew": "мучнистая роса томата",
    "tomato_leaf_miner": "повреждение томата листовым минёром",
    "tomato_whitefly": "повреждение томата белокрылкой",
    "cucumber_healthy": "огурец без видимых признаков болезни",
    "cucumber_downy_mildew": "ложная мучнистая роса огурца",
    "cucumber_powdery_mildew": "мучнистая роса огурца",
    "cucumber_anthracnose": "антракноз огурца",
    "cucumber_bacterial_wilt": "бактериальное увядание огурца",
    "cucumber_belly_rot": "гниль нижней стороны плода огурца",
    "cucumber_pythium_fruit_rot": "питиозная гниль плодов огурца",
    "cucumber_gummy_stem_blight": "аскохитоз огурца",
    "pepper_healthy": "перец без видимых признаков болезни",
    "pepper_bacterial_spot": "бактериальная пятнистость перца",
    "pepper_cercospora_leaf_spot": "церкоспороз перца",
    "pepper_leaf_curl": "курчавость листьев перца",
    "pepper_nutrient_deficiency": "возможный дефицит питания перца",
    "pepper_powdery_mildew": "мучнистая роса перца",
    "eggplant_healthy": "баклажан без видимых признаков болезни",
    "eggplant_insect_pest": "возможное повреждение баклажана вредителем",
    "eggplant_leaf_spot": "пятнистость листьев баклажана",
    "eggplant_mosaic_virus": "вирусная мозаика баклажана",
    "eggplant_small_leaf": "мелколистность баклажана",
    "eggplant_white_mold": "белая гниль баклажана",
    "eggplant_wilt": "увядание баклажана",
    "strawberry_healthy": "земляника без видимых признаков болезни",
    "strawberry_leaf_scorch": "ожог листьев земляники",
    "strawberry_calcium_deficiency": "возможный дефицит кальция у земляники",
    "strawberry_gray_mold": "серая гниль земляники",
    "strawberry_leaf_spot": "пятнистость листьев земляники",
    "strawberry_powdery_mildew_leaf": "мучнистая роса земляники",
    "strawberry_anthracnose_fruit_rot": "антракнозная гниль плодов земляники",
}


def _tokens(value: str) -> set[str]:
    return set(TOKEN_RE.findall(value.lower().replace("_", " ")))


def _float(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


@dataclass(frozen=True)
class RetrievedKnowledge:
    item: dict[str, Any]
    score: float

    def source(self) -> dict[str, Any]:
        return {
            "id": self.item["id"],
            "title": self.item["title"],
            "organization": self.item["organization"],
            "url": self.item["url"],
        }


class AgronomyKnowledgeBase:
    def __init__(self, path: str | Path):
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        self.version = str(payload["version"])
        self.items = list(payload["items"])
        if not self.items:
            raise ValueError("Agronomy knowledge base is empty")
        required = {"id", "title", "organization", "url", "crops", "diseases", "facts"}
        for item in self.items:
            missing = required.difference(item)
            if missing:
                raise ValueError(
                    f"Knowledge item {item.get('id')} misses {sorted(missing)}"
                )

    def search(
        self,
        crop: str,
        disease: str,
        stage: str,
        sensors: dict[str, Any],
        request_type: str,
        limit: int = 4,
    ) -> list[RetrievedKnowledge]:
        crop = crop.lower().strip() or "unknown"
        disease = disease.lower().strip()
        disease_tokens = _tokens(disease).difference(DISEASE_STOPWORDS)
        query = " ".join(
            [crop, disease, stage, request_type, " ".join(str(key) for key in sensors)]
        )
        query_tokens = _tokens(query)
        results: list[RetrievedKnowledge] = []

        for item in self.items:
            crops = set(item.get("crops", []))
            if crop not in crops and "all" not in crops:
                continue
            item_diseases = set(item.get("diseases", []))
            item_tags = set(item.get("tags", []))
            score = 8.0 if crop in crops else 2.0

            if item_diseases:
                if not disease or disease in {"unknown", "неизвестно"}:
                    continue
                if disease in item_diseases:
                    score += 18.0
                else:
                    item_disease_tokens = set().union(
                        *(_tokens(value) for value in item_diseases)
                    )
                    overlap = disease_tokens.intersection(item_disease_tokens)
                    if not overlap:
                        continue
                    score += 5.0 * len(overlap)
            elif request_type == "sensor_based" and "sensor_based" in item_tags:
                score += 6.0

            searchable = " ".join(
                [item["title"], " ".join(item.get("facts", [])), " ".join(item_tags)]
            )
            score += min(
                6.0, 0.45 * len(query_tokens.intersection(_tokens(searchable)))
            )
            results.append(RetrievedKnowledge(item=item, score=score))

        results.sort(key=lambda result: (-result.score, result.item["id"]))
        return results[: max(1, limit)]

    def crop_ranges(self, crop: str) -> dict[str, list[float]]:
        for item in self.items:
            ranges = item.get("crop_ranges", {}).get(crop)
            if ranges:
                return ranges
        return {}

    def sensor_line(
        self, crop: str, sensors: dict[str, Any], key: str, label: str
    ) -> str:
        value = _float(sensors.get(key))
        limits = self.crop_ranges(crop).get(key)
        if value is None or value == 0:
            enabled = sensors.get(f"{key}_enabled")
            if enabled is False:
                return f"Измерение {label} выключено; включите датчик и дождитесь ненулевого показания перед изменением раствора."
            return f"Для {label} не получено ненулевое показание; проверьте датчик перед изменением раствора."
        if not limits:
            return f"Показание {label} равно {value:g}; сравните его с технологической картой выбранной культуры."
        low, high = limits
        range_text = f"{low:g}–{high:g}"
        if low <= value <= high:
            return f"Показание {label} {value:g} находится в справочном диапазоне {range_text}; коррекция не требуется."
        direction = "ниже" if value < low else "выше"
        return (
            f"Показание {label} {value:g} {direction} справочного диапазона {range_text}; "
            "сначала повторите измерение откалиброванным датчиком, затем корректируйте раствор постепенно по технологической карте."
        )

    def environment_line(self, sensors: dict[str, Any]) -> str:
        labels = (
            ("air_temp", "воздух", "°C"),
            ("humidity", "влажность", "%"),
            ("solution_temp", "раствор", "°C"),
            ("light", "свет", "лк"),
            ("level", "уровень", "см"),
        )
        values = []
        for key, label, unit in labels:
            value = _float(sensors.get(key))
            if value is not None and value != 0:
                values.append(f"{label} {value:g} {unit}")
        if not values:
            return "Показания среды не получены; обновите диагностику и проверьте связь с контроллером."
        return "Учтены текущие показания: " + ", ".join(values) + "."

    def grounded_fallback(
        self,
        crop: str,
        plant: str,
        disease: str,
        sensors: dict[str, Any],
        results: list[RetrievedKnowledge],
        rejected: bool = False,
        sensors_included: bool = True,
        variation: int = 0,
    ) -> str:
        healthy = disease.endswith("_healthy")
        if rejected:
            first = "Сделайте новый резкий снимок листа при рассеянном свете; по отклонённому кадру лечение не назначается."
        elif healthy:
            first = (
                f"Для культуры «{plant}» модель не обнаружила известный класс болезни; "
                "не начинайте обработку только по этому результату."
            )
        elif disease in {"", "общая проверка здоровья"}:
            first = f"Осмотрите культуру «{plant}» и сравните состояние молодых и старых листьев, стеблей и корневой зоны."
        else:
            disease_label = DISEASE_LABELS.get(disease, "предполагаемое заболевание")
            first = f"Результат «{disease_label}» считайте предварительным и сопоставьте с симптомами на нескольких растениях."

        facts: list[tuple[str, int]] = []
        for source_index, result in enumerate(results, start=1):
            for fact in result.item.get("facts", []):
                if not any(existing == fact for existing, _ in facts):
                    facts.append((fact, source_index))
        default_evidence = "Не меняйте режим выращивания без повторной проверки показаний и состояния растений."
        evidence_one = facts[0][0] if facts else default_evidence
        evidence_two = (
            facts[1][0]
            if len(facts) > 1
            else "Повторно осмотрите те же растения через сутки и зафиксируйте распространение симптомов."
        )
        safety = (
            "Не применяйте средство защиты или дозировку без подтверждения диагноза "
            "и проверки этикетки для культуры и тепличных условий."
        )
        if not sensors_included:
            no_sensor_line = (
                "Показания датчиков не были переданы; рекомендации основаны только "
                "на выбранном контексте и справочных данных."
            )
            lines = [first, evidence_one, evidence_two, no_sensor_line, safety]
        elif rejected:
            lines = [
                first,
                self.sensor_line(crop, sensors, "ph", "pH"),
                self.sensor_line(crop, sensors, "ec", "EC"),
                default_evidence,
                safety,
            ]
        elif disease in {"", "общая проверка здоровья"} or healthy:
            lines = [
                first,
                self.sensor_line(crop, sensors, "ph", "pH"),
                self.sensor_line(crop, sensors, "ec", "EC"),
                evidence_one,
                evidence_two,
            ]
        else:
            sensor_summary = (
                self.sensor_line(crop, sensors, "ph", "pH")
                + " "
                + self.sensor_line(crop, sensors, "ec", "EC")
                + " "
                + self.environment_line(sensors)
            )
            lines = [first, evidence_one, evidence_two, sensor_summary, safety]
        tails = lines[1:]
        orders = (
            tuple(range(len(tails))),
            tuple([1, 0, *range(2, len(tails))]) if len(tails) >= 2 else (0,),
            (
                tuple([2, 0, 1, *range(3, len(tails))])
                if len(tails) >= 3
                else tuple(range(len(tails)))
            ),
            (
                tuple([0, 2, 1, *range(3, len(tails))])
                if len(tails) >= 3
                else tuple(range(len(tails)))
            ),
        )
        tails = [tails[index] for index in orders[variation % len(orders)]]
        lines = [lines[0], *tails]
        return "\n".join(f"{index}. {line}" for index, line in enumerate(lines, 1))
