import json
import math
import threading
import time

SENSORS = {
    "agr_t": "air_temp",
    "agr_h": "humidity",
    "agr_tds": "solution_temp",
    "agr_l": "light",
    "agr_lv": "level",
    "agr_ec": "ec",
    "agr_ph": "ph",
}
CONTROLS = {
    "agr_r1": "relay1",
    "agr_r2": "relay2",
    "agr_r3": "relay3",
    "agr_СlearСloudy": "mode",
    "agr_ClearCloudy": "mode",
    "agr_swec": "ec_onoff",
    "agr_swph": "ph_onoff",
}

CONTROLS.update(
    {
        "relay1": "relay1",
        "relay2": "relay2",
        "relay3": "relay3",
        "ClearCloudy": "mode",
        "IFEC": "ec_onoff",
        "IFPH": "ph_onoff",
    }
)

CONTROL_TOPIC_IDS = {
    "relay1": "relay1",
    "relay2": "relay2",
    "relay3": "relay3",
    "clearcloudy": "mode",
    "ifec": "ec_onoff",
    "ifph": "ph_onoff",
}


def control_key_from_topics(*topics):
    for topic in topics:
        if not topic:
            continue
        parts = {part.casefold() for part in str(topic).split("/") if part}
        for object_id, key in CONTROL_TOPIC_IDS.items():
            if object_id in parts:
                return key
    return None


class ControllerTelemetry:
    def __init__(self, device_id="", stale_seconds=120):
        self.device_id = device_id
        self.stale_seconds = stale_seconds
        self.lock = threading.RLock()
        self.entities = {}
        self.values = {}
        self.updated = {}

    def discovery(self, topic, payload):
        if not self.device_id or not payload:
            return []
        config = json.loads(payload)
        if not isinstance(config, dict):
            return []
        device = config.get("device", config.get("dev", {}))
        if not isinstance(device, dict):
            return []
        ids = device.get("identifiers", device.get("ids", []))
        if isinstance(ids, str):
            ids = [ids]
        if self.device_id not in ids:
            return []
        uid = str(config.get("unique_id", config.get("uniq_id", "")))
        key = next(
            (
                value
                for suffix, value in {**SENSORS, **CONTROLS}.items()
                if uid == suffix
                or uid.endswith("_" + suffix)
                or uid.endswith("/" + suffix)
            ),
            None,
        )
        if key is None:
            return []
        base = config.get("~", "")

        def expand(value):
            return value.replace("~", base) if value else None

        state = expand(config.get("state_topic", config.get("stat_t")))
        command = expand(config.get("command_topic", config.get("cmd_t")))
        key = control_key_from_topics(topic, state, command) or key
        if not state or "+" in state or "#" in state:
            return []
        if command and ("+" in command or "#" in command):
            return []
        entity = dict(
            state=state,
            command=command,
            on=str(config.get("payload_on", config.get("pl_on", "ON"))),
            off=str(config.get("payload_off", config.get("pl_off", "OFF"))),
        )
        with self.lock:
            self.entities[key] = entity
        return [state]

    def receive(self, topic, payload, now=None):
        now = time.monotonic() if now is None else now
        with self.lock:
            for key, entity in self.entities.items():
                if entity["state"] != topic:
                    continue
                if key in SENSORS.values():
                    try:
                        value = float(payload)
                    except (ValueError, TypeError):
                        return False
                    if not math.isfinite(value):
                        return False
                    value = str(value)
                else:
                    if payload not in (entity["on"], entity["off"]):
                        return False
                    state = payload == entity["on"]
                    value = (
                        ("пасмурно" if state else "ясно") if key == "mode" else state
                    )
                self.values[key] = value
                self.updated[key] = now
                return True
        return False

    def snapshot(self, now=None):
        now = time.monotonic() if now is None else now
        with self.lock:
            fresh = {
                k: v
                for k, v in self.values.items()
                if now - self.updated[k] <= self.stale_seconds
            }
        return fresh

    def command(self, key, value):
        with self.lock:
            entity = self.entities.get(key)
            if not entity or not entity.get("command"):
                raise RuntimeError(f"Контроллер не сообщил MQTT-топик для {key}")
            state = value == "пасмурно" if key == "mode" else value
            return entity["command"], entity["on"] if state else entity["off"]
