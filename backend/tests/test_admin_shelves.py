from __future__ import annotations

import os

from sqlalchemy import select

from app.models import Book, ReadingProgress, Shelf, StorageLocation
from app.services import shelf_scanner
from conftest import TEST_LIBRARY, TEST_ROOT
from pdf_factory import create_pdf


ADMIN_HEADERS = {"X-Page-Shelf-Admin": "1"}


def _login(client) -> None:
    response = client.post("/api/v1/admin/session", json={"password": "test-admin-password"})
    assert response.status_code == 204


def _create_location(client, path=TEST_LIBRARY) -> dict:
    response = client.post(
        "/api/v1/admin/storage-locations",
        headers=ADMIN_HEADERS,
        json={"name": "测试存储", "path": str(path), "create_directory": True},
    )
    assert response.status_code == 201, response.text
    return response.json()


def test_management_page_login_and_security_boundary(client):
    page = client.get("/admin")
    unauthorized = client.get("/api/v1/admin/overview")
    wrong_password = client.post("/api/v1/admin/session", json={"password": "wrong"})

    assert page.status_code == 200
    assert "把散落的文件" in page.text
    assert 'id="refresh-storage-button"' in page.text
    assert 'id="shelf-pin"' in page.text
    assert 'id="book-list"' in page.text
    assert 'id="edit-book-dialog"' in page.text
    assert 'id="edit-book-form"' in page.text
    assert 'id="edit-cover-file"' in page.text
    assert 'id="edit-book-filename"' in page.text
    assert 'id="edit-book-location"' in page.text
    assert 'id="split-dialog"' in page.text
    assert 'id="reset-button"' in page.text
    assert 'id="reader-link"' in page.text
    assert 'href="/reader"' in page.text
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    assert unauthorized.status_code == 401
    assert wrong_password.status_code == 401

    _login(client)
    overview = client.get("/api/v1/admin/overview")
    assert overview.status_code == 200
    assert overview.json()["storage_roots"][0]["path"] == str(TEST_LIBRARY.resolve())


def test_admin_book_editor_assets_include_safe_upload_and_metadata_contract(client):
    script = client.get("/admin/assets/admin.js")
    styles = client.get("/admin/assets/admin.css")

    assert script.status_code == 200
    assert styles.status_code == 200
    assert "COVER_MAX_BYTES = 8 * 1024 * 1024" in script.text
    assert 'new Set(["image/jpeg", "image/png"])' in script.text
    assert "reader.result.slice(separator + 1)" in script.text
    assert "`/books/${book.id}/metadata`" in script.text
    for field in (
        "title",
        "author",
        "reset_title",
        "reset_author",
        "cover_base64",
        "cover_filename",
        "remove_cover",
    ):
        assert f"{field}:" in script.text
    assert 'if (!editing.resetTitle) payload.title = title' in script.text
    assert 'if (!editing.resetAuthor) payload.author = author || null' in script.text
    assert '["manual", "custom", "uploaded"]' in script.text
    assert 'epub: "EPUB 内置封面"' in script.text
    assert 'pdf: "PDF 首页封面"' in script.text
    assert 'generated: "自动生成封面"' in script.text
    assert ".book-cover-image" in styles.text
    assert ".dialog-editor" in styles.text
    assert "@media (max-width: 420px)" in styles.text


def test_storage_location_must_stay_inside_authorized_root(client):
    _login(client)
    response = client.post(
        "/api/v1/admin/storage-locations",
        headers=ADMIN_HEADERS,
        json={"name": "越界目录", "path": str(TEST_ROOT.parent), "create_directory": False},
    )

    assert response.status_code == 422
    assert "不在已授权范围" in response.json()["detail"]


