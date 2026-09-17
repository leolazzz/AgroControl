from datetime import datetime, timezone

from flask import Blueprint, jsonify, request

from backend.core.auth import (
    generate_tokens,
    get_current_user,
    hash_password,
    limiter,
    role_required,
    token_required,
    validate_email,
    validate_password_strength,
    validate_username,
    verify_password,
)
from backend.core.models import BlacklistedToken, RefreshToken, User, UserRole, db

auth_bp = Blueprint("auth", __name__, url_prefix="/api/auth")


@auth_bp.route("/register", methods=["POST"])
@limiter.limit("5 per 15 minutes")
def register():
    data = request.get_json(silent=True) or {}

    username = (data.get("username") or "").strip()
    email = (data.get("email") or "").strip().lower()
    password = data.get("password", "")
    confirm_password = data.get("confirm_password", "")

    if not username or not email or not password:
        return (
            jsonify({"error": "missing_fields", "message": "Заполните все поля"}),
            400,
        )

    username_valid, username_error = validate_username(username)
    if not username_valid:
        return jsonify({"error": "invalid_username", "message": username_error}), 400

    if not validate_email(email):
        return (
            jsonify({"error": "invalid_email", "message": "Введите корректный email"}),
            400,
        )

    is_valid, error_msg = validate_password_strength(password)
    if not is_valid:
        return jsonify({"error": "weak_password", "message": error_msg}), 400

    if password != confirm_password:
        return (
            jsonify({"error": "password_mismatch", "message": "Пароли не совпадают"}),
            400,
        )

    if User.query.filter_by(username=username).first():
        return (
            jsonify({"error": "username_exists", "message": "Этот логин уже занят"}),
            409,
        )

    if User.query.filter_by(email=email).first():
        return (
            jsonify(
                {
                    "error": "email_exists",
                    "message": "Аккаунт с таким email уже существует",
                }
            ),
            409,
        )

    try:
        user = User(
            username=username,
            email=email,
            password_hash=hash_password(password),
            role=UserRole.VIEWER,
        )
        db.session.add(user)
        db.session.commit()

        tokens = generate_tokens(user, request.headers.get("X-Device-ID"))
        tokens["message"] = "Аккаунт создан"
        return jsonify(tokens), 201
    except Exception:
        db.session.rollback()
        return (
            jsonify(
                {
                    "error": "registration_failed",
                    "message": "Не удалось создать аккаунт",
                }
            ),
            500,
        )


@auth_bp.route("/login", methods=["POST"])
@limiter.limit("5 per 15 minutes")
def login():
    data = request.get_json(silent=True) or {}

    username_or_email = (data.get("username") or data.get("email") or "").strip()
    normalized_email = username_or_email.lower()
    password = data.get("password", "")

    if not username_or_email or not password:
        return (
            jsonify(
                {"error": "missing_credentials", "message": "Введите логин и пароль"}
            ),
            400,
        )

    user = User.query.filter(
        (User.username == username_or_email) | (User.email == normalized_email)
    ).first()

    if not user or not user.is_active:
        return (
            jsonify(
                {"error": "invalid_credentials", "message": "Неверный логин или пароль"}
            ),
            401,
        )

    if not verify_password(password, user.password_hash):
        return (
            jsonify(
                {"error": "invalid_credentials", "message": "Неверный логин или пароль"}
            ),
            401,
        )

    user.last_login = datetime.now(timezone.utc).replace(tzinfo=None)
    db.session.commit()

    device_id = request.headers.get("X-Device-ID")
    tokens = generate_tokens(user, device_id)

    return jsonify(tokens), 200


@auth_bp.route("/refresh", methods=["POST"])
def refresh():
    auth_header = request.headers.get("Authorization")
    if not auth_header:
        return jsonify({"error": "Missing authorization header"}), 401

    parts = auth_header.split(" ")
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return jsonify({"error": "Invalid authorization header"}), 401

    refresh_token = parts[1]

    from backend.core.auth import decode_token

    payload = decode_token(refresh_token, "refresh")
    if not payload:
        return jsonify({"error": "Invalid or expired refresh token"}), 401

    user_id = payload.get("user_id")
    jti = payload.get("jti")

    db_token = RefreshToken.query.filter_by(jti=jti, user_id=user_id).first()
    if not db_token:
        return jsonify({"error": "Refresh token not found"}), 401

    user = User.query.filter_by(id=user_id, is_active=True).first()
    if not user:
        return jsonify({"error": "User not found or inactive"}), 401

    from backend.core.auth import generate_tokens

    tokens = generate_tokens(user, db_token.device_id, create_refresh=False)

    return (
        jsonify(
            {
                "access_token": tokens["access_token"],
                "token_type": "bearer",
                "expires_in": tokens["expires_in"],
            }
        ),
        200,
    )


@auth_bp.route("/logout", methods=["POST"])
@token_required
def logout():
    from backend.core.auth import g

    payload = getattr(g, "token_payload", {})
    jti = payload.get("jti")
    exp = payload.get("exp")

    if jti and exp:
        expires_at = datetime.fromtimestamp(exp, timezone.utc).replace(tzinfo=None)
        blacklisted = BlacklistedToken(jti=jti, expires_at=expires_at)
        db.session.add(blacklisted)

        refresh_header = request.headers.get("X-Refresh-Token")
        if refresh_header:
            refresh_token = RefreshToken.query.filter_by(token=refresh_header).first()
            if refresh_token:
                db.session.delete(refresh_token)

        db.session.commit()

    return jsonify({"message": "Logged out successfully"}), 200


@auth_bp.route("/change-password", methods=["POST"])
@token_required
def change_password():
    user = get_current_user()
    if not user:
        return (
            jsonify(
                {
                    "error": "authentication_required",
                    "message": "Требуется вход в аккаунт",
                }
            ),
            401,
        )

    data = request.get_json(silent=True) or {}
    current_password = data.get("current_password", "")
    new_password = data.get("new_password", "")
    confirm_password = data.get("confirm_password", "")

    if not current_password or not new_password:
        return (
            jsonify(
                {"error": "missing_fields", "message": "Заполните все поля пароля"}
            ),
            400,
        )

    if not verify_password(current_password, user.password_hash):
        return (
            jsonify(
                {
                    "error": "invalid_current_password",
                    "message": "Текущий пароль указан неверно",
                }
            ),
            401,
        )

    is_valid, error_msg = validate_password_strength(new_password)
    if not is_valid:
        return jsonify({"error": "weak_password", "message": error_msg}), 400

    if new_password != confirm_password:
        return (
            jsonify(
                {"error": "password_mismatch", "message": "Новые пароли не совпадают"}
            ),
            400,
        )

    if verify_password(new_password, user.password_hash):
        return (
            jsonify(
                {
                    "error": "password_unchanged",
                    "message": "Новый пароль должен отличаться от текущего",
                }
            ),
            400,
        )

    user.password_hash = hash_password(new_password)
    db.session.commit()

    return jsonify({"status": "ok", "message": "Пароль успешно изменён"}), 200


@auth_bp.route("/me", methods=["GET"])
@token_required
def get_current_user_info():
    user = get_current_user()
    if not user:
        return (
            jsonify(
                {
                    "error": "authentication_required",
                    "message": "Требуется вход в аккаунт",
                }
            ),
            401,
        )

    return jsonify(user.to_dict()), 200
