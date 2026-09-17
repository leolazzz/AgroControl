import re
from datetime import datetime, timedelta, timezone
from functools import wraps
from typing import Optional

import bcrypt
import jwt
from flask import current_app, g, jsonify, request
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

from backend.core.models import BlacklistedToken, RefreshToken, User, UserRole, db


limiter = Limiter(
    key_func=get_remote_address,
    default_limits=["200 per day", "50 per hour"],
    storage_uri="memory://",
)


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=12)).decode(
        "utf-8"
    )


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except Exception:
        return False


def validate_password_strength(password: str) -> tuple[bool, Optional[str]]:
    if len(password) < 8:
        return False, "Пароль должен содержать не менее 8 символов"
    if len(password.encode("utf-8")) > 72:
        return False, "Пароль слишком длинный"
    if not any(character.isupper() for character in password):
        return False, "Добавьте хотя бы одну заглавную букву"
    if not re.search(r"[0-9]", password):
        return False, "Добавьте хотя бы одну цифру"
    if not re.search(r"[^\w\s]", password, re.UNICODE):
        return False, "Добавьте хотя бы один специальный символ"
    return True, None


def validate_username(username: str) -> tuple[bool, Optional[str]]:
    if not 3 <= len(username) <= 32:
        return False, "Логин должен содержать от 3 до 32 символов"
    if not re.fullmatch(r"[\w.-]+", username, flags=re.UNICODE):
        return False, "В логине допустимы буквы, цифры, точка, дефис и подчёркивание"
    return True, None


def validate_email(email: str) -> bool:
    pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
    return bool(re.match(pattern, email))


def generate_tokens(
    user: User, device_id: Optional[str] = None, create_refresh: bool = True
) -> dict:
    secret_key = current_app.config.get("JWT_SECRET_KEY")
    if not secret_key:
        raise ValueError("JWT_SECRET_KEY not configured")

    now = datetime.now(timezone.utc)
    access_expires = now + timedelta(
        seconds=current_app.config.get("JWT_ACCESS_EXPIRES", 3600)
    )

    import uuid

    access_jti = str(uuid.uuid4())

    access_payload = {
        "user_id": user.id,
        "username": user.username,
        "role": user.role.value,
        "iat": int(now.timestamp()),
        "exp": int(access_expires.timestamp()),
        "jti": access_jti,
        "type": "access",
    }

    access_token = jwt.encode(access_payload, secret_key, algorithm="HS256")

    response = {
        "access_token": access_token,
        "token_type": "bearer",
        "expires_in": current_app.config.get("JWT_ACCESS_EXPIRES", 3600),
        "user": user.to_dict(),
    }

    if create_refresh:
        refresh_expires = now + timedelta(
            seconds=current_app.config.get("JWT_REFRESH_EXPIRES", 604800)
        )
        refresh_jti = str(uuid.uuid4())

        refresh_payload = {
            "user_id": user.id,
            "iat": int(now.timestamp()),
            "exp": int(refresh_expires.timestamp()),
            "jti": refresh_jti,
            "type": "refresh",
        }

        refresh_token = jwt.encode(refresh_payload, secret_key, algorithm="HS256")

        db_token = RefreshToken(
            user_id=user.id,
            token=refresh_token,
            jti=refresh_jti,
            expires_at=refresh_expires.replace(tzinfo=None),
            device_id=device_id,
        )
        db.session.add(db_token)
        db.session.commit()

        response["refresh_token"] = refresh_token

    return response


def decode_token(token: str, token_type: str = "access") -> Optional[dict]:
    secret_key = current_app.config.get("JWT_SECRET_KEY")
    if not secret_key:
        return None

    try:
        payload = jwt.decode(token, secret_key, algorithms=["HS256"])
        if payload.get("type") != token_type:
            return None

        jti = payload.get("jti")
        if jti:
            blacklisted = BlacklistedToken.query.filter_by(jti=jti).first()
            if blacklisted and blacklisted.expires_at > datetime.now(
                timezone.utc
            ).replace(tzinfo=None):
                return None

        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None


def get_current_user() -> Optional[User]:
    return getattr(g, "current_user", None)


def token_required(f):

    @wraps(f)
    def decorated(*args, **kwargs):

        if not current_app.config.get("AUTH_ENABLED", False):

            g.current_user = None
            return f(*args, **kwargs)

        token = None
        auth_header = request.headers.get("Authorization")
        if auth_header:
            parts = auth_header.split(" ")
            if len(parts) == 2 and parts[0].lower() == "bearer":
                token = parts[1]

        if not token:
            return jsonify({"error": "Missing or invalid authorization token"}), 401

        payload = decode_token(token, "access")
        if not payload:
            return jsonify({"error": "Invalid or expired token"}), 401

        user_id = payload.get("user_id")
        user = User.query.filter_by(id=user_id, is_active=True).first()
        if not user:
            return jsonify({"error": "User not found or inactive"}), 401

        g.current_user = user
        g.token_payload = payload
        return f(*args, **kwargs)

    return decorated


def role_required(*allowed_roles: UserRole):

    def decorator(f):
        @wraps(f)
        @token_required
        def decorated(*args, **kwargs):
            user = get_current_user()
            if not user:
                return jsonify({"error": "Authentication required"}), 401

            if user.role not in allowed_roles:
                return jsonify({"error": "Insufficient permissions"}), 403

            return f(*args, **kwargs)

        return decorated

    return decorator


def admin_required(f):
    return role_required(UserRole.ADMIN)(f)


def cleanup_expired_tokens():
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    RefreshToken.query.filter(RefreshToken.expires_at < now).delete()
    BlacklistedToken.query.filter(BlacklistedToken.expires_at < now).delete()
    db.session.commit()