def test_storage_location_accepts_read_only_authorized_root(client, monkeypatch):
    _login(client)
    checked_modes: list[int] = []

    def read_only_access(path, mode):  # noqa: ARG001
        checked_modes.append(mode)
        return mode == os.R_OK | os.X_OK

    monkeypatch.setattr("app.services.storage_paths.os.access", read_only_access)
    response = client.post(
        "/api/v1/admin/storage-locations",
        headers=ADMIN_HEADERS,
        json={"name": "只读存储", "path": str(TEST_LIBRARY), "create_directory": True},
    )

    assert response.status_code == 201, response.text
    assert checked_modes == [os.R_OK | os.X_OK]


def test_storage_root_can_be_removed_and_refreshed(client):
    _login(client)
    removed = client.request(
        "DELETE",
        "/api/v1/admin/storage-roots",
        headers=ADMIN_HEADERS,
        json={"path": str(TEST_LIBRARY)},
    )
    after_remove = client.get("/api/v1/admin/overview")
    refreshed = client.post("/api/v1/admin/storage-roots/refresh", headers=ADMIN_HEADERS)
    after_refresh = client.get("/api/v1/admin/overview")

    assert removed.status_code == 204
    assert after_remove.json()["storage_roots"] == []
    assert refreshed.status_code == 200
    assert after_refresh.json()["storage_roots"][0]["path"] == str(TEST_LIBRARY.resolve())


def test_storage_root_removal_requires_empty_registration(client):
    _login(client)
    _create_location(client)

    response = client.request(
        "DELETE",
        "/api/v1/admin/storage-roots",
        headers=ADMIN_HEADERS,
        json={"path": str(TEST_LIBRARY)},
    )

    assert response.status_code == 409


def test_create_shelf_scans_books_and_rejects_overlapping_paths(client, db_session):
    shelf_directory = TEST_LIBRARY / "novels"
    shelf_directory.mkdir()
    create_pdf(shelf_directory / "fixed-layout.pdf", pages=3)
    _login(client)
    location = _create_location(client)

    created = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "小说",
            "storage_location_id": location["id"],
            "relative_path": "novels",
            "auto_scan_enabled": True,
            "scan_interval_minutes": 7,
            "scan_after_create": True,
        },
    )

    assert created.status_code == 201, created.text
    shelf = created.json()
    assert shelf["book_count"] == 1
    assert shelf["scan_interval_minutes"] == 7
    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.shelf_id == shelf["id"]
    assert book.page_count == 3
    assert book.chapter_count is None

    overlap = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "重叠书架",
            "storage_location_id": location["id"],
            "relative_path": "novels/subfolder",
        },
    )
    assert overlap.status_code == 409
    assert "重叠" in overlap.json()["detail"]


def test_storage_root_can_be_used_directly_as_shelf(client, db_session):
    create_pdf(TEST_LIBRARY / "root-document.pdf", pages=2)
    _login(client)
    location = _create_location(client)

    created = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "整个挂载目录",
            "storage_location_id": location["id"],
            "relative_path": ".",
            "scan_after_create": True,
        },
    )

    assert created.status_code == 201, created.text
    assert created.json()["resolved_path"] == str(TEST_LIBRARY.resolve())
    assert created.json()["book_count"] == 1
    assert db_session.scalar(select(Book)).shelf_id == created.json()["id"]


def test_rescan_removes_missing_catalog_record_without_deleting_shelf_directory(client, db_session):
    shelf_directory = TEST_LIBRARY / "technical"
    shelf_directory.mkdir()
    source = create_pdf(shelf_directory / "manual.pdf")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "技术资料",
            "storage_location_id": location["id"],
            "relative_path": "technical",
        },
    ).json()
    assert db_session.scalar(select(Book)) is not None

    source.unlink()
    result = client.post(
        f"/api/v1/admin/shelves/{shelf['id']}/scan",
        headers=ADMIN_HEADERS,
    )
    db_session.expire_all()

    assert result.status_code == 200
    assert result.json()["removed"] == 1
    assert db_session.scalar(select(Book)) is None
    assert shelf_directory.is_dir()


