from datetime import datetime, timezone
from enum import IntEnum

from flask_sqlalchemy import SQLAlchemy
from sqlalchemy import (
    Column,
    Integer,
    String,
    Boolean,
    DateTime,
    ForeignKey,
    Float,
    Text,
    Enum,
)
from sqlalchemy.orm import relationship

db = SQLAlchemy()


def utcnow_naive():
    return datetime.now(timezone.utc).replace(tzinfo=None)


class UserRole(IntEnum):
    ADMIN = 1
    OPERATOR = 2
    VIEWER = 3


class CommandStatus(IntEnum):
    PENDING = 0
    IN_PROGRESS = 1
    SUCCESS = 2
    FAILED = 3


class User(db.Model):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, autoincrement=True)
    username = Column(String(80), unique=True, nullable=False, index=True)
    email = Column(String(120), unique=True, nullable=False, index=True)
    password_hash = Column(String(255), nullable=False)
    role = Column(Enum(UserRole), nullable=False, default=UserRole.VIEWER)
    created_at = Column(DateTime, nullable=False, default=utcnow_naive)
    last_login = Column(DateTime, nullable=True)
    is_active = Column(Boolean, nullable=False, default=True)

    sessions = relationship(
        "RefreshToken", back_populates="user", cascade="all, delete-orphan"
    )
    sensor_data = relationship(
        "SensorData", back_populates="user", cascade="all, delete-orphan"
    )
    commands = relationship(
        "CommandQueue", back_populates="user", cascade="all, delete-orphan"
    )

    def to_dict(self):
        return {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "role": self.role.value,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "last_login": self.last_login.isoformat() if self.last_login else None,
        }


class RefreshToken(db.Model):

    __tablename__ = "refresh_tokens"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    token = Column(Text, nullable=False, unique=True, index=True)
    jti = Column(String(36), nullable=True, unique=True, index=True)
    expires_at = Column(DateTime, nullable=False, index=True)
    created_at = Column(DateTime, nullable=False, default=utcnow_naive)
    device_id = Column(String(255), nullable=True)

    user = relationship("User", back_populates="sessions")


class BlacklistedToken(db.Model):

    __tablename__ = "blacklisted_tokens"

    id = Column(Integer, primary_key=True, autoincrement=True)
    jti = Column(String(36), unique=True, nullable=False, index=True)
    expires_at = Column(DateTime, nullable=False, index=True)
    created_at = Column(DateTime, nullable=False, default=utcnow_naive)


class SensorData(db.Model):

    __tablename__ = "sensor_data"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True
    )
    device_id = Column(String(255), nullable=True, index=True)
    timestamp = Column(DateTime, nullable=False, default=utcnow_naive, index=True)
    air_temp = Column(Float, nullable=True)
    humidity = Column(Float, nullable=True)
    solution_temp = Column(Float, nullable=True)
    light = Column(Integer, nullable=True)
    level = Column(Float, nullable=True)
    ec = Column(Float, nullable=True)
    ph = Column(Float, nullable=True)
    mode = Column(String(20), nullable=True)
    origin = Column(String(50), nullable=True, default="mobile")
    sync_metadata = Column(Text, nullable=True)

    user = relationship("User", back_populates="sensor_data")

    def to_dict(self):
        return {
            "id": self.id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "air_temp": str(self.air_temp) if self.air_temp is not None else "0",
            "humidity": str(self.humidity) if self.humidity is not None else "0",
            "solution_temp": (
                str(self.solution_temp) if self.solution_temp is not None else "0"
            ),
            "light": str(self.light) if self.light is not None else "0",
            "level": str(self.level) if self.level is not None else "0",
            "ec": str(self.ec) if self.ec is not None else "0",
            "ph": str(self.ph) if self.ph is not None else "0",
            "mode": self.mode or "ясно",
        }


class CommandQueue(db.Model):

    __tablename__ = "command_queue"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True
    )
    device_id = Column(String(255), nullable=True, index=True)
    command_type = Column(String(50), nullable=False)
    relay_id = Column(Integer, nullable=True)
    target_state = Column(Boolean, nullable=True)
    parameter = Column(String(255), nullable=True)
    timestamp = Column(DateTime, nullable=False, default=utcnow_naive, index=True)
    attempt_count = Column(Integer, nullable=False, default=0)
    last_attempt = Column(DateTime, nullable=True)
    status = Column(
        Enum(CommandStatus), nullable=False, default=CommandStatus.PENDING, index=True
    )
    server_timestamp = Column(DateTime, nullable=True)
    error_message = Column(Text, nullable=True)

    user = relationship("User", back_populates="commands")

    def to_dict(self):
        return {
            "id": self.id,
            "command_type": self.command_type,
            "relay_id": self.relay_id,
            "target_state": self.target_state,
            "parameter": self.parameter,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "status": self.status.value,
            "attempt_count": self.attempt_count,
        }
