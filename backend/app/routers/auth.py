from __future__ import annotations

import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from ..config import Settings, get_settings
from ..services.admin_auth import (
    create_session_token,
    password_change_required,
    password_is_configured,
    require_mobile_session,
    verify_password,
)
from ..services.login_limiter import clear_login_failures, enforce_login_limit, record_login_failure


API_VERSION = "1.0"
router = APIRouter(prefix="/auth", tags=["auth"])


class MobileLogin(BaseModel):
    password: str = Field(min_length=1, max_length=512)


class MobileSession(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime
    api_version: str = API_VERSION


@router.post("/login", response_model=MobileSession)
def login(payload: MobileLogin, request: Request, settings: Settings = Depends(get_settings)) -> MobileSession:
    if not password_is_configured(settings):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="请先配置 ADMIN_PASSWORD 和至少 16 位的 ADMIN_SESSION_SECRET",
        )
    if password_change_required(settings):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="请先访问网页管理后台，并修改默认管理密码",
        )
    enforce_login_limit(request)
    if not verify_password(payload.password, settings):
        record_login_failure(request)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="管理密码错误")
    clear_login_failures(request)
    lifetime_seconds = max(settings.admin_session_hours, 1) * 3600
    return MobileSession(
        access_token=create_session_token(settings, "mobile"),
        expires_at=datetime.fromtimestamp(time.time() + lifetime_seconds, tz=timezone.utc),
    )


@router.get("/session", dependencies=[Depends(require_mobile_session)])
def session_status() -> dict[str, str]:
    return {"status": "ok", "api_version": API_VERSION}
