import os
from types import SimpleNamespace

import pytest

os.environ["AUTH_ENABLED"] = "false"
os.environ["DATABASE_URI"] = "sqlite:///:memory:"
os.environ["MQTT_EMULATION"] = "true"

import backend.app as app_module


def client():
    app_module.app.config.update(TESTING=True)
    return app_module.app.test_client()


def test_diagnostics_contains_required_sensor_contract():
    response = client().get("/diagnostics")
    assert response.status_code == 200
    assert {
        "air_temp",
        "humidity",
        "solution_temp",
        "light",
        "level",
        "ec",
        "ph",
        "mode",
        "relay1",
        "relay2",
        "relay3",
        "ec_onoff",
        "ph_onoff",
    } <= set(response.get_json())


def test_emulated_sensors_stay_in_plausible_greenhouse_ranges():
    sensors = app_module.simulate_sensors()
    assert 19 <= float(sensors["air_temp"]) <= 31
    assert 50 <= float(sensors["humidity"]) <= 82
    assert 18 <= float(sensors["solution_temp"]) <= 25
    assert 35 <= float(sensors["level"]) <= 90
    assert 0.6 <= float(sensors["ec"]) <= 4.0
    assert 4.5 <= float(sensors["ph"]) <= 8.0


def test_recommendations_fall_back_when_model_switches_language(monkeypatch):
    calls = []

    def fake_chat(**kwargs):
        calls.append(kwargs)
        return {
            "message": {
                "content": (
                    "1. Inspect both sides of the leaves.\n"
                    "2. Проверьте температуру и влажность.\n"
                    "3. Перепроверьте текущие показания датчиков.\n"
                    "4. Не меняйте раствор без повторного измерения.\n"
                    "5. Повторите осмотр растения через сутки."
                )
            }
        }

    monkeypatch.setattr(app_module.ollama_client, "chat", fake_chat)
    text, source = app_module.generate_russian_recommendations(
        "Дай рекомендации.", "томат", "tomato_leaf_mold", {"ph": "6.1", "ec": "1.8"}
    )

    assert len(calls) == 1
    assert source == "fallback_language_guard"
    assert app_module.predominantly_russian(text)
    assert calls[0]["options"]["temperature"] == 0.2
    assert calls[0]["options"]["seed"] == 4200
    assert calls[0]["options"]["num_ctx"] == 2048
    assert calls[0]["options"]["num_predict"] == app_module.OLLAMA_NUM_PREDICT
    assert calls[0]["options"]["num_gpu"] == app_module.OLLAMA_NUM_GPU
    assert calls[0]["think"] is False
    assert calls[0]["format"]["properties"]["items"]["minItems"] == 4


def test_cuda_failure_retries_ollama_on_cpu(monkeypatch):
    calls = []

    def fake_chat(**kwargs):
        calls.append(kwargs)
        if len(calls) == 1:
            raise RuntimeError("llama-server terminated: 0xc0000409: CUDA error")
        return {
            "message": {
                "content": (
                    "Осмотрите листья с обеих сторон.\n"
                    "Проверьте текущие значения pH 6.1 и EC 1.8.\n"
                    "Сравните состояние молодых и старых листьев.\n"
                    "Не меняйте раствор без повторного измерения.\n"
                    "Повторите осмотр растения через сутки."
                )
            }
        }

    monkeypatch.setattr(app_module, "OLLAMA_NUM_GPU", 12)
    monkeypatch.setattr(app_module.ollama_client, "chat", fake_chat)
    text, source = app_module.generate_russian_recommendations(
        "Дай рекомендации по показаниям pH 6.1 и EC 1.8.",
        "томат",
        "tomato_leaf_mold",
        {"ph": "6.1", "ec": "1.8"},
    )

    assert len(calls) == 2
    assert calls[0]["options"]["num_gpu"] == 12
    assert calls[1]["options"]["num_gpu"] == 0
    assert source == "ollama"
    assert "Осмотрите листья" in text


def test_mixed_script_recommendation_is_rejected():
    text = (
        "\u68c0\u67e5\u53f6\u7247 1. Проверьте условия внешней среды и температуру.\n"
        "2. Осмотрите листья растения с обеих сторон.\n"
        "3. Перепроверьте текущие показания датчиков.\n"
        "4. Не меняйте раствор без повторного измерения.\n"
        "5. Повторите осмотр растения через сутки."
    )
    assert not app_module.predominantly_russian(text)


