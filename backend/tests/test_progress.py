from __future__ import annotations

from sqlalchemy import select

from app.models import Book
from conftest import TEST_LIBRARY
from pdf_factory import create_pdf


def test_pdf_page_progress_is_saved_and_restored(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf", pages=10)
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))

    saved = client.put(
        f"/api/v1/books/{book.id}/progress/device-a",
        json={"page_index": 4, "page_count": 10, "locator_json": {"view": "continuous"}},
    )
    restored = client.get(f"/api/v1/books/{book.id}/progress/device-a")

    assert saved.status_code == 200
    assert saved.json()["page_index"] == 4
    assert saved.json()["progression"] == 0.5
    assert restored.status_code == 200
    assert restored.json()["page_index"] == 4
    assert restored.json()["locator_json"] == {"view": "continuous"}


def test_progress_rejects_stale_page_count(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf", pages=3)
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))

    response = client.put(
        f"/api/v1/books/{book.id}/progress/device-a",
        json={"page_index": 1, "page_count": 4},
    )

    assert response.status_code == 409


def test_txt_locator_progress_is_saved_and_restored(client, db_session):
    (TEST_LIBRARY / "novel.txt").write_text("第1章 开始\n第一段\n第2章 继续\n第二段", encoding="utf-8")
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))
    chapter = book.chapters[1]
    locator = {
        "type": "text",
        "chapter_id": chapter.id,
        "chapter_index": 1,
        "char_offset": 3,
        "view": "paged",
        "font_size_sp": 19,
    }

    saved = client.put(
        f"/api/v1/books/{book.id}/progress/device-text",
        json={"progression": 0.55, "locator_json": locator},
    )
    restored = client.get(f"/api/v1/books/{book.id}/progress/device-text")

    assert saved.status_code == 200
    assert saved.json()["page_index"] is None
    assert saved.json()["progression"] == 0.55
    assert restored.status_code == 200
    assert restored.json()["locator_json"] == locator


def test_progress_rejects_oversized_locator_and_invalid_device_id(client, db_session):
    (TEST_LIBRARY / "limited.txt").write_text("第一章 开始\n正文", encoding="utf-8")
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))

    oversized = client.put(
        f"/api/v1/books/{book.id}/progress/device-a",
        json={"progression": 0.2, "locator_json": {"type": "text", "padding": "x" * 20_000}},
    )
    invalid_device = client.put(
        f"/api/v1/books/{book.id}/progress/device%20with%20spaces",
        json={"progression": 0.2, "locator_json": {"type": "text"}},
    )

    assert oversized.status_code == 422
    assert invalid_device.status_code == 422