def test_update_scan_policy_and_delete_shelf_keeps_original_files(client, db_session):
    shelf_directory = TEST_LIBRARY / "keep-files"
    shelf_directory.mkdir()
    source = create_pdf(shelf_directory / "keep.pdf")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "保留文件",
            "storage_location_id": location["id"],
            "relative_path": "keep-files",
        },
    ).json()

    updated = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"auto_scan_enabled": False, "scan_interval_minutes": 60},
    )
    assert updated.status_code == 200
    assert updated.json()["auto_scan_enabled"] is False
    assert updated.json()["scan_interval_minutes"] == 60

    deleted = client.delete(f"/api/v1/admin/shelves/{shelf['id']}", headers=ADMIN_HEADERS)
    db_session.expire_all()
    assert deleted.status_code == 204
    assert db_session.scalar(select(Shelf)) is None
    assert db_session.scalar(select(Book)) is None
    assert source.is_file()


def test_public_shelves_follow_folders_and_pin_shelf_requires_pin(client, db_session):
    public_directory = TEST_LIBRARY / "public"
    hidden_directory = TEST_LIBRARY / "hidden"
    public_directory.mkdir()
    hidden_directory.mkdir()
    (public_directory / "公开.txt").write_text("第一章 开始\n公开正文", encoding="utf-8")
    (hidden_directory / "秘密.txt").write_text("第一章 开始\n秘密正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)

    public = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "公开书架",
            "storage_location_id": location["id"],
            "relative_path": "public",
        },
    )
    hidden = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "受密码保护的书架",
            "storage_location_id": location["id"],
            "relative_path": "hidden",
            "access_pin": "2580",
        },
    )

    assert public.status_code == 201
    assert hidden.status_code == 201
    assert hidden.json()["pin_configured"] is True
    shelves = client.get("/api/v1/shelves").json()
    assert [shelf["name"] for shelf in shelves] == ["公开书架", "受密码保护的书架"]
    assert len(shelves[0]["books"]) == 1
    assert shelves[1]["locked"] is True
    assert shelves[1]["books"] == []

    wrong = client.post(f"/api/v1/shelves/{hidden.json()['id']}/unlock", json={"pin": "0000"})
    unlocked = client.post(f"/api/v1/shelves/{hidden.json()['id']}/unlock", json={"pin": "2580"})
    hidden_book_id = db_session.scalar(select(Book.id).where(Book.shelf_id == hidden.json()["id"]))
    blocked_book = client.get(f"/api/v1/books/{hidden_book_id}")
    allowed_book = client.get(f"/api/v1/books/{hidden_book_id}", headers={"X-Shelf-Pin": "2580"})

    assert wrong.status_code == 401
    assert unlocked.status_code == 200
    assert unlocked.json()["books"][0]["title"] == "秘密"
    assert blocked_book.status_code == 401
    assert allowed_book.status_code == 200


def test_recent_reading_is_grouped_inside_each_shelf(client):
    first_directory = TEST_LIBRARY / "recent-first"
    second_directory = TEST_LIBRARY / "recent-second"
    first_directory.mkdir()
    second_directory.mkdir()
    (first_directory / "甲书.txt").write_text("第一章 开始\n甲书正文", encoding="utf-8")
    (second_directory / "乙书.txt").write_text("第一章 开始\n乙书正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)

    for name, relative_path in (("甲书架", "recent-first"), ("乙书架", "recent-second")):
        response = client.post(
            "/api/v1/admin/shelves",
            headers=ADMIN_HEADERS,
            json={
                "name": name,
                "storage_location_id": location["id"],
                "relative_path": relative_path,
            },
        )
        assert response.status_code == 201, response.text

    shelves = client.get("/api/v1/shelves").json()
    first_book = shelves[0]["books"][0]
    second_book = shelves[1]["books"][0]
    for book, progression in ((first_book, 0.25), (second_book, 0.75)):
        saved = client.put(
            f"/api/v1/books/{book['id']}/progress/recent-web",
            json={
                "progression": progression,
                "locator_json": {
                    "type": "text",
                    "chapter_index": 0,
                    "chapter_title": "第一章 开始",
                    "chapter_progress": progression,
                },
            },
        )
        assert saved.status_code == 200, saved.text

    refreshed = {shelf["name"]: shelf for shelf in client.get("/api/v1/shelves").json()}

    assert [item["book"]["id"] for item in refreshed["甲书架"]["recent_reading"]] == [first_book["id"]]
    assert [item["book"]["id"] for item in refreshed["乙书架"]["recent_reading"]] == [second_book["id"]]
    assert refreshed["甲书架"]["recent_reading"][0]["progression"] == 0.25
    assert refreshed["乙书架"]["recent_reading"][0]["progression"] == 0.75


def test_shelf_pin_must_be_four_digits(client):
    _login(client)
    location = _create_location(client)

    invalid = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "密码格式错误",
            "storage_location_id": location["id"],
            "relative_path": "bad-pin",
            "access_pin": "12345",
        },
    )

    assert invalid.status_code == 422