def test_two_line_answer_is_rejected_by_required_format():
    text = (
        "Состояние растений выглядит стабильным. Осмотрите нижнюю сторону листьев и "
        "уберите растительные остатки.\n"
        "Продолжайте наблюдение за влажностью и раствором. Повторите осмотр завтра."
    )
    prompt = "Показания датчиков: pH 6.1, EC 1.8."

    assert not app_module.grounded_response_valid(
        text,
        source_count=1,
        sensors={"ph": "6.1", "ec": "1.8"},
        allowed_numeric_context=prompt,
    )


def test_russian_decimal_separator_is_allowed_when_value_came_from_prompt():
    text = (
        "Проверьте раствор: текущее значение pH 6,1 находится в переданном контексте.\n"
        "Осмотрите листья с обеих сторон.\n"
        "Сравните состояние молодых и старых листьев.\n"
        "Продолжайте наблюдение за растениями."
    )

    assert app_module.grounded_response_valid(
        text,
        source_count=1,
        sensors={"ph": "6.1"},
        allowed_numeric_context="Текущее значение pH 6.1.",
    )


def test_truncated_recommendation_is_rejected():
    text = (
        "1. Осмотрите листья растения с обеих сторон.\n"
        "2. Проверьте текущие значения pH и EC.\n"
        "3. Сравните состояние молодых и старых листьев.\n"
        "4. Повторите измерение датчиков перед изменением"
    )
    assert not app_module.grounded_response_valid(text, source_count=1)


def test_completed_numbered_answer_is_normalized(monkeypatch):
    generated = (
        "1) Осмотрите листья с обеих сторон "
        "2) Проверьте значения pH 6.1 и EC 1.8 "
        "3) Сравните молодые и старые листья "
        "4) Продолжайте наблюдение за растением"
    )
    monkeypatch.setattr(
        app_module.ollama_client,
        "chat",
        lambda **kwargs: {
            "message": {"content": generated},
            "done": True,
            "done_reason": "stop",
        },
    )

    text, source = app_module.generate_russian_recommendations(
        "Показания датчиков: pH 6.1, EC 1.8.",
        "томат",
        "общая проверка здоровья",
        {"ph": "6.1", "ec": "1.8"},
        source_count=1,
    )

    assert source == "ollama_rag"
    assert len(text.splitlines()) == 4
    assert all(line.endswith(".") for line in text.splitlines())


def test_structured_ollama_answer_is_normalized(monkeypatch):
    generated = {
        "items": [
            "Осмотрите листья с обеих сторон",
            "Проверьте значения pH 6.1 и EC 1.8",
            "Сравните молодые и старые листья",
            "Продолжайте наблюдение за растением",
        ]
    }
    monkeypatch.setattr(
        app_module.ollama_client,
        "chat",
        lambda **kwargs: {
            "message": {"content": __import__("json").dumps(generated)},
            "done": True,
            "done_reason": "stop",
        },
    )

    text, source = app_module.generate_russian_recommendations(
        "Показания датчиков: pH 6.1, EC 1.8.",
        "томат",
        "общая проверка здоровья",
        {"ph": "6.1", "ec": "1.8"},
        source_count=1,
    )

    assert source == "ollama_rag"
    assert text.splitlines()[0] == "1. Осмотрите листья с обеих сторон."
    assert len(text.splitlines()) == 4


def test_answer_cut_off_by_token_limit_uses_fallback(monkeypatch):
    generated = (
        "1. Осмотрите листья с обеих сторон.\n"
        "2. Проверьте значения pH 6.1 и EC 1.8.\n"
        "3. Сравните молодые и старые листья.\n"
        "4. Продолжайте наблюдение за"
    )
    monkeypatch.setattr(
        app_module.ollama_client,
        "chat",
        lambda **kwargs: {
            "message": {"content": generated},
            "done": True,
            "done_reason": "length",
        },
    )

    text, source = app_module.generate_russian_recommendations(
        "Показания датчиков: pH 6.1, EC 1.8.",
        "томат",
        "общая проверка здоровья",
        {"ph": "6.1", "ec": "1.8"},
        fallback_text="Локальная рекомендация.",
        source_count=1,
    )

    assert source == "rag_rules"
    assert text == "Локальная рекомендация."


