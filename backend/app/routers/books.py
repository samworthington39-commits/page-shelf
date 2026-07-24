from pathlib import Path

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import FileResponse, Response
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..models import Book, Chapter, Shelf
from ..schemas import (
    BookDetail,
    BookSummary,
    ChapterResponse,
    PdfNavigationItem,
    PdfNavigationResponse,
    TocItem,
    TocResponse,
)
from ..services.file_streamer import stream_file
from ..services.admin_auth import require_mobile_session
from ..services.cover_service import ensure_cover
from ..services.shelf_scanner import resolved_shelf_path
from ..services.shelf_access import require_book_access


router = APIRouter(prefix="/books", tags=["books"])


def _file_within(path_value: str, root: Path) -> Path | None:
    try:
        path = Path(path_value).resolve(strict=True)
        boundary = root.resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    return path if path.is_file() and (path == boundary or boundary in path.parents) else None


def _book_or_404(session: Session, book_id: str, shelf_pin: str | None = None) -> Book:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="书籍不存在")
    require_book_access(book, shelf_pin)
    return book


@router.get("", response_model=list[BookSummary], dependencies=[Depends(require_mobile_session)])
def list_books(session: Session = Depends(get_db)) -> list[Book]:
    return list(
        session.scalars(
            select(Book)
            .outerjoin(Shelf)
            .where(
                or_(
                    Book.shelf_id.is_(None),
                    (Shelf.is_hidden.is_(False) & Shelf.access_pin_hash.is_(None)),
                )
            )
            .order_by(Book.title, Book.id)
        )
    )


@router.get("/{book_id}", response_model=BookDetail, dependencies=[Depends(require_mobile_session)])
def get_book(
    book_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> Book:
    return _book_or_404(session, book_id, shelf_pin)


@router.get("/{book_id}/cover", dependencies=[Depends(require_mobile_session)])
def get_cover(
    book_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Response:
    book = _book_or_404(session, book_id, shelf_pin)
    cover = _file_within(book.cover_path, settings.cover_path) if book.cover_path else None
    if book.cover_status != "ready" or cover is None:
        metadata = dict(book.metadata_overrides_json or {})
        if metadata.get("manual_cover_path"):
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="手工封面文件不存在")
        try:
            result = ensure_cover(
                Path(book.file_path),
                book.title,
                settings.cover_path.resolve(),
                book.fingerprint,
            )
            book.cover_status = result.status
            book.cover_path = result.path
            metadata["automatic_cover_source"] = result.source
            metadata["cover_source"] = result.source
            book.metadata_overrides_json = metadata
            warnings = list(book.parse_warnings_json or [])
            for warning in result.warnings:
                if warning not in warnings:
                    warnings.append(warning)
            book.parse_warnings_json = warnings
            session.commit()
            cover = _file_within(book.cover_path, settings.cover_path) if book.cover_path else None
        except Exception as exc:
            session.rollback()
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"封面不可用：{exc}") from exc
    if book.cover_status != "ready" or cover is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="封面不可用")
    return FileResponse(cover, media_type="image/jpeg")


@router.get("/{book_id}/toc", response_model=TocResponse, dependencies=[Depends(require_mobile_session)])
def get_toc(
    book_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> TocResponse:
    book = _book_or_404(session, book_id, shelf_pin)
    if book.format == "pdf":
        return TocResponse(book_id=book.id, format="pdf", chapter_supported=False, items=[])
    return TocResponse(
        book_id=book.id,
        format=book.format,
        chapter_supported=True,
        items=[
            TocItem(id=chapter.id, title=chapter.original_title or chapter.title, position=chapter.position)
            for chapter in book.chapters
        ],
    )


@router.get(
    "/{book_id}/chapters/{chapter_id}",
    response_model=ChapterResponse,
    dependencies=[Depends(require_mobile_session)],
)
def get_chapter(
    book_id: str,
    chapter_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> ChapterResponse:
    book = _book_or_404(session, book_id, shelf_pin)
    if book.format == "pdf":
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="PDF 不提供章节正文",
        )
    chapter = session.scalar(select(Chapter).where(Chapter.id == chapter_id, Chapter.book_id == book_id))
    if chapter is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="章节不存在")
    return ChapterResponse(
        id=chapter.id,
        book_id=chapter.book_id,
        title=chapter.original_title or chapter.title,
        position=chapter.position,
        body=chapter.body,
    )


def _navigation_to_api(items: list[dict]) -> list[PdfNavigationItem]:
    return [
        PdfNavigationItem(
            title=str(item.get("title", "")),
            page=int(item.get("page_index", 0)) + 1,
            children=_navigation_to_api(item.get("children", [])),
        )
        for item in items
        if item.get("title") and isinstance(item.get("page_index"), int)
    ]


@router.get(
    "/{book_id}/pdf-navigation",
    response_model=PdfNavigationResponse,
    dependencies=[Depends(require_mobile_session)],
)
def get_pdf_navigation(
    book_id: str,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
) -> PdfNavigationResponse:
    book = _book_or_404(session, book_id, shelf_pin)
    if book.format != "pdf":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="该接口只适用于 PDF")
    return PdfNavigationResponse(
        book_id=book.id,
        page_count=book.page_count,
        items=_navigation_to_api(book.pdf_navigation_json or []),
    )


@router.get("/{book_id}/file", dependencies=[Depends(require_mobile_session)])
def get_file(
    book_id: str,
    request: Request,
    shelf_pin: str | None = Header(default=None, alias="X-Shelf-Pin"),
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Response:
    book = _book_or_404(session, book_id, shelf_pin)
    try:
        root = resolved_shelf_path(book.shelf, settings) if book.shelf is not None else settings.library_path
    except (OSError, ValueError, RuntimeError):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="原始文件不存在") from None
    source = _file_within(book.file_path, root)
    if source is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="原始文件不存在")
    return stream_file(request, source, book.mime_type, book.fingerprint)
