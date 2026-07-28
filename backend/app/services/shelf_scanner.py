from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import Book, Shelf
from .library_scanner import ScanResult, locate_book_source, process_book_file, scan_library
from .storage_paths import allowed_storage_path, shelf_directory


class ShelfScanBusyError(RuntimeError):
    pass


_locks_guard = Lock()
_shelf_locks: dict[str, Lock] = {}


def _lock_for(shelf_id: str) -> Lock:
    with _locks_guard:
        return _shelf_locks.setdefault(shelf_id, Lock())


def resolved_shelf_path(shelf: Shelf, settings: Settings) -> Path:
    location = shelf.storage_location
    allowed_storage_path(location.path, settings, create=False)
    return shelf_directory(location.path, shelf.relative_path, create=False)


def _stored_book_source(book: Book, shelf_root: Path) -> tuple[Path | None, str]:
    try:
        source = Path(book.file_path).resolve(strict=True)
    except FileNotFoundError:
        return None, "missing"
    except PermissionError:
        return None, "permission"
    except (OSError, RuntimeError):
        return None, "unreadable"
    try:
        if not source.is_file():
            return None, "missing"
    except OSError:
        return None, "permission"
    if source != shelf_root and shelf_root not in source.parents:
        return None, "outside"
    return source, "ready"


def scan_shelf(session: Session, shelf: Shelf, settings: Settings) -> ScanResult:
    lock = _lock_for(shelf.id)
    if not lock.acquire(blocking=False):
        raise ShelfScanBusyError("该书架正在扫描")
    try:
        shelf.scan_status = "scanning"
        shelf.last_scan_started_at = datetime.now(timezone.utc)
        shelf.last_scan_error = None
        session.commit()
        try:
            result = scan_library(
                session,
                resolved_shelf_path(shelf, settings),
                settings.cover_path.resolve(),
                shelf_id=shelf.id,
                remove_missing=True,
            )
            shelf.scan_status = "idle" if not result.failures else "warning"
            shelf.last_scan_completed_at = datetime.now(timezone.utc)
            shelf.last_scan_summary_json = asdict(result)
            shelf.last_scan_error = None
            session.commit()
            return result
        except Exception as exc:
            session.rollback()
            shelf = session.get(Shelf, shelf.id) or shelf
            shelf.scan_status = "error"
            shelf.last_scan_completed_at = datetime.now(timezone.utc)
            shelf.last_scan_error = str(exc)
            session.commit()
            raise
    finally:
        lock.release()


def scan_shelf_by_id(session: Session, shelf_id: str, settings: Settings) -> ScanResult:
    shelf = session.get(Shelf, shelf_id)
    if shelf is None:
        raise LookupError("书架不存在")
    return scan_shelf(session, shelf, settings)


def resplit_book(
    session: Session,
    book: Book,
    settings: Settings,
    *,
    mode: str,
    segment_size: int,
) -> Book:
    if book.shelf is None:
        raise ValueError("只有已归属书架的书籍可以在后台重新拆分")
    if book.format == "pdf":
        raise ValueError("PDF 只按页码阅读，不支持章节拆分")
    if mode == "source" and book.format not in {"epub", "mobi"}:
        raise ValueError("原始目录方式只适用于 EPUB 或 MOBI")
    lock = _lock_for(book.shelf.id)
    if not lock.acquire(blocking=False):
        raise ShelfScanBusyError("该书架正在扫描，请稍后再设置章节")
    try:
        shelf_root = resolved_shelf_path(book.shelf, settings).resolve()
        source, source_state = _stored_book_source(book, shelf_root)
        if source_state in {"permission", "unreadable"}:
            raise ValueError("容器无法读取书籍源文件，请检查 NAS 与容器目录权限后重新扫描该书架")
        if source is None:
            try:
                source = locate_book_source(shelf_root, book)
            except (OSError, RuntimeError) as exc:
                raise ValueError("容器无法读取书架目录，请检查 NAS 与容器目录权限后重新扫描该书架") from exc
        if source is None:
            if source_state == "outside":
                raise ValueError("书籍源文件已超出当前书架目录；为防止跨书架访问，请重新扫描对应书架")
            raise ValueError("书籍源文件不存在或已移动；若文件仍在 NAS，请检查目录权限后重新扫描该书架")
        resolved_source = str(source)
        if resolved_source != book.file_path:
            owner = session.scalar(select(Book.id).where(Book.file_path == resolved_source, Book.id != book.id))
            if owner is not None:
                raise ValueError("书籍目录记录发生冲突，请先重新扫描该书架后再修改拆分方式")
        book.file_path = resolved_source
        book.chapter_split_mode = mode
        book.chapter_split_config_json = {"segment_size": segment_size} if mode == "fixed" else {}
        try:
            process_book_file(session, source, book, settings.cover_path.resolve(), book.fingerprint)
        except OSError as exc:
            raise ValueError("容器无法读取书籍源文件，请检查 NAS 与容器目录权限后重新扫描该书架") from exc
        book.chapter_split_revision = (book.chapter_split_revision or 0) + 1
        session.commit()
        session.refresh(book)
        return book
    except Exception:
        session.rollback()
        raise
    finally:
        lock.release()


def scan_all_shelves(session: Session, settings: Settings) -> ScanResult:
    shelves = list(session.scalars(select(Shelf).order_by(Shelf.created_at, Shelf.id)))
    if not shelves:
        return scan_library(session, settings.library_path.resolve(), settings.cover_path.resolve())

    combined = ScanResult()
    for shelf in shelves:
        try:
            result = scan_shelf(session, shelf, settings)
            combined.discovered += result.discovered
            combined.imported += result.imported
            combined.updated += result.updated
            combined.unchanged += result.unchanged
            combined.removed += result.removed
            combined.failures.extend(result.failures)
        except Exception as exc:
            combined.failures.append({"file": shelf.name, "warning": str(exc)})
    return combined