def test_existing_public_shelf_password_can_be_set_changed_and_cleared(client, db_session):
    shelf_directory = TEST_LIBRARY / "password-editing"
    shelf_directory.mkdir()
    (shelf_directory / "受保护.txt").write_text("第一章 开始\n正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "可设置密码的普通书架",
            "storage_location_id": location["id"],
            "relative_path": "password-editing",
        },
    ).json()
    book_id = db_session.scalar(select(Book.id).where(Book.shelf_id == shelf["id"]))
    assert client.get("/api/v1/shelves").json()[0]["locked"] is False

    protected = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"access_pin": "1357"},
    )
    locked = client.get("/api/v1/shelves").json()[0]
    assert protected.status_code == 200
    assert protected.json()["pin_configured"] is True
    assert locked["locked"] is True
    assert locked["books"] == []
    assert book_id not in {book["id"] for book in client.get("/api/v1/books").json()}
    assert client.get(f"/api/v1/books/{book_id}").status_code == 401
    assert client.get(f"/api/v1/books/{book_id}", headers={"X-Shelf-Pin": "1357"}).status_code == 200

    changed = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"access_pin": "2468"},
    )
    assert changed.status_code == 200
    assert client.post(f"/api/v1/shelves/{shelf['id']}/unlock", json={"pin": "1357"}).status_code == 401
    assert client.post(f"/api/v1/shelves/{shelf['id']}/unlock", json={"pin": "2468"}).status_code == 200

    cleared = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"access_pin": None},
    )
    unlocked = client.get("/api/v1/shelves").json()[0]
    assert cleared.status_code == 200
    assert cleared.json()["pin_configured"] is False
    assert unlocked["locked"] is False
    assert unlocked["books"][0]["id"] == book_id
    assert book_id in {book["id"] for book in client.get("/api/v1/books").json()}


def test_backend_reset_requires_confirmation_and_keeps_source_files(client, db_session):
    shelf_directory = TEST_LIBRARY / "reset-source"
    shelf_directory.mkdir()
    source = create_pdf(shelf_directory / "keep.pdf")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "待重置书架",
            "storage_location_id": location["id"],
            "relative_path": "reset-source",
        },
    ).json()
    book = db_session.scalar(select(Book).where(Book.shelf_id == shelf["id"]))
    db_session.add(ReadingProgress(book_id=book.id, device_id="reset-test", progression=0.5))
    db_session.commit()

    rejected = client.post(
        "/api/v1/admin/reset",
        headers=ADMIN_HEADERS,
        json={"confirmation": "WRONG"},
    )
    reset = client.post(
        "/api/v1/admin/reset",
        headers=ADMIN_HEADERS,
        json={"confirmation": "RESET"},
    )
    db_session.expire_all()

    assert rejected.status_code == 422
    assert reset.status_code == 200
    assert reset.json()["books_deleted"] == 1
    assert db_session.scalar(select(Book)) is None
    assert db_session.scalar(select(Shelf)) is None
    assert db_session.scalar(select(StorageLocation)) is None
    assert db_session.scalar(select(ReadingProgress)) is None
    assert source.is_file()


