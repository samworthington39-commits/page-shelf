from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Path, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Book, ReadingProgress
from ..schemas import ProgressResponse, ProgressUpsert
from ..services.shelf_access import require_book_access
from ..services.admin_auth import require_mobile_session


router = APIRouter(prefix="/books", tags=["progress"])
DeviceId = Annotated[str, Path(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9._:-]+$")]


def _book_or_404(session: Session, book_id: str, shelf_pin: str | None = None) -> Book:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="书籍不存在")
    require_book_access(book, shelf_pin)
    return book


@router.get(
    "/{book_id}/progress",
    response_model=ProgressResponse,
    dependencies=[Depends(require_mobile_session)],
)
def get_latest_progress(
    book_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> ReadingProgress:
    _book_or_404(session, book_id, shelf_pin)
    progress = session.scalar(
        select(ReadingProgress)
        .where(ReadingProgress.book_id == book_id)
        .order_by(ReadingProgress.updated_at.desc(), ReadingProgress.id.desc())
        .limit(1)
    )
    if progress is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="尚无阅读进度")
    return progress


@router.get(
    "/{book_id}/progress/{device_id}",
    response_model=ProgressResponse,
    dependencies=[Depends(require_mobile_session)],
)
def get_progress(
    book_id: str,
    device_id: DeviceId,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> ReadingProgress:
    _book_or_404(session, book_id, shelf_pin)
    progress = session.scalar(
        select(ReadingProgress).where(
            ReadingProgress.book_id == book_id,
            ReadingProgress.device_id == device_id,
        )
    )
    if progress is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="尚无阅读进度")
    return progress


@router.put(
    "/{book_id}/progress/{device_id}",
    response_model=ProgressResponse,
    dependencies=[Depends(require_mobile_session)],
)
def save_progress(
    book_id: str,
    device_id: DeviceId,
    payload: ProgressUpsert,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> ReadingProgress:
    book = _book_or_404(session, book_id, shelf_pin)
    page_index = payload.page_index
    page_count = payload.page_count
    if book.format == "pdf":
        if page_index is None or page_count is None:
            raise HTTPException(status_code=422, detail="PDF 进度必须包含页码和总页数")
        authoritative_count = book.page_count or page_count
        if page_count != authoritative_count:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="PDF 页数已变化，请重新打开文件")
        if page_index >= authoritative_count:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="页码超出 PDF 范围")
        progression = (page_index + 1) / authoritative_count
        locator = payload.locator_json or {
            "type": "pdf",
            "page_index": page_index,
            "page": page_index + 1,
        }
        page_count = authoritative_count
    else:
        if payload.progression is None or payload.locator_json is None:
            raise HTTPException(status_code=422, detail="文字阅读进度必须包含 progression 和 locator_json")
        if payload.locator_json.get("type") not in {"text", "epub"}:
            raise HTTPException(status_code=422, detail="文字阅读定位器类型无效")
        progression = payload.progression
        locator = payload.locator_json

    progress = session.scalar(
        select(ReadingProgress).where(
            ReadingProgress.book_id == book_id,
            ReadingProgress.device_id == device_id,
        )
    )
    if progress is None:
        progress = ReadingProgress(book_id=book_id, device_id=device_id)
        session.add(progress)
    progress.page_index = page_index
    progress.page_count = page_count
    progress.progression = progression
    progress.locator_json = locator
    session.commit()
    session.refresh(progress)
    return progress
