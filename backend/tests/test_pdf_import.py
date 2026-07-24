from __future__ import annotations

import pytest
from sqlalchemy import func, select

from app.models import Book, Chapter
from conftest import TEST_LIBRARY
from pdf_factory import create_pdf


def _scan(client):
    response = client.post("/api/v1/library/scan")
    assert response.status_code == 200, response.text
    return response.json()


def test_pdf_metadata_pages_and_no_chapters(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf", pages=5)

    result = _scan(client)

    assert result["imported"] == 1
    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.title == "测试 PDF"
    assert book.author == "测试作者"
    assert book.subject == "固定版式阅读"
    assert book.keywords == "PDF,阅读器"
    assert book.page_count == 5
    assert book.chapter_count is None
    assert book.password_required is False
    assert book.can_open is True
    assert db_session.scalar(select(func.count()).select_from(Chapter)) == 0


def test_pdf_without_bookmarks_imports_normally(client, db_session):
    create_pdf(TEST_LIBRARY / "plain.pdf", bookmarks=False)
    _scan(client)

    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.pdf_navigation_json == []
    response = client.get(f"/api/v1/books/{book.id}/pdf-navigation")
    assert response.status_code == 200
    assert response.json() == {"book_id": book.id, "page_count": 3, "items": []}


def test_native_bookmarks_are_navigation_not_chapters(client, db_session):
    create_pdf(TEST_LIBRARY / "bookmarked.pdf", pages=4, bookmarks=True)
    _scan(client)

    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.pdf_navigation_json[0]["page_index"] == 0
    assert db_session.scalar(select(func.count()).select_from(Chapter)) == 0
    payload = client.get(f"/api/v1/books/{book.id}/pdf-navigation").json()
    assert payload["items"][0]["title"] == "前言"
    assert payload["items"][0]["page"] == 1
    assert payload["items"][0]["children"][0] == {"title": "说明", "page": 2, "children": []}


def test_encrypted_and_corrupt_pdf_do_not_abort_scan(client, db_session):
    create_pdf(TEST_LIBRARY / "normal.pdf")
    create_pdf(TEST_LIBRARY / "locked.pdf", password="secret")
    (TEST_LIBRARY / "broken.pdf").write_bytes(b"not a pdf")

    result = _scan(client)

    assert result["discovered"] == 3
    assert result["imported"] == 3
    assert result["failed"] == 0
    books = {book.file_path.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]: book for book in db_session.scalars(select(Book))}
    locked = books["locked.pdf"]
    assert locked.password_required is True
    assert locked.can_open is False
    assert locked.page_count is None
    assert locked.parse_status == "warning"
    assert locked.parse_warnings_json
    assert books["broken.pdf"].can_open is False


def test_pdf_toc_is_successful_empty_response(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf")
    _scan(client)
    book = db_session.scalar(select(Book))

    response = client.get(f"/api/v1/books/{book.id}/toc")

    assert response.status_code == 200
    assert response.json() == {
        "book_id": book.id,
        "format": "pdf",
        "chapter_supported": False,
        "items": [],
    }
    assert client.get(f"/api/v1/books/{book.id}/chapters/anything").status_code == 404


def test_pdf_capabilities_exposed_on_book(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf")
    _scan(client)
    book = db_session.scalar(select(Book))
    payload = client.get(f"/api/v1/books/{book.id}").json()

    assert payload["chapter_count"] is None
    assert payload["capabilities"] == {
        "chapters": False,
        "reflowable_text": False,
        "font_settings": False,
        "page_navigation": True,
        "zoom": True,
        "offline_download": True,
        "progress_sync": True,
    }


def test_database_session_rejects_pdf_chapter_rows(client, db_session):
    create_pdf(TEST_LIBRARY / "book.pdf")
    _scan(client)
    book = db_session.scalar(select(Book))
    db_session.add(Chapter(book_id=book.id, title="不允许的章节", position=0, body=""))

    with pytest.raises(ValueError, match="PDF cannot be written"):
        db_session.commit()
    db_session.rollback()