def test_admin_lists_each_shelf_books_and_resplits_one_book(client, db_session):
    shelf_directory = TEST_LIBRARY / "split-settings"
    shelf_directory.mkdir()
    source = shelf_directory / "长篇.txt"
    source.write_text("第一章 开始\n" + ("甲" * 2300), encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "拆分设置书架",
            "storage_location_id": location["id"],
            "relative_path": "split-settings",
        },
    ).json()

    books = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books")
    assert books.status_code == 200
    assert len(books.json()) == 1
    book = books.json()[0]
    assert book["chapter_split_mode"] == "auto"

    fixed = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "fixed", "segment_size": 1000},
    )
    assert fixed.status_code == 200, fixed.text
    assert fixed.json()["chapter_split_mode"] == "fixed"
    assert fixed.json()["chapter_split_config"] == {"segment_size": 1000}
    assert fixed.json()["chapter_count"] == 3
    assert fixed.json()["chapter_split_revision"] == 1

    public_book = client.get(f"/api/v1/books/{book['id']}")
    assert public_book.json()["content_version"].endswith(":1")

    rescanned = client.post(
        f"/api/v1/admin/shelves/{shelf['id']}/scan",
        headers=ADMIN_HEADERS,
    )
    db_session.expire_all()
    persisted = db_session.get(Book, book["id"])
    assert rescanned.status_code == 200
    assert persisted.chapter_split_mode == "fixed"
    assert persisted.chapter_split_config_json == {"segment_size": 1000}
    assert persisted.chapter_count == 3

    single = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "single", "segment_size": 12000},
    )
    assert single.status_code == 200
    assert single.json()["chapter_count"] == 1
    assert single.json()["chapter_split_revision"] == 2


def test_resplit_relocates_source_only_with_unique_fingerprint_inside_same_shelf(client, db_session):
    shelf_directory = TEST_LIBRARY / "relocated-split-source"
    shelf_directory.mkdir()
    source = shelf_directory / "原位置.txt"
    source.write_text("第一章 开始\n" + ("甲" * 2300), encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "同书架移动",
            "storage_location_id": location["id"],
            "relative_path": "relocated-split-source",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    nested = shelf_directory / "新目录"
    nested.mkdir()
    relocated = source.rename(nested / "已移动.txt")
    response = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "fixed", "segment_size": 1000},
    )
    db_session.expire_all()

    assert response.status_code == 200, response.text
    assert response.json()["file_path"] == str(relocated.resolve())
    assert response.json()["chapter_count"] == 3
    assert db_session.get(Book, book["id"]).file_path == str(relocated.resolve())


def test_resplit_never_recovers_source_from_outside_current_shelf(client, db_session):
    shelf_directory = TEST_LIBRARY / "bounded-split-source"
    shelf_directory.mkdir()
    source = shelf_directory / "原位置.txt"
    source.write_text("第一章 开始\n正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "禁止跨书架恢复",
            "storage_location_id": location["id"],
            "relative_path": "bounded-split-source",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    outside = TEST_LIBRARY / "other-shelf"
    outside.mkdir()
    outside_source = source.rename(outside / source.name)
    catalog_book = db_session.get(Book, book["id"])
    catalog_book.file_path = str(outside_source.resolve())
    db_session.commit()
    book["file_path"] = str(outside_source.resolve())
    response = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "single", "segment_size": 12000},
    )
    db_session.expire_all()
    persisted = db_session.get(Book, book["id"])

    assert response.status_code == 422
    assert "超出当前书架目录" in response.json()["detail"]
    assert "重新扫描" in response.json()["detail"]
    assert persisted.file_path == book["file_path"]
    assert persisted.chapter_split_mode == "auto"
    assert persisted.chapter_split_revision == 0


