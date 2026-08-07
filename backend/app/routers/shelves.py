from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from ..db import get_db
from ..models import Book, ReadingProgress, Shelf
from ..schemas import BookSummary, PublicShelfView, RecentReadingView, ShelfUnlockRequest
from ..services.shelf_access import require_shelf_pin
from ..services.admin_auth import require_mobile_session


router = APIRouter(prefix="/shelves", tags=["shelves"])
DEFAULT_SHELF_ID = "__default__"
RECENT_READING_LIMIT = 6


def _recent_reading(session: Session, books: list[Book]) -> list[RecentReadingView]:
    if not books:
        return []
    books_by_id = {book.id: book for book in books}
    ranked_progress = (
        select(
            ReadingProgress.id.label("progress_id"),
            func.row_number()
            .over(
                partition_by=ReadingProgress.book_id,
                order_by=(ReadingProgress.updated_at.desc(), ReadingProgress.id.desc()),
            )
            .label("book_rank"),
        )
        .where(ReadingProgress.book_id.in_(books_by_id))
        .subquery()
    )
    progresses = session.scalars(
        select(ReadingProgress)
        .join(ranked_progress, ranked_progress.c.progress_id == ReadingProgress.id)
        .where(ranked_progress.c.book_rank == 1)
        .order_by(ReadingProgress.updated_at.desc(), ReadingProgress.id.desc())
        .limit(RECENT_READING_LIMIT)
    )
    recent: list[RecentReadingView] = []
    for progress in progresses:
        recent.append(
            RecentReadingView(
                book=BookSummary.model_validate(books_by_id[progress.book_id]),
                progression=progress.progression,
                locator_json=progress.locator_json,
                updated_at=progress.updated_at,
            )
        )
    return recent


def _shelf_view(session: Session, shelf: Shelf, *, unlocked: bool) -> PublicShelfView:
    books = sorted(
        (book for book in shelf.books if book.shelf_visible),
        key=lambda book: (book.title, book.id),
    )
    locked = shelf.access_pin_hash is not None and not unlocked
    return PublicShelfView(
        id=shelf.id,
        name=shelf.name,
        locked=locked,
        book_count=len(books),
        total_bytes=sum(book.file_size for book in books),
        books=[] if locked else [BookSummary.model_validate(book) for book in books],
        recent_reading=[] if locked else _recent_reading(session, books),
    )


@router.get("", response_model=list[PublicShelfView], dependencies=[Depends(require_mobile_session)])
def list_shelves(session: Session = Depends(get_db)) -> list[PublicShelfView]:
    shelves = list(
        session.scalars(
            select(Shelf)
            .options(selectinload(Shelf.books), selectinload(Shelf.storage_location))
            .order_by(Shelf.created_at, Shelf.id)
        )
    )
    result = [_shelf_view(session, shelf, unlocked=shelf.access_pin_hash is None) for shelf in shelves]
    loose_books = list(
        session.scalars(
            select(Book)
            .where(Book.shelf_id.is_(None), Book.shelf_visible.is_(True))
            .order_by(Book.title, Book.id)
        )
    )
    if loose_books:
        result.insert(
            0,
            PublicShelfView(
                id=DEFAULT_SHELF_ID,
                name="默认书架",
                locked=False,
                book_count=len(loose_books),
                total_bytes=sum(book.file_size for book in loose_books),
                books=[BookSummary.model_validate(book) for book in loose_books],
                recent_reading=_recent_reading(session, loose_books),
            ),
        )
    return result


@router.post(
    "/{shelf_id}/unlock",
    response_model=PublicShelfView,
    dependencies=[Depends(require_mobile_session)],
)
def unlock_shelf(
    shelf_id: str,
    payload: ShelfUnlockRequest,
    session: Session = Depends(get_db),
) -> PublicShelfView:
    if shelf_id == DEFAULT_SHELF_ID:
        raise HTTPException(status_code=400, detail="默认书架无需解锁")
    shelf = session.get(Shelf, shelf_id)
    if shelf is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="书架不存在")
    require_shelf_pin(shelf, payload.pin)
    return _shelf_view(session, shelf, unlocked=True)
