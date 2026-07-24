def test_mobile_login_rejects_wrong_password(client):
    response = client.post("/api/v1/auth/login", json={"password": "wrong-password"})

    assert response.status_code == 401
    assert response.json()["detail"] == "管理密码错误"


def test_mobile_session_and_health_expose_compatible_version(client):
    session = client.get("/api/v1/auth/session")
    health = client.get("/health")

    assert session.status_code == 200
    assert session.json()["api_version"] == "1.0"
    assert health.json() == {"status": "ok", "api_version": "1.0"}


def test_books_require_bearer_session(client):
    authorization = client.headers.pop("Authorization")
    try:
        response = client.get("/api/v1/books")
        cover = client.get("/api/v1/books/missing/cover")
    finally:
        client.headers["Authorization"] = authorization

    assert response.status_code == 401
    assert cover.status_code == 401


def test_mobile_session_cannot_be_reused_as_admin_cookie(client):
    token = client.headers["Authorization"].removeprefix("Bearer ")
    client.cookies.set("page_shelf_admin", token)

    response = client.get("/api/v1/admin/overview")

    assert response.status_code == 401


def test_login_rate_limit_blocks_repeated_password_guesses(client):
    responses = [
        client.post("/api/v1/auth/login", json={"password": f"wrong-{attempt}"})
        for attempt in range(9)
    ]

    assert all(response.status_code == 401 for response in responses[:8])
    assert responses[8].status_code == 429
    assert int(responses[8].headers["retry-after"]) > 0


def test_scan_requires_authentication(client):
    authorization = client.headers.pop("Authorization")
    try:
        response = client.post("/api/v1/library/scan")
    finally:
        client.headers["Authorization"] = authorization

    assert response.status_code == 401