def test_llm_answer_with_new_numeric_target_is_rejected(monkeypatch):
    generated = (
        "1. Осмотрите листья с обеих сторон. [1]\n"
        "2. Проверьте pH 6.1 и EC 1.8. [1]\n"
        "3. Увеличьте освещённость до 99999 люкс. [1]\n"
        "4. Сравните состояние молодых и старых листьев. [1]\n"
        "5. Повторите осмотр после контроля параметров. [1]"
    )
    monkeypatch.setattr(
        app_module.ollama_client,
        "chat",
        lambda **kwargs: {"message": {"content": generated}},
    )
    fallback = (
        "1. Осмотрите растение.\n2. Проверьте pH 6.1.\n"
        "3. Проверьте EC 1.8.\n4. Сверьте условия. [1]\n5. Повторите осмотр."
    )
    text, source = app_module.generate_russian_recommendations(
        "Разрешены только pH 6.1 и EC 1.8. [1]",
        "томат",
        "общая проверка здоровья",
        {"ph": "6.1", "ec": "1.8"},
        fallback_text=fallback,
        source_count=1,
    )

    assert source == "rag_rules"
    assert text == fallback
    assert "99999" not in text


def test_llm_visual_confirmation_is_rewritten_as_preliminary():
    text = (
        "1. Результат «листовая плесень томата» подтверждён фотографией.\n"
        "2. Снизьте влажность воздуха. [1]\n"
        "3. Улучшите вентиляцию. [1]\n"
        "4. Проверьте pH 6.2 и EC 2.1.\n"
        "5. Не применяйте препараты без проверки этикетки."
    )
    sanitized = app_module.sanitize_generated_recommendation(text)
    assert "подтверждён фотографией" not in sanitized
    assert "считайте предварительным" in sanitized
    assert "[1]" not in sanitized


def test_inline_source_labels_are_removed_from_recommendation():
    text = (
        "Проверьте влажность и осмотрите нижнюю сторону листьев (Источник 2).\n"
        "Повторите наблюдение на следующий день."
    )

    sanitized = app_module.sanitize_generated_recommendation(text)

    assert "Источник" not in sanitized
    assert "Проверьте влажность" in sanitized


def test_generic_source_mention_is_removed_without_discarding_sentence():
    text = (
        "Показатели соответствуют рекомендациям, указанным в источниках.\n"
        "Продолжайте наблюдение за растениями."
    )

    sanitized = app_module.sanitize_generated_recommendation(text)

    assert "источник" not in sanitized.lower()
    assert "Показатели соответствуют рекомендациям." in sanitized


def test_unknown_photo_uses_safe_fallback_without_ollama(monkeypatch):
    def must_not_run(**kwargs):
        raise AssertionError("Ollama must not diagnose a rejected image")

    monkeypatch.setattr(app_module.ollama_client, "chat", must_not_run)
    response = client().post(
        "/recommendations",
        json={
            "type": "photo_analysis",
            "plant": "томат",
            "disease_name": "unknown",
            "confidence_score": 0.0,
            "sensors": {"ph": "6.1", "ec": "1.8"},
        },
    )
    assert response.status_code == 200
    body = response.get_json()
    assert body["recommendation_source"] == "rag_rules_invalid_photo"
    assert "по отклонённому кадру лечение не назначается" in body["recommendations"]
    assert body["retrieval"]["grounded"] is True
    assert body["sources"]


def test_photo_recommendation_returns_matching_rag_sources(monkeypatch):
    monkeypatch.setattr(
        app_module.ollama_client,
        "chat",
        lambda **kwargs: {"message": {"content": "An unsupported English answer."}},
    )
    response = client().post(
        "/recommendations",
        json={
            "type": "photo_analysis",
            "plant": "томат",
            "stage": "плодоношение",
            "disease_name": "tomato_leaf_mold",
            "confidence_score": 0.91,
            "sensors": {"humidity": "88", "ph": "6.2", "ec": "2.5"},
            "response_mode": "hybrid",
        },
    )
    assert response.status_code == 200
    body = response.get_json()
    assert body["recommendation_source"] == "rag_rules"
    assert body["sources"][0]["id"] == "umn-tomato-leaf-mold"
    assert "листовая плесень томата" in body["recommendations"]
    assert "tomato_leaf_mold" not in body["recommendations"]
    assert "высокой относительной влажностью" in body["recommendations"]


