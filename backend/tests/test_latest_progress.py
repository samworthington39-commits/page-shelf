from sqlalchemy import select

from app.models import Book
from conftest import TEST_LIBRARY


def test_latest_progress_returns_newest_device_record(client, db_session):
    (TEST_LIBRARY / "novel.txt").write_text("第一章\n正文", encoding="utf-8")
    client.post("/api/v1/library/scan")
    book = db_session.scalar(select(Book))

    client.put(
        f"/api/v1/books/{book.id}/progress/device-a",
        json={
            "progression": 0.2,
            "locator_json": {"type": "text", "chapter_id": book.chapters[0].id},
        },
    )
    client.put(
        f"/api/v1/books/{book.id}/progress/device-b",
        json={
            "progression": 0.7,
            "locator_json": {"type": "text", "chapter_id": book.chapters[0].id},
        },
    )

    response = client.get(f"/api/v1/books/{book.id}/progress")

    assert response.status_code == 200
    assert response.json()["device_id"] == "device-b"
    assert response.json()["progression"] == 0.7
