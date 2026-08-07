from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import threading
from pathlib import Path
from typing import Literal

from fastapi import Depends, HTTPException, Request, status

from ..config import Settings, get_settings


ADMIN_COOKIE = "page_shelf_admin"
DEFAULT_ADMIN_PASSWORD = "112233"
PASSWORD_ITERATIONS = 210_000
SessionScope = Literal["admin", "mobile"]
_credentials_lock = threading.Lock()


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii")


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value.encode("ascii"))


def _password_hash(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PASSWORD_ITERATIONS)


def _new_credentials(settings: Settings) -> dict[str, object]:
    initial_password = settings.admin_password.strip() or DEFAULT_ADMIN_PASSWORD
    salt = secrets.token_bytes(16)
    configured_secret = settings.admin_session_secret.strip()
    return {
        "version": 1,
        "password_salt": _encode(salt),
        "password_hash": _encode(_password_hash(initial_password, salt)),
        "session_secret": configured_secret if len(configured_secret) >= 16 else secrets.token_urlsafe(48),
        "password_change_required": initial_password == DEFAULT_ADMIN_PASSWORD,
    }


def _validate_credentials(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or value.get("version") != 1:
        raise RuntimeError("管理凭据文件格式无效")
    required = ("password_salt", "password_hash", "session_secret", "password_change_required")
    if any(key not in value for key in required):
        raise RuntimeError("管理凭据文件不完整")
    try:
        salt = _decode(str(value["password_salt"]))
        password_hash = _decode(str(value["password_hash"]))
    except Exception as exc:
        raise RuntimeError("管理凭据文件编码无效") from exc
    if len(salt) < 16 or len(password_hash) != 32 or len(str(value["session_secret"])) < 16:
        raise RuntimeError("管理凭据文件内容无效")
    if not isinstance(value["password_change_required"], bool):
        raise RuntimeError("管理凭据文件状态无效")
    return value


def _write_credentials(path: Path, credentials: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(6)}.tmp")
    try:
        temporary.write_text(
            json.dumps(credentials, ensure_ascii=True, indent=2) + "\n",
            encoding="utf-8",
        )
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _credentials(settings: Settings) -> dict[str, object]:
    path = settings.admin_credentials_path.resolve()
    with _credentials_lock:
        if path.exists():
            try:
                return _validate_credentials(json.loads(path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError) as exc:
                raise RuntimeError("无法读取管理凭据文件") from exc
        credentials = _new_credentials(settings)
        _write_credentials(path, credentials)
        return credentials


def password_is_configured(settings: Settings) -> bool:
    _credentials(settings)
    return True


def password_change_required(settings: Settings) -> bool:
    return bool(_credentials(settings)["password_change_required"])


def verify_password(candidate: str, settings: Settings) -> bool:
    credentials = _credentials(settings)
    salt = _decode(str(credentials["password_salt"]))
    expected = _decode(str(credentials["password_hash"]))
    return hmac.compare_digest(_password_hash(candidate, salt), expected)


def update_password(current_password: str, new_password: str, settings: Settings) -> bool:
    if not verify_password(current_password, settings):
        return False
    if new_password == DEFAULT_ADMIN_PASSWORD:
        raise ValueError("新密码不能继续使用默认密码 112233")
    salt = secrets.token_bytes(16)
    credentials = {
        "version": 1,
        "password_salt": _encode(salt),
        "password_hash": _encode(_password_hash(new_password, salt)),
        "session_secret": secrets.token_urlsafe(48),
        "password_change_required": False,
    }
    with _credentials_lock:
        _write_credentials(settings.admin_credentials_path.resolve(), credentials)
    return True


def create_session_token(settings: Settings, scope: SessionScope) -> str:
    # 会话不设有效时限：仅当修改管理密码（轮换会话密钥）后全部失效。
    nonce = secrets.token_urlsafe(18)
    payload = f"v1.{scope}.{nonce}"
    signature = hmac.new(_session_key(settings), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{signature}"


def valid_session(token: str | None, settings: Settings, scope: SessionScope) -> bool:
    if not token or not password_is_configured(settings):
        return False
    parts = token.split(".")
    if len(parts) != 4:
        return False
    version, token_scope, nonce, signature = parts
    if version != "v1" or token_scope != scope or not nonce:
        return False
    payload = ".".join(parts[:3])
    expected = hmac.new(_session_key(settings), payload.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature, expected)


def _session_key(settings: Settings) -> bytes:
    credentials = _credentials(settings)
    password_digest = _decode(str(credentials["password_hash"]))
    return hmac.new(
        str(credentials["session_secret"]).encode(),
        password_digest,
        hashlib.sha256,
    ).digest()


def require_admin_session(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> None:
    if not valid_session(request.cookies.get(ADMIN_COOKIE), settings, "admin"):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="请先登录管理后台")
    if request.method not in {"GET", "HEAD", "OPTIONS"} and request.headers.get("X-Page-Shelf-Admin") != "1":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="管理请求校验失败")


def require_admin(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> None:
    require_admin_session(request, settings)
    if password_change_required(settings):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="首次登录必须先修改默认管理密码",
        )


def require_mobile_session(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> None:
    """Validate the bearer session used by the reading app.

    The management password is exchanged once at the login endpoint. Ordinary
    book, file and progress requests only carry this signed session token.
    Sessions do not expire by time; changing the management password rotates
    the signing key and invalidates every existing session.
    """
    authorization = request.headers.get("Authorization", "")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not valid_session(token.strip(), settings, "mobile"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="登录已失效，请重新连接服务器",
            headers={"WWW-Authenticate": "Bearer"},
        )
