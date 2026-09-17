import json
from types import SimpleNamespace
import pytest
from backend.services.controller import ControllerTelemetry


def announcement(uid, state, command=None, device="bed1"):
    return json.dumps(
        dict(
            unique_id="bed1_" + uid,
            device={"identifiers": [device]},
            state_topic=state,
            command_topic=command,
            payload_on="ON",
            payload_off="OFF",
        )
    )


def test_discovery_filters_device_and_real_readings_expire():
    c = ControllerTelemetry("bed1", 10)
    assert (
        c.discovery(
            "homeassistant/sensor/other/config",
            announcement("agr_t", "other", device="other"),
        )
        == []
    )
    assert c.discovery(
        "homeassistant/sensor/bed1/config", announcement("agr_t", "bed1/temp")
    ) == ["bed1/temp"]
    assert c.receive("bed1/temp", "23.5", now=100)
    assert c.snapshot(now=105)["air_temp"] == "23.5"
    assert not c.receive("bed1/temp", "nan", now=106)
    assert c.snapshot(now=111) == {}


def test_controls_are_confirmed_only_by_state_messages():
    c = ControllerTelemetry("bed1")
    c.discovery(
        "homeassistant/switch/bed1/config", announcement("agr_r3", "r3/state", "r3/set")
    )
    assert c.command("relay3", True) == ("r3/set", "ON")
    assert c.snapshot() == {}
    c.receive("r3/state", "ON")
    assert c.snapshot()["relay3"] is True
    with pytest.raises(RuntimeError):
        c.command("relay2", True)


def test_cloudy_cyrillic_identifier_from_firmware():
    c = ControllerTelemetry("bed1")
    c.discovery(
        "homeassistant/switch/bed1/config",
        announcement("agr_СlearСloudy", "mode/state", "mode/set"),
    )
    assert c.command("mode", "пасмурно") == ("mode/set", "ON")
    c.receive("mode/state", "OFF")
    assert c.snapshot()["mode"] == "ясно"


@pytest.mark.parametrize(
    "uid,key",
    [
        ("relay1", "relay1"),
        ("relay2", "relay2"),
        ("relay3", "relay3"),
        ("ClearCloudy", "mode"),
        ("IFEC", "ec_onoff"),
        ("IFPH", "ph_onoff"),
    ],
)
def test_original_firmware_constructor_ids(uid, key):
    c = ControllerTelemetry("bed1")
    payload = json.loads(announcement(uid, "state", "set"))
    payload["unique_id"] = uid
    assert c.discovery("homeassistant/switch/bed1/config", json.dumps(payload)) == [
        "state"
    ]
    assert key in c.entities


def test_relay3_topic_repairs_duplicate_firmware_identifier():
    c = ControllerTelemetry("bed1")
    payload = announcement("agr_r2", "bed1/relay3/state", "bed1/relay3/set")
    assert c.discovery("homeassistant/switch/bed1/relay3/config", payload) == [
        "bed1/relay3/state"
    ]
    assert c.command("relay3", True) == ("bed1/relay3/set", "ON")


def test_real_api_never_simulates_and_rejects_string_boolean(monkeypatch):
    import backend.app as a

    monkeypatch.setattr(a, "MQTT_EMULATION", False)
    monkeypatch.setattr(a, "telemetry", ControllerTelemetry("bed1"))
    monkeypatch.setattr(
        a, "simulate_sensors", lambda: pytest.fail("real mode simulated")
    )
    assert a.current_sensor_snapshot()["air_temp"] == "нет данных"
    assert a.controller_state()["relay1"] is None
    with pytest.raises(ValueError):
        a.apply_control_updates({"relay1": "false"})


def test_publish_tuple_failure_not_reported_as_success(monkeypatch):
    import backend.app as a

    monkeypatch.setattr(a, "MQTT_EMULATION", False)
    monkeypatch.setattr(a, "MQTT_ENABLED", True)
    monkeypatch.setattr(a, "mqtt_connected", True)
    monkeypatch.setattr(a.mqtt, "publish", lambda *args, **kwargs: (4, 1))
    with pytest.raises(RuntimeError):
        a.publish_mqtt("bed1/relay", "ON")


def test_api_routes_real_mqtt_message_to_diagnostics(monkeypatch):
    import backend.app as a

    monkeypatch.setattr(a, "MQTT_EMULATION", False)
    monkeypatch.setattr(a, "telemetry", ControllerTelemetry("bed1"))
    subscribed = []
    monkeypatch.setattr(
        a.mqtt, "subscribe", lambda topic, **kwargs: subscribed.append(topic)
    )
    a.handle_message(
        None,
        None,
        SimpleNamespace(
            topic="homeassistant/sensor/bed1/temp/config",
            payload=announcement("agr_t", "bed1/temp").encode(),
        ),
    )
    a.handle_message(None, None, SimpleNamespace(topic="bed1/temp", payload=b"24.7"))
    assert subscribed == ["bed1/temp"]
    assert a.current_sensor_snapshot()["air_temp"] == "24.7"


def test_upload_invalid_file_is_client_error():
    import io
    import backend.app as a

    a.app.config["AUTH_ENABLED"] = False
    response = a.app.test_client().post(
        "/upload_photo", data={"file": (io.BytesIO(b"not an image"), "bad.png")}
    )
    assert response.status_code == 400


def test_malformed_discovery_is_ignored():
    c = ControllerTelemetry("bed1")
    assert c.discovery("homeassistant/x/config", "[]") == []
    assert c.discovery("homeassistant/x/config", '{"dev":null}') == []


def test_malformed_queue_items_do_not_crash_api():
    import backend.app as a

    a.app.config["AUTH_ENABLED"] = False
    client = a.app.test_client()
    for endpoint, field in [("commands", "commands"), ("sensor-data", "readings")]:
        assert client.post("/api/sync/" + endpoint, json=[1]).status_code == 400
        response = client.post("/api/sync/" + endpoint, json={field: [None]})
        assert response.status_code == 200
