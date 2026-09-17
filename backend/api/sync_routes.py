from datetime import datetime, timezone
from typing import Any, Dict

from flask import Blueprint, current_app, jsonify, request

from backend.core.auth import get_current_user, token_required
from backend.core.models import CommandQueue, CommandStatus, SensorData, db

sync_bp = Blueprint("sync", __name__, url_prefix="/api/sync")


def utcnow_naive():
    return datetime.now(timezone.utc).replace(tzinfo=None)


@sync_bp.route("/sensor-data", methods=["POST"])
@token_required
def sync_sensor_data():
    user = get_current_user()
    data = request.get_json(silent=True) or {}
    if not isinstance(data, dict):
        return jsonify({"error": "Ожидается JSON-объект"}), 400

    readings = data.get("readings", [])
    if not isinstance(readings, list):
        return jsonify({"error": "readings must be an array"}), 400

    device_id = request.headers.get("X-Device-ID", "unknown")

    accepted = []
    rejected = []

    for reading in readings:
        if not isinstance(reading, dict):
            rejected.append({"local_id": None, "error": "Ожидается объект показаний"})
            continue
        try:

            timestamp_str = reading.get("timestamp")
            if isinstance(timestamp_str, str):
                try:
                    timestamp = datetime.fromisoformat(
                        timestamp_str.replace("Z", "+00:00")
                    )
                except Exception:
                    timestamp = datetime.fromtimestamp(float(timestamp_str))
            elif isinstance(timestamp_str, (int, float)):
                timestamp = datetime.fromtimestamp(float(timestamp_str))
            else:
                timestamp = utcnow_naive()

            sensor_entry = SensorData(
                user_id=user.id if user else None,
                device_id=device_id,
                timestamp=timestamp,
                air_temp=(
                    float(reading.get("air_temp", 0))
                    if reading.get("air_temp")
                    else None
                ),
                humidity=(
                    float(reading.get("humidity", 0))
                    if reading.get("humidity")
                    else None
                ),
                solution_temp=(
                    float(reading.get("solution_temp", 0))
                    if reading.get("solution_temp")
                    else None
                ),
                light=int(reading.get("light", 0)) if reading.get("light") else None,
                level=float(reading.get("level", 0)) if reading.get("level") else None,
                ec=float(reading.get("ec", 0)) if reading.get("ec") else None,
                ph=float(reading.get("ph", 0)) if reading.get("ph") else None,
                mode=reading.get("mode"),
                origin="mobile",
                sync_metadata=str(reading.get("sync_metadata", "")),
            )

            db.session.add(sensor_entry)
            db.session.flush()
            accepted.append(
                {"local_id": reading.get("local_id"), "server_id": sensor_entry.id}
            )
        except Exception as e:
            rejected.append({"local_id": reading.get("local_id"), "error": str(e)})

    db.session.commit()

    return jsonify({"accepted": accepted, "rejected": rejected}), 200


@sync_bp.route("/commands", methods=["POST"])
@token_required
def sync_commands():
    user = get_current_user()
    data = request.get_json(silent=True) or {}
    if not isinstance(data, dict):
        return jsonify({"error": "Ожидается JSON-объект"}), 400

    commands = data.get("commands", [])
    if not isinstance(commands, list):
        return jsonify({"error": "commands must be an array"}), 400

    device_id = request.headers.get("X-Device-ID", "unknown")
    results = []

    for cmd_data in commands:
        if not isinstance(cmd_data, dict):
            results.append(
                {
                    "local_id": None,
                    "status": "failed",
                    "error": "Ожидается объект команды",
                }
            )
            continue
        local_id = cmd_data.get("local_id")
        command_type = cmd_data.get("command_type")
        relay_id = cmd_data.get("relay_id")
        target_state = cmd_data.get("target_state")
        parameter = cmd_data.get("parameter")

        try:

            if command_type == "RELAY_TOGGLE" and relay_id in (1, 2, 3):
                updates = {f"relay{relay_id}": target_state}
            elif command_type == "SET_MODE" and parameter:
                updates = {"mode": parameter}
            elif command_type == "SET_EC_ENABLED":
                updates = {"ec_onoff": target_state}
            elif command_type == "SET_PH_ENABLED":
                updates = {"ph_onoff": target_state}
            else:
                raise ValueError(f"Unsupported command: {command_type}")

            current_app.extensions["control_updater"](updates)

            cmd_entry = CommandQueue(
                user_id=user.id if user else None,
                device_id=device_id,
                command_type=command_type,
                relay_id=relay_id,
                target_state=target_state,
                parameter=parameter,
                status=CommandStatus.SUCCESS,
                server_timestamp=utcnow_naive(),
            )
            db.session.add(cmd_entry)
            db.session.flush()

            results.append(
                {
                    "local_id": local_id,
                    "server_id": cmd_entry.id,
                    "status": "success",
                    "server_timestamp": cmd_entry.server_timestamp.isoformat(),
                }
            )
        except Exception as e:
            results.append(
                {
                    "local_id": local_id,
                    "status": "failed",
                    "error": str(e),
                    "server_timestamp": utcnow_naive().isoformat(),
                }
            )

    db.session.commit()

    return jsonify({"results": results}), 200


@sync_bp.route("/pending", methods=["GET"])
@token_required
def get_pending_data():
    user = get_current_user()
    last_sync = request.args.get("last_sync_timestamp")

    if last_sync:
        try:
            if last_sync.replace(".", "").replace("-", "").replace(":", "").isdigit():
                last_sync_dt = datetime.fromtimestamp(float(last_sync))
            else:
                last_sync_dt = datetime.fromisoformat(last_sync.replace("Z", "+00:00"))
        except Exception:
            last_sync_dt = None
    else:
        last_sync_dt = None

    sensor_query = SensorData.query
    if user:
        sensor_query = sensor_query.filter(SensorData.user_id == user.id)
    if last_sync_dt:
        sensor_query = sensor_query.filter(SensorData.timestamp > last_sync_dt)

    sensor_data = sensor_query.order_by(SensorData.timestamp.desc()).limit(100).all()

    command_query = CommandQueue.query
    if user:
        command_query = command_query.filter(CommandQueue.user_id == user.id)
    if last_sync_dt:
        command_query = command_query.filter(CommandQueue.timestamp > last_sync_dt)

    commands = command_query.order_by(CommandQueue.timestamp.desc()).limit(100).all()

    return (
        jsonify(
            {
                "sensor_data": [s.to_dict() for s in sensor_data],
                "commands": [c.to_dict() for c in commands],
                "server_timestamp": utcnow_naive().isoformat(),
            }
        ),
        200,
    )


@sync_bp.route("/health", methods=["GET"])
def health_check():
    return (
        jsonify(
            {
                "status": "ok",
                "timestamp": utcnow_naive().isoformat(),
                "version": "2.0.0",
                "services": {
                    "ai_model": "available",
                    "database": "connected",
                    "ollama": "running",
                },
            }
        ),
        200,
    )