def test_resplit_rejects_ambiguous_fingerprint_matches_inside_shelf(client, db_session):
    shelf_directory = TEST_LIBRARY / "ambiguous-split-source"
    shelf_directory.mkdir()
    content = "第一章 开始\n" + ("丙" * 1200)
    source = shelf_directory / "原位置.txt"
    source.write_text(content, encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "拒绝歧义恢复",
            "storage_location_id": location["id"],
            "relative_path": "ambiguous-split-source",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    source.unlink()
    (shelf_directory / "副本一.txt").write_text(content, encoding="utf-8")
    (shelf_directory / "副本二.txt").write_text(content, encoding="utf-8")
    response = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "single", "segment_size": 12000},
    )
    db_session.expire_all()
    persisted = db_session.get(Book, book["id"])

    assert response.status_code == 422
    assert "不存在或已移动" in response.json()["detail"]
    assert persisted.file_path == book["file_path"]
    assert persisted.chapter_split_mode == "auto"


def test_resplit_reports_source_permission_failure(client, monkeypatch):
    shelf_directory = TEST_LIBRARY / "permission-split-source"
    shelf_directory.mkdir()
    (shelf_directory / "权限.txt").write_text("第一章 开始\n正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "权限错误提示",
            "storage_location_id": location["id"],
            "relative_path": "permission-split-source",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    def reject_read(*args, **kwargs):  # noqa: ARG001
        raise PermissionError("simulated NAS ACL failure")

    monkeypatch.setattr(shelf_scanner, "process_book_file", reject_read)
    response = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "single", "segment_size": 12000},
    )

    assert response.status_code == 422
    assert "检查 NAS 与容器目录权限" in response.json()["detail"]
    assert "重新扫描" in response.json()["detail"]


def test_rescan_preserves_book_identity_and_split_settings_after_same_shelf_move(client, db_session):
    shelf_directory = TEST_LIBRARY / "rescan-relocation"
    shelf_directory.mkdir()
    source = shelf_directory / "扫描前.txt"
    source.write_text("第一章 开始\n" + ("乙" * 2300), encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "扫描保留记录",
            "storage_location_id": location["id"],
            "relative_path": "rescan-relocation",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]
    configured = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "fixed", "segment_size": 1000},
    )
    assert configured.status_code == 200

    nested = shelf_directory / "归档"
    nested.mkdir()
    relocated = source.rename(nested / "扫描后.txt")
    rescanned = client.post(f"/api/v1/admin/shelves/{shelf['id']}/scan", headers=ADMIN_HEADERS)
    db_session.expire_all()
    persisted = db_session.get(Book, book["id"])

    assert rescanned.status_code == 200, rescanned.text
    assert rescanned.json()["updated"] == 0
    assert rescanned.json()["unchanged"] == 1
    assert rescanned.json()["imported"] == 0
    assert rescanned.json()["removed"] == 0
    assert persisted.file_path == str(relocated.resolve())
    assert persisted.chapter_split_mode == "fixed"
    assert persisted.chapter_split_config_json == {"segment_size": 1000}
    assert persisted.chapter_split_revision == 1


def test_pdf_rejects_chapter_split_settings(client):
    shelf_directory = TEST_LIBRARY / "pdf-settings"
    shelf_directory.mkdir()
    create_pdf(shelf_directory / "fixed.pdf")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "PDF 设置书架",
            "storage_location_id": location["id"],
            "relative_path": "pdf-settings",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    response = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "auto", "segment_size": 12000},
    )
    assert response.status_code == 422
    assert "PDF" in response.json()["detail"]


def test_book_can_be_resplit_with_classical_mode(client):
    shelf_directory = TEST_LIBRARY / "classical-split"
    shelf_directory.mkdir()
    (shelf_directory / "古籍.txt").write_text(
        "卷一 少年游\n" + ("甲" * 1200) + "\n学而第一\n" + ("乙" * 1200),
        encoding="utf-8",
    )
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "古籍拆分书架",
            "storage_location_id": location["id"],
            "relative_path": "classical-split",
        },
    ).json()
    book = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()[0]

    changed = client.patch(
        f"/api/v1/admin/books/{book['id']}/chapter-split",
        headers=ADMIN_HEADERS,
        json={"mode": "classical"},
    )
    assert changed.status_code == 200, changed.text
    assert changed.json()["chapter_split_mode"] == "classical"
    assert changed.json()["chapter_count"] == 2
    assert changed.json()["chapter_split_revision"] == 1


