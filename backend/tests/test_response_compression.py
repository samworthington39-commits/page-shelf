from sqlalchemy import select

from app.models import Book, Chapter
from conftest import TEST_LIBRARY


def test_large_chapter_response_is_gzip_compressed(client, db_session):
    (TEST_LIBRARY / "compressed.txt").write_text(
        "第一章 开始\n" + "这是一段适合压缩的正文。" * 4_000,
        encoding="utf-8",
    )
    assert client.post("/api/v1/library/scan").status_code == 200
    book = db_session.scalar(select(Book))
    chapter = db_session.scalar(select(Chapter))
    assert book is not None and chapter is not None

    response = client.get(
        f"/api/v1/books/{book.id}/chapters/{chapter.id}",
        headers={"Accept-Encoding": "gzip"},
    )

    assert response.status_code == 200
    assert response.headers.get("content-encoding") == "gzip"
    assert len(response.content) > 10_000
