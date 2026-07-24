from __future__ import annotations

import base64
from pathlib import Path

import fitz
from sqlalchemy import select

from app.models import Book
from app.services.library_scanner import automatic_book_title
from conftest import TEST_LIBRARY


ADMIN_HEADERS = {"X-Page-Shelf-Admin": "1"}


def _admin_login(client) -> None:
    response = client.post("/api/v1/admin/session", json={"password": "test-admin-password"})
    assert response.status_code == 204


def _png_bytes() -> bytes:
    document = fitz.open()
    try:
        page = document.new_page(width=120, height=180)
        page.draw_rect(page.rect, fill=(0.2, 0.4, 0.3))
        return page.get_pixmap(alpha=False).tobytes("png")
    finally:
        document.close()


def test_automatic_title_priority_and_non_empty_book_marks(tmp_path: Path):
    directory = tmp_path / "下载归档《父目录书名》2026"
    directory.mkdir()

    assert automatic_book_title(directory / "《文件书名》超长发布名.txt", "内部标题") == "文件书名"
    assert automatic_book_title(directory / "普通文件.epub", "内部标题") == "内部标题"
    assert automatic_book_title(directory / "001.txt") == "父目录书名"
    assert automatic_book_title(directory / "《 》《第二组》.txt") == "第二组"
    assert automatic_book_title(tmp_path / "ordinary.txt") == "ordinary"


def test_scan_scrapes_filename_title_and_metadata_override_survives_rescan(client, db_session):
    source = TEST_LIBRARY / "《中医许阳》完本含番外(2025_9_3) -- 发布归档.txt"
    source.write_text("第一章 开始\n正文", encoding="utf-8")

    scan = client.post("/api/v1/library/scan")
    assert scan.status_code == 200
    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.title == "中医许阳"

    _admin_login(client)
    updated = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"title": "手工书名", "author": "手工作者"},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["title"] == "手工书名"
    assert updated.json()["filename"] == source.name
    assert updated.json()["directory"] == str(TEST_LIBRARY.resolve())

    source.write_text("第一章 开始\n修改后的正文", encoding="utf-8")
    rescanned = client.post("/api/v1/library/scan")
    assert rescanned.status_code == 200
    db_session.expire_all()
    persisted = db_session.get(Book, book.id)
    assert persisted is not None
    assert persisted.title == "手工书名"
    assert persisted.author == "手工作者"

    reset = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"reset_title": True, "reset_author": True},
    )
    assert reset.status_code == 200, reset.text
    assert reset.json()["title"] == "中医许阳"
    assert reset.json()["author"] is None


def test_unchanged_migrated_row_is_rescraped(client, db_session):
    source = TEST_LIBRARY / "《迁移后的书名》超长归档发布文件.txt"
    source.write_text("正文", encoding="utf-8")
    assert client.post("/api/v1/library/scan").status_code == 200
    book = db_session.scalar(select(Book))
    assert book is not None
    book.title = source.stem
    book.metadata_overrides_json = {}
    db_session.commit()

    rescanned = client.post("/api/v1/library/scan")

    assert rescanned.status_code == 200
    assert rescanned.json()["unchanged"] == 1
    db_session.expire_all()
    assert db_session.get(Book, book.id).title == "迁移后的书名"


def test_metadata_update_rejects_empty_title_and_conflicting_actions(client, db_session):
    (TEST_LIBRARY / "book.txt").write_text("正文", encoding="utf-8")
    assert client.post("/api/v1/library/scan").status_code == 200
    book = db_session.scalar(select(Book))
    assert book is not None
    _admin_login(client)

    empty = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"title": "   "},
    )
    conflicting = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"title": "新书名", "reset_title": True},
    )

    assert empty.status_code == 422
    assert conflicting.status_code == 422


def test_manual_cover_upload_get_and_remove(client, db_session):
    source = TEST_LIBRARY / "covered.txt"
    source.write_text("正文", encoding="utf-8")
    assert client.post("/api/v1/library/scan").status_code == 200
    book = db_session.scalar(select(Book))
    assert book is not None
    _admin_login(client)

    uploaded = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={
            "cover_base64": base64.b64encode(_png_bytes()).decode("ascii"),
            "cover_filename": "cover.png",
        },
    )

    assert uploaded.status_code == 200, uploaded.text
    assert uploaded.json()["cover_source"] == "manual"
    assert uploaded.json()["cover_url"] == f"/api/v1/admin/books/{book.id}/cover"
    cover = client.get(uploaded.json()["cover_url"])
    assert cover.status_code == 200
    assert cover.content.startswith(b"\xff\xd8")
    db_session.expire_all()
    persisted = db_session.get(Book, book.id)
    assert persisted is not None
    assert Path(persisted.cover_path or "").name == f"manual-{book.id}.jpg"

    source.write_text("修改后正文", encoding="utf-8")
    assert client.post("/api/v1/library/scan").status_code == 200
    db_session.expire_all()
    assert Path(db_session.get(Book, book.id).cover_path).name == f"manual-{book.id}.jpg"

    removed = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"remove_cover": True},
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["cover_source"] == "generated"
    assert client.get(removed.json()["cover_url"]).status_code == 200


def test_metadata_upload_route_has_scoped_larger_request_limit(client, db_session):
    (TEST_LIBRARY / "limits.txt").write_text("正文", encoding="utf-8")
    assert client.post("/api/v1/library/scan").status_code == 200
    book = db_session.scalar(select(Book))
    assert book is not None
    _admin_login(client)
    larger_than_default = "!" * 1_100_000

    metadata_response = client.patch(
        f"/api/v1/admin/books/{book.id}/metadata",
        headers=ADMIN_HEADERS,
        json={"cover_base64": larger_than_default, "cover_filename": "cover.png"},
    )
    ordinary_response = client.post(
        "/api/v1/admin/session",
        json={"password": larger_than_default},
    )

    assert metadata_response.status_code == 422
    assert "Base64" in metadata_response.json()["detail"]
    assert ordinary_response.status_code == 413
