from __future__ import annotations

import os

from sqlalchemy import select

from app.models import Book
from conftest import TEST_LIBRARY
from pdf_factory import create_pdf


def test_pdf_file_supports_range_etag_and_last_modified(client, db_session):
    source = create_pdf(TEST_LIBRARY / "large.pdf", pages=20)
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))

    response = client.get(f"/api/v1/books/{book.id}/file", headers={"Range": "bytes=10-29"})

    assert response.status_code == 206
    assert response.content == source.read_bytes()[10:30]
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["content-range"] == f"bytes 10-29/{source.stat().st_size}"
    assert response.headers["content-length"] == "20"
    assert response.headers["etag"] == f'"{book.fingerprint}"'
    assert "last-modified" in response.headers


def test_range_resume_and_invalid_range(client, db_session):
    source = create_pdf(TEST_LIBRARY / "resume.pdf")
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))
    data = source.read_bytes()

    resumed = client.get(f"/api/v1/books/{book.id}/file", headers={"Range": "bytes=100-"})
    invalid = client.get(f"/api/v1/books/{book.id}/file", headers={"Range": f"bytes={len(data)}-"})

    assert resumed.status_code == 206
    assert resumed.content == data[100:]
    assert invalid.status_code == 416
    assert invalid.headers["content-range"] == f"bytes */{len(data)}"


def test_etag_and_last_modified_support_conditional_get(client, db_session):
    create_pdf(TEST_LIBRARY / "conditional.pdf")
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))
    initial = client.get(f"/api/v1/books/{book.id}/file")

    by_etag = client.get(
        f"/api/v1/books/{book.id}/file",
        headers={"If-None-Match": initial.headers["etag"]},
    )
    by_date = client.get(
        f"/api/v1/books/{book.id}/file",
        headers={"If-Modified-Since": initial.headers["last-modified"]},
    )

    assert by_etag.status_code == 304
    assert by_date.status_code == 304
    assert by_etag.content == b""
    assert by_date.content == b""


def test_file_change_updates_fingerprint_for_redownload_prompt(client, db_session):
    source = create_pdf(TEST_LIBRARY / "changing.pdf", pages=1)
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))
    original_fingerprint = book.fingerprint

    create_pdf(source, pages=2)
    os.utime(source, None)
    result = client.post("/api/v1/library/scan").json()
    db_session.expire_all()
    changed = db_session.get(Book, book.id)

    assert result["updated"] == 1
    assert changed.fingerprint != original_fingerprint
    assert changed.page_count == 2
