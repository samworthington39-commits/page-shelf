def test_reader_page_and_assets_are_served_with_security_headers(client):
    page = client.get("/reader")
    script = client.get("/reader/assets/reader.js")
    styles = client.get("/reader/assets/reader.css")
    favicon = client.get("/reader/assets/favicon.svg")

    assert page.status_code == 200
    assert page.headers["cache-control"] == "no-store"
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    assert "frame-src blob:" in page.headers["content-security-policy"]
    assert page.headers["x-frame-options"] == "DENY"
    assert 'id="login-form"' in page.text
    assert 'id="shelf-tabs"' in page.text
    assert 'id="admin-link"' in page.text
    assert 'href="/admin"' in page.text
    assert 'id="reader-toolbar"' in page.text
    assert 'id="chapter-nav-bottom"' not in page.text
    assert 'id="toc-drawer"' in page.text

    assert script.status_code == 200
    assert styles.status_code == 200
    assert favicon.status_code == 200
    assert "/auth/login" in script.text
    assert "/shelves" in script.text
    assert "/toc" in script.text
    assert "/progress/" in script.text
    assert "scheduleAutoAdvance" in script.text
    assert "autoAdvance: true" in script.text
    assert 'data-theme="sepia"' not in page.text
    assert 'data-theme-choice="sepia"' in page.text
    assert ".toc-drawer.open" in styles.text
    assert ".reader-toolbar" in styles.text
    assert ".chapter-content" in styles.text


def test_reader_page_does_not_require_a_bearer_session(client):
    authorization = client.headers.pop("Authorization")
    try:
        page = client.get("/reader")
        protected_shelves = client.get("/api/v1/shelves")
    finally:
        client.headers["Authorization"] = authorization

    assert page.status_code == 200
    assert protected_shelves.status_code == 401
