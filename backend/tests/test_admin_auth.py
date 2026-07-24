from __future__ import annotations

from app.config import Settings
from app.services.admin_auth import (
    create_session_token,
    password_change_required,
    update_password,
    valid_session,
    verify_password,
)


ADMIN_HEADERS = {"X-Page-Shelf-Admin": "1"}


def test_default_password_requires_change_and_persists_new_hash(tmp_path):
    settings = Settings(
        admin_password="112233",
        admin_session_secret="",
        admin_credentials_path=tmp_path / "admin_credentials.json",
        _env_file=None,
    )

    assert verify_password("112233", settings)
    assert password_change_required(settings) is True
    old_token = create_session_token(settings, "admin")

    assert update_password("wrong", "a-new-secure-password", settings) is False
    assert update_password("112233", "a-new-secure-password", settings) is True
    assert verify_password("112233", settings) is False
    assert verify_password("a-new-secure-password", settings) is True
    assert password_change_required(settings) is False
    assert valid_session(old_token, settings, "admin") is False
    assert "112233" not in settings.admin_credentials_path.read_text(encoding="utf-8")


def test_admin_password_endpoint_rotates_credentials(client):
    login = client.post("/api/v1/admin/session", json={"password": "test-admin-password"})
    assert login.status_code == 204
    status = client.get("/api/v1/admin/status")
    assert status.json()["password_change_required"] is False

    wrong = client.put(
        "/api/v1/admin/password",
        headers=ADMIN_HEADERS,
        json={"current_password": "wrong", "new_password": "replacement-password"},
    )
    assert wrong.status_code == 400

    changed = client.put(
        "/api/v1/admin/password",
        headers=ADMIN_HEADERS,
        json={"current_password": "test-admin-password", "new_password": "replacement-password"},
    )
    assert changed.status_code == 204
    assert client.post(
        "/api/v1/admin/session", json={"password": "test-admin-password"}
    ).status_code == 401
    assert client.post(
        "/api/v1/admin/session", json={"password": "replacement-password"}
    ).status_code == 204
