from __future__ import annotations

import argparse
import json
import math
import os
import signal
import sys
import threading
import time
from dataclasses import dataclass

import paho.mqtt.client as mqtt


SENSORS = (
    ("agr_t", "air_temperature", "Температура воздуха", "°C", 23.5, 0.8),
    ("agr_h", "humidity", "Влажность воздуха", "%", 62.0, 4.0),
    ("agr_tds", "solution_temperature", "Температура раствора", "°C", 21.0, 0.5),
    ("agr_l", "light", "Освещённость", "lx", 8500.0, 2200.0),
    ("agr_lv", "solution_level", "Уровень раствора", "%", 74.0, 2.0),
    ("agr_ec", "ec", "Электропроводность", "mS/cm", 1.6, 0.08),
    ("agr_ph", "ph", "pH", "pH", 6.1, 0.12),
)

CONTROLS = (
    ("agr_r1", "relay1", "Реле 1", "ON", "OFF", False),
    ("agr_r2", "relay2", "Реле 2", "ON", "OFF", False),
    ("agr_r3", "relay3", "Реле 3", "ON", "OFF", False),
    ("agr_ClearCloudy", "mode", "Режим освещения", "CLOUDY", "CLEAR", False),
    ("agr_swec", "ec_onoff", "Измерение EC", "ON", "OFF", True),
    ("agr_swph", "ph_onoff", "Измерение pH", "ON", "OFF", True),
)


@dataclass
class Settings:
    host: str
    port: int
    device_id: str
    username: str | None
    password: str | None
    interval: float


class HotBedEmulator:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.prefix = f"hotbed/{settings.device_id}"
        self.stop_event = threading.Event()
        self.connected = threading.Event()
        self.states = {name: initial for _, name, _, _, _, initial in CONTROLS}
        self.control_by_topic = {
            f"{self.prefix}/controls/{name}/command": (name, on, off)
            for _, name, _, on, off, _ in CONTROLS
        }
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"hotbed-emulator-{settings.device_id}",
        )
        if settings.username:
            self.client.username_pw_set(settings.username, settings.password)
        self.client.on_connect = self.on_connect
        self.client.on_disconnect = self.on_disconnect
        self.client.on_message = self.on_message

    def config_topic(self, component: str, name: str) -> str:
        return f"homeassistant/{component}/{self.settings.device_id}/{name}/config"

    def device(self) -> dict[str, object]:
        return {
            "identifiers": [self.settings.device_id],
            "name": f"HotBed {self.settings.device_id}",
            "manufacturer": "HotBed",
            "model": "Virtual controller",
        }

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code != 0:
            print(f"Не удалось подключиться к MQTT: {reason_code}")
            return
        self.connected.set()
        print(f"MQTT подключён: {self.settings.host}:{self.settings.port}")
        self.publish_discovery()
        for topic in self.control_by_topic:
            client.subscribe(topic, qos=1)
        # Backend подписывается на состояния после получения discovery.
        time.sleep(0.4)
        self.publish_all_states()

    def on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        self.connected.clear()
        if not self.stop_event.is_set():
            print(f"MQTT отключён: {reason_code}")

    def on_message(self, client, userdata, message):
        command = message.payload.decode("utf-8", errors="replace")
        control = self.control_by_topic.get(message.topic)
        if control is None:
            return
        name, on_payload, off_payload = control
        if command not in (on_payload, off_payload):
            print(f"Пропущена команда для {name}: {command!r}")
            return
        self.states[name] = command == on_payload
        self.publish_control_state(name)
        print(f"Команда: {name} -> {command}")

    def publish_discovery(self):
        for unique_id, name, title, unit, _, _ in SENSORS:
            payload = {
                "name": title,
                "unique_id": unique_id,
                "state_topic": f"{self.prefix}/sensors/{name}/state",
                "unit_of_measurement": unit,
                "device": self.device(),
            }
            self.client.publish(self.config_topic("sensor", name), json.dumps(payload), qos=1, retain=True)

        for unique_id, name, title, on, off, _ in CONTROLS:
            payload = {
                "name": title,
                "unique_id": unique_id,
                "state_topic": f"{self.prefix}/controls/{name}/state",
                "command_topic": f"{self.prefix}/controls/{name}/command",
                "payload_on": on,
                "payload_off": off,
                "device": self.device(),
            }
            self.client.publish(self.config_topic("switch", name), json.dumps(payload), qos=1, retain=True)

    def publish_sensor_values(self):
        moment = time.monotonic()
        for _, name, _, _, center, amplitude in SENSORS:
            phase = (abs(hash(name)) % 100) / 20
            value = center + math.sin(moment / 45 + phase) * amplitude
            self.client.publish(
                f"{self.prefix}/sensors/{name}/state", f"{value:.2f}", qos=1, retain=False
            )

    def publish_control_state(self, name: str):
        control = next(item for item in CONTROLS if item[1] == name)
        _, _, _, on_payload, off_payload, _ = control
        payload = on_payload if self.states[name] else off_payload
        self.client.publish(f"{self.prefix}/controls/{name}/state", payload, qos=1, retain=False)

    def publish_all_states(self):
        self.publish_sensor_values()
        for _, name, _, _, _, _ in CONTROLS:
            self.publish_control_state(name)

    def run(self):
        self.client.connect_async(self.settings.host, self.settings.port, keepalive=30)
        self.client.loop_start()
        if not self.connected.wait(timeout=12):
            self.client.loop_stop()
            raise RuntimeError("Не удалось подключиться к MQTT-брокеру за 12 секунд")

        print(f"Эмулятор HotBed запущен. Идентификатор устройства: {self.settings.device_id}")
        try:
            while not self.stop_event.wait(self.settings.interval):
                if self.connected.is_set():
                    self.publish_sensor_values()
        finally:
            self.stop_event.set()
            self.client.disconnect()
            self.client.loop_stop()


def read_settings() -> Settings:
    parser = argparse.ArgumentParser(description="Эмулятор MQTT-контроллера HotBed")
    parser.add_argument("--host", default=os.getenv("MQTT_BROKER_URL", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("MQTT_BROKER_PORT", "1883")))
    parser.add_argument("--device-id", default=os.getenv("MQTT_DEVICE_ID", "hotbed1"))
    parser.add_argument("--username", default=os.getenv("MQTT_USERNAME") or None)
    parser.add_argument("--password", default=os.getenv("MQTT_PASSWORD") or None)
    parser.add_argument("--interval", type=float, default=float(os.getenv("EMULATOR_INTERVAL", "5")))
    args = parser.parse_args()
    if args.interval <= 0:
        parser.error("--interval должен быть больше нуля")
    return Settings(args.host, args.port, args.device_id, args.username, args.password, args.interval)


def main():
    emulator = HotBedEmulator(read_settings())
    signal.signal(signal.SIGINT, lambda *_: emulator.stop_event.set())
    signal.signal(signal.SIGTERM, lambda *_: emulator.stop_event.set())
    try:
        emulator.run()
    except RuntimeError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