def test_shelf_book_can_be_hidden_and_restored(client, db_session):
    shelf_directory = TEST_LIBRARY / "visibility"
    shelf_directory.mkdir()
    (shelf_directory / "可见.txt").write_text("第一章 开始\n可见正文", encoding="utf-8")
    (shelf_directory / "待隐藏.txt").write_text("第一章 开始\n待隐藏正文", encoding="utf-8")
    _login(client)
    location = _create_location(client)
    shelf = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "可见性书架",
            "storage_location_id": location["id"],
            "relative_path": "visibility",
        },
    ).json()

    books = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()
    assert len(books) == 2
    target = next(book for book in books if book["filename"] == "待隐藏.txt")
    other = next(book for book in books if book["filename"] == "可见.txt")
    assert target["shelf_visible"] is True

    hidden = client.patch(
        f"/api/v1/admin/books/{target['id']}/shelf-visibility",
        headers=ADMIN_HEADERS,
        json={"shelf_visible": False},
    )
    assert hidden.status_code == 200
    assert hidden.json()["shelf_visible"] is False

    public_shelf = client.get("/api/v1/shelves").json()[0]
    assert [book["id"] for book in public_shelf["books"]] == [other["id"]]
    assert public_shelf["book_count"] == 1
    flat = client.get("/api/v1/books").json()
    assert target["id"] not in {book["id"] for book in flat}
    assert other["id"] in {book["id"] for book in flat}

    # 管理后台仍然能看到被隐藏的书，以便恢复。
    admin_books = client.get(f"/api/v1/admin/shelves/{shelf['id']}/books").json()
    assert len(admin_books) == 2
    assert next(book for book in admin_books if book["id"] == target["id"])["shelf_visible"] is False

    restored = client.patch(
        f"/api/v1/admin/books/{target['id']}/shelf-visibility",
        headers=ADMIN_HEADERS,
        json={"shelf_visible": True},
    )
    assert restored.status_code == 200
    public_shelf = client.get("/api/v1/shelves").json()[0]
    assert {book["id"] for book in public_shelf["books"]} == {target["id"], other["id"]}
    assert public_shelf["book_count"] == 2


def test_scan_interval_units_are_persisted_and_converted(client):
    _login(client)
    location = _create_location(client)
    created = client.post(
        "/api/v1/admin/shelves",
        headers=ADMIN_HEADERS,
        json={
            "name": "间隔单位书架",
            "storage_location_id": location["id"],
            "relative_path": "interval-units",
            "scan_interval_value": 2,
            "scan_interval_unit": "days",
        },
    )

    assert created.status_code == 201, created.text
    shelf = created.json()
    assert shelf["scan_interval_value"] == 2
    assert shelf["scan_interval_unit"] == "days"
    assert shelf["scan_interval_minutes"] == 2 * 24 * 60

    updated = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"scan_interval_value": 3, "scan_interval_unit": "weeks"},
    )
    assert updated.status_code == 200
    assert updated.json()["scan_interval_value"] == 3
    assert updated.json()["scan_interval_unit"] == "weeks"
    assert updated.json()["scan_interval_minutes"] == 3 * 7 * 24 * 60

    legacy = client.patch(
        f"/api/v1/admin/shelves/{shelf['id']}",
        headers=ADMIN_HEADERS,
        json={"scan_interval_minutes": 30},
    )
    assert legacy.status_code == 200
    assert legacy.json()["scan_interval_value"] == 30
    assert legacy.json()["scan_interval_unit"] == "minutes"
    assert legacy.json()["scan_interval_minutes"] == 30