def test_photo_recommendation_uses_hybrid_by_default(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "generate_russian_recommendations",
        lambda *args, **kwargs: ("Проверьте растение и продолжайте наблюдение.", "ollama_rag"),
    )
    response = client().post(
        "/recommendations",
        json={
            "type": "photo_analysis",
            "plant": "томат",
            "disease_name": "tomato_leaf_mold",
            "confidence_score": 0.91,
            "sensors": {"humidity": "88", "ph": "6.2", "ec": "2.5"},
        },
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["recommendation_source"] == "ollama_rag"
    assert body["generation"]["mode"] == "hybrid"
    assert body["generation"]["llm_attempted"] is True
    assert body["generation"]["llm_used"] is True


def test_legacy_response_mode_is_ignored_and_hybrid_is_used(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "generate_russian_recommendations",
        lambda *args, **kwargs: ("Проверьте растение и продолжайте наблюдение.", "ollama_rag"),
    )
    response = client().post(
        "/recommendations",
        json={
            "type": "photo_analysis",
            "disease_name": "tomato_leaf_mold",
            "response_mode": "fast",
        },
    )

    assert response.status_code == 200
    body = response.get_json()
    assert body["generation"]["mode"] == "hybrid"
    assert body["generation"]["llm_attempted"] is True


def test_health_reports_rag_corpus():
    body = client().get("/api/health").get_json()
    assert body["services"]["rag"] == "available"
    assert body["rag"]["documents"] >= 10
    assert body["rag"]["knowledge_version"]


def test_control_normalizes_modes_and_returns_applied_state(monkeypatch):
    published = []
    monkeypatch.setattr(
        app_module,
        "publish_mqtt",
        lambda topic, payload: published.append((topic, payload)),
    )
    response = client().post(
        "/control",
        json={"mode": "cloudy", "relay1": True, "ec_onoff": False},
    )
    assert response.status_code == 200
    body = response.get_json()
    assert body["applied"]["mode"] == "пасмурно"
    assert (app_module.MQTT_TOPICS["relay1"], "on") in published
    assert (app_module.MQTT_TOPICS["ec_onoff"], "off") in published

    diagnostics = client().get("/diagnostics").get_json()
    assert diagnostics["mode"] == "пасмурно"
    assert diagnostics["relay1"] is True
    assert diagnostics["ec_onoff"] is False


def test_photo_recommendation_includes_supplied_sensor_snapshot(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "generate_russian_recommendations",
        lambda *args, **kwargs: (kwargs["fallback_text"], "rag_rules"),
    )
    supplied = {
        "air_temp": "24.5",
        "humidity": "81",
        "solution_temp": "21.0",
        "light": "14500",
        "level": "62",
        "ph": "6.2",
        "ec": "2.1",
    }
    response = client().post(
        "/recommendations",
        json={
            "type": "photo_analysis",
            "plant": "томат",
            "disease_name": "tomato_leaf_mold",
            "confidence_score": 0.91,
            "sensor_data": supplied,
        },
    )
    body = response.get_json()
    assert response.status_code == 200
    assert body["recommendation_source"] == "rag_rules"
    assert body["generation"]["mode"] == "hybrid"
    assert body["generation"]["llm_attempted"] is True
    assert body["sensor_snapshot"]["air_temp"] == "24.5"
    assert body["sensor_snapshot"]["humidity"] == "81"
    assert "воздух 24.5 °C" in body["recommendations"]
    assert "влажность 81 %" in body["recommendations"]


def test_recommendation_can_explicitly_exclude_sensor_payload(monkeypatch):
    monkeypatch.setattr(
        app_module,
        "generate_russian_recommendations",
        lambda *args, **kwargs: (kwargs["fallback_text"], "rag_rules"),
    )
    response = client().post(
        "/recommendations",
        json={
            "type": "sensor_based",
            "plant": "томат",
            "stage": "рост",
            "include_sensors": False,
            "sensor_data": {"ph": "4.1", "ec": "4.9"},
        },
    )
    body = response.get_json()
    assert response.status_code == 200
    assert body["sensor_snapshot"] == {}
    assert "Показания датчиков не были переданы" in body["recommendations"]
    assert "4.1" not in body["recommendations"]
    assert "4.9" not in body["recommendations"]


def test_control_reports_mqtt_failure_for_android_offline_queue(monkeypatch):
    def fail_publish(topic, payload):
        raise RuntimeError("broker unavailable")

    monkeypatch.setattr(app_module, "publish_mqtt", fail_publish)
    response = client().post("/control", json={"relay2": True})
    assert response.status_code == 503
    assert response.get_json()["error"] == "mqtt_unavailable"


def test_sync_endpoint_executes_all_offline_command_types(monkeypatch):
    applied = []
    monkeypatch.setattr(
        app_module,
        "apply_control_updates",
        lambda updates: applied.append(updates) or updates,
    )
    response = client().post(
        "/api/sync/commands",
        json={
            "commands": [
                {"local_id": 1, "command_type": "SET_MODE", "parameter": "ясно"},
                {
                    "local_id": 2,
                    "command_type": "RELAY_TOGGLE",
                    "relay_id": 3,
                    "target_state": True,
                },
                {
                    "local_id": 3,
                    "command_type": "SET_EC_ENABLED",
                    "target_state": False,
                },
                {"local_id": 4, "command_type": "SET_PH_ENABLED", "target_state": True},
            ]
        },
    )
    assert response.status_code == 200
    assert all(item["status"] == "success" for item in response.get_json()["results"])
    assert applied == [
        {"mode": "ясно"},
        {"relay3": True},
        {"ec_onoff": False},
        {"ph_onoff": True},
    ]


def test_plant_name_is_normalized_to_model_prefix():
    assert app_module.plant_class_prefix("Томат") == "tomato"
    assert app_module.plant_class_prefix("огурец") == "cucumber"
    assert app_module.plant_class_prefix("Сладкий перец") == "pepper"
    assert app_module.plant_class_prefix("баклажан") == "eggplant"
    assert app_module.plant_class_prefix("клубника") == "strawberry"


def test_prediction_passes_selected_crop_to_classifier(monkeypatch):
    class FakeModel:
        def predict(self, image_path, crop_prefix):
            assert crop_prefix == "tomato"
            return SimpleNamespace(
                class_name="tomato_healthy",
                confidence=0.75,
                class_index=1,
                accepted=True,
            )

    monkeypatch.setattr(app_module, "onnx_model", FakeModel())
    disease, confidence, class_index = app_module.predict_image("unused.jpg", "томат")
    assert disease == "tomato_healthy"
    assert confidence == pytest.approx(0.75)
    assert class_index == 1


def test_registration_returns_session_and_protected_profile():
    app_module.app.config["AUTH_ENABLED"] = True
    api = client()
    response = api.post(
        "/api/auth/register",
        json={
            "username": "course_user",
            "email": "Course.User@example.com",
            "password": "Strong!9",
            "confirm_password": "Strong!9",
        },
    )
    assert response.status_code == 201
    body = response.get_json()
    assert body["access_token"]
    assert body["refresh_token"]
    assert body["user"]["username"] == "course_user"
    assert body["user"]["email"] == "course.user@example.com"

    profile = api.get(
        "/api/auth/me", headers={"Authorization": f"Bearer {body['access_token']}"}
    )
    assert profile.status_code == 200
    assert profile.get_json()["username"] == "course_user"
    app_module.app.config["AUTH_ENABLED"] = False


def test_registration_rejects_duplicate_and_weak_password():
    weak = client().post(
        "/api/auth/register",
        json={
            "username": "weak_user",
            "email": "weak@example.com",
            "password": "Password9",
            "confirm_password": "Password9",
        },
    )
    assert weak.status_code == 400
    assert weak.get_json()["error"] == "weak_password"

    duplicate = client().post(
        "/api/auth/register",
        json={
            "username": "course_user",
            "email": "another@example.com",
            "password": "Strong!9",
            "confirm_password": "Strong!9",
        },
    )
    assert duplicate.status_code == 409
    assert duplicate.get_json()["error"] == "username_exists"


def test_email_login_and_password_change_flow():
    app_module.app.config["AUTH_ENABLED"] = True
    api = client()
    registered = api.post(
        "/api/auth/register",
        json={
            "username": "password_user",
            "email": "password.user@example.com",
            "password": "Initial!9",
            "confirm_password": "Initial!9",
        },
    )
    assert registered.status_code == 201
    access_token = registered.get_json()["access_token"]

    changed = api.post(
        "/api/auth/change-password",
        headers={"Authorization": f"Bearer {access_token}"},
        json={
            "current_password": "Initial!9",
            "new_password": "Updated!8",
            "confirm_password": "Updated!8",
        },
    )
    assert changed.status_code == 200
    assert changed.get_json()["status"] == "ok"

    old_login = api.post(
        "/api/auth/login",
        json={"username": "password.user@example.com", "password": "Initial!9"},
    )
    assert old_login.status_code == 401

    new_login = api.post(
        "/api/auth/login",
        json={"username": "PASSWORD.USER@EXAMPLE.COM", "password": "Updated!8"},
    )
    assert new_login.status_code == 200
    assert new_login.get_json()["user"]["username"] == "password_user"
    app_module.app.config["AUTH_ENABLED"] = False
