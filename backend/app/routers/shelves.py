from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from ..db import get_db
from ..models import Book, Shelf
from ..schemas import BookSummary, PublicShelfView, ShelfUnlockRequest
from ..services.shelf_access import require_shelf_pin
from ..services.admin_auth import require_mobile_session


router = APIRouter(prefix="/shelves", tags=["shelves"])
DEFAULT_SHELF_ID = "__default__"


def _shelf_view(session: Session, shelf: Shelf, *, unlocked: bool) -> PublicShelfView:  # noqa: ARG001
    books = sorted(shelf.books, key=lambda book: (book.title, book.id))
    locked = shelf.access_pin_hash is not None and not unlocked
    return PublicShelfView(
        id=shelf.id,
        name=shelf.name,
        is_hidden=shelf.is_hidden,
        locked=locked,
        book_count=len(books),
        total_bytes=sum(book.file_size for book in books),
        books=[] if locked else [BookSummary.model_validate(book) for book in books],
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
        session.scalars(select(Book).where(Book.shelf_id.is_(None)).order_by(Book.title, Book.id))
    )
    if loose_books:
        result.insert(
            0,
            PublicShelfView(
                id=DEFAULT_SHELF_ID,
                name="默认书架",
                is_hidden=False,
                locked=False,
                book_count=len(loose_books),
                total_bytes=sum(book.file_size for book in loose_books),
                books=[BookSummary.model_validate(book) for book in loose_books],
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
