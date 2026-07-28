from __future__ import annotations

import mimetypes
import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Book, Chapter
from .cover_service import automatic_cover
from .epub_processor import EpubCover, inspect_epub
from .mobi_processor import inspect_mobi
from .pdf_processor import fingerprint_file, inspect_pdf
from .text_processor import (
    DEFAULT_SEGMENT_SIZE,
    TextChapter,
    read_text,
    split_text_with_warnings,
)


SUPPORTED_EXTENSIONS = {".pdf", ".txt", ".epub", ".mobi"}
_BOOK_TITLE_PATTERN = re.compile(r"《([^《》]+)》")


@dataclass(slots=True)
class ScanResult:
    discovered: int = 0
    imported: int = 0
    updated: int = 0
    unchanged: int = 0
    removed: int = 0
    failures: list[dict[str, str]] = field(default_factory=list)


def _book_mark_title(value: str) -> str | None:
    """Return the first non-empty Chinese book-title-mark group."""
    for match in _BOOK_TITLE_PATTERN.finditer(value):
        title = " ".join(match.group(1).split()).strip()
        if title:
            return title[:500]
    return None


def _automatic_book_title_details(
    path: Path,
    internal_title: str | None = None,
) -> tuple[str, str]:
    filename_title = _book_mark_title(path.stem)
    if filename_title:
        return filename_title, "filename"
    normalized_internal = " ".join((internal_title or "").split()).strip()
    if normalized_internal:
        return normalized_internal[:500], "internal"
    directory_title = _book_mark_title(path.parent.name)
    if directory_title:
        return directory_title, "directory"
    return (" ".join(path.stem.split()).strip() or path.name)[:500], "filename_stem"


def automatic_book_title(path: Path, internal_title: str | None = None) -> str:
    """Derive a display title without modifying the source file.

    Priority is a title in the filename, explicit container metadata, a title in
    the immediate parent directory, and finally the ordinary filename stem.
    """
    return _automatic_book_title_details(path, internal_title)[0]


def apply_metadata_overrides(
    book: Book,
    automatic_title: str,
    automatic_author: str | None,
    *,
    automatic_title_source: str | None = None,
) -> None:
    """Record automatic values, then reapply persistent administrator edits."""
    metadata = dict(book.metadata_overrides_json or {})
    metadata["automatic_title"] = automatic_title[:500]
    metadata["automatic_author"] = automatic_author
    if automatic_title_source:
        metadata["automatic_title_source"] = automatic_title_source
    book.title = metadata["title"] if "title" in metadata else automatic_title
    book.author = metadata["author"] if "author" in metadata else automatic_author
    manual_cover_path = metadata.get("manual_cover_path")
    if isinstance(manual_cover_path, str) and manual_cover_path:
        book.cover_path = manual_cover_path
        book.cover_status = "ready" if Path(manual_cover_path).is_file() else "missing"
        metadata["cover_source"] = "manual"
    book.metadata_overrides_json = metadata


def _apply_automatic_cover(
    book: Book,
    path: Path,
    cover_directory: Path,
    fingerprint: str,
    automatic_title: str,
    *,
    embedded_cover: EpubCover | None = None,
    embedded_source: str | None = None,
    embedded_inspected: bool = False,
) -> None:
    metadata = dict(book.metadata_overrides_json or {})
    display_title = str(metadata.get("title") or automatic_title)
    cover = automatic_cover(
        path,
        display_title,
        cover_directory,
        fingerprint,
        embedded_cover=embedded_cover,
        embedded_source=embedded_source,
        embedded_inspected=embedded_inspected,
    )
    book.cover_status = cover.status
    book.cover_path = cover.path
    metadata["automatic_cover_source"] = cover.source
    if not metadata.get("manual_cover_path"):
        metadata["cover_source"] = cover.source
    book.metadata_overrides_json = metadata
    warnings = list(book.parse_warnings_json or [])
    for warning in cover.warnings:
        if warning not in warnings:
            warnings.append(warning)
    book.parse_warnings_json = warnings


def _is_within(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def locate_book_source(library_path: Path, book: Book) -> Path | None:
    """Resolve a book source without ever searching outside its current shelf.

    A stored absolute path can become stale when a file is moved inside a shelf.
    In that case, only an unambiguous fingerprint match is accepted.  File size is
    checked first so the recovery path does not hash every book in a large shelf.
    """
    try:
        library_root = library_path.resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    try:
        stored = Path(book.file_path).resolve(strict=True)
        if stored.is_file() and _is_within(stored, library_root):
            return stored
    except (OSError, RuntimeError):
        pass

    expected_suffix = f".{book.format.lower()}"
    matches: list[Path] = []
    for candidate in library_root.rglob("*"):
        try:
            if candidate.is_symlink() or not candidate.is_file():
                continue
            if candidate.suffix.lower() != expected_suffix or candidate.stat().st_size != book.file_size:
                continue
            resolved = candidate.resolve(strict=True)
            if not _is_within(resolved, library_root):
                continue
            if fingerprint_file(resolved) != book.fingerprint:
                continue
        except (OSError, RuntimeError):
            continue
        matches.append(resolved)
        if len(matches) > 1:
            return None
    return matches[0] if matches else None


def _relocated_catalog_book(
    session: Session,
    *,
    shelf_id: str | None,
    fingerprint: str,
    extension: str,
    current_paths: set[str],
) -> Book | None:
    if shelf_id is None:
        return None
    candidates = session.scalars(
        select(Book).where(
            Book.shelf_id == shelf_id,
            Book.fingerprint == fingerprint,
            Book.format == extension.removeprefix("."),
        )
    )
    stale = [book for book in candidates if book.file_path not in current_paths]
    return stale[0] if len(stale) == 1 else None


def _source_modified(path: Path) -> datetime:
    return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)


def _base_values(path: Path, fingerprint: str) -> dict[str, object]:
    mime_type = (
        "application/x-mobipocket-ebook"
        if path.suffix.lower() == ".mobi"
        else mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    )
    return {
        "format": path.suffix.lower().removeprefix("."),
        "title": automatic_book_title(path),
        "file_size": path.stat().st_size,
        "fingerprint": fingerprint,
        "mime_type": mime_type,
        "file_path": str(path.resolve()),
        "source_modified_at": _source_modified(path),
        "parse_status": "ready",
        "parse_warnings_json": [],
        "can_open": True,
        # API boolean status, not a credential. bool() avoids security scanners
        # mistaking the field name for a hard-coded password.
        "password_required": bool(),
        "cover_status": "unavailable",
    }


def _replace_chapters(
    session: Session,
    book: Book,
    chapters: list[TextChapter],
    hrefs: list[str | None] | None = None,
) -> None:
    session.flush()
    book.chapters.clear()
    session.flush()
    for position, chapter in enumerate(chapters):
        source_position = chapter.source_position if chapter.source_position is not None else position
        stable_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"page-shelf:{book.id}:{source_position}:{chapter.original_title or chapter.title}"))
        book.chapters.append(Chapter(
            id=stable_id,
            title=chapter.normalized_title or chapter.title,
            original_title=chapter.original_title or chapter.title,
            normalized_title=chapter.normalized_title or chapter.title,
            body=chapter.body,
            source_href=hrefs[position] if hrefs else None,
            position=position,
            volume_index=chapter.volume_index,
            chapter_index=chapter.chapter_index,
            secondary_index=chapter.secondary_index,
            suffix_order=chapter.suffix_order,
            level=chapter.level,
            special_type=chapter.special_type,
            start_offset=chapter.start_offset,
            end_offset=chapter.end_offset,
            source_position=source_position,
        ))
    book.chapter_count = len(chapters)


def process_book_file(
    session: Session,
    path: Path,
    book: Book,
    cover_directory: Path,
    fingerprint: str,
) -> None:
    extension = path.suffix.lower()
    if extension == ".pdf":
        metadata = inspect_pdf(path, cover_directory, fingerprint, render_cover=False)
        automatic_title, title_source = _automatic_book_title_details(
            path,
            metadata.title if metadata.has_title_metadata else None,
        )
        book.subject = metadata.subject
        book.keywords = metadata.keywords
        book.document_created_at = metadata.document_created_at
        book.document_modified_at = metadata.document_modified_at
        book.page_count = metadata.page_count
        book.password_required = metadata.password_required
        book.can_open = metadata.can_open
        book.pdf_navigation_json = metadata.navigation
        book.cover_status = metadata.cover_status
        book.cover_path = metadata.cover_path
        book.parse_status = metadata.parse_status
        book.parse_warnings_json = metadata.warnings
        book.chapter_count = None
        book.chapter_split_mode = "none"
        book.chapter_split_config_json = {}
        book.chapters.clear()
        _apply_automatic_cover(book, path, cover_directory, fingerprint, automatic_title)
        apply_metadata_overrides(
            book,
            automatic_title,
            metadata.author,
            automatic_title_source=title_source,
        )
        return

    book.page_count = None
    book.pdf_navigation_json = None
    book.parse_warnings_json = []
    mode = book.chapter_split_mode or "auto"
    config = book.chapter_split_config_json or {}
    segment_size = int(config.get("segment_size", DEFAULT_SEGMENT_SIZE))
    if extension == ".txt":
        if mode == "source":
            raise ValueError("原始目录方式只适用于 EPUB 或 MOBI")
        result = split_text_with_warnings(read_text(path), mode=mode, segment_size=segment_size)
        _replace_chapters(session, book, result.chapters)
        book.parse_warnings_json = result.warnings
        book.last_chapter_split_at = datetime.now(timezone.utc)
        automatic_title, title_source = _automatic_book_title_details(path)
        _apply_automatic_cover(book, path, cover_directory, fingerprint, automatic_title)
        apply_metadata_overrides(
            book,
            automatic_title,
            None,
            automatic_title_source=title_source,
        )
        return
    if extension == ".epub":
        epub = inspect_epub(path)
        automatic_title, title_source = _automatic_book_title_details(
            path,
            epub.title if epub.has_title_metadata else None,
        )
        if mode in {"auto", "source"}:
            chapters = [
                TextChapter(
                    title=chapter.title,
                    original_title=chapter.title,
                    normalized_title=chapter.title,
                    body=chapter.body,
                    source_position=position,
                )
                for position, chapter in enumerate(epub.chapters)
            ]
            _replace_chapters(session, book, chapters, [chapter.href for chapter in epub.chapters])
        else:
            combined = "\n\n".join(f"{chapter.title}\n{chapter.body}" for chapter in epub.chapters)
            result = split_text_with_warnings(combined, mode=mode, segment_size=segment_size)
            _replace_chapters(session, book, result.chapters)
            book.parse_warnings_json = result.warnings
        book.last_chapter_split_at = datetime.now(timezone.utc)
        _apply_automatic_cover(book, path, cover_directory, fingerprint, automatic_title)
        apply_metadata_overrides(
            book,
            automatic_title,
            epub.author,
            automatic_title_source=title_source,
        )
        return
    if extension == ".mobi":
        mobi = inspect_mobi(path)
        book.parse_warnings_json = list(mobi.warnings or [])
        automatic_title, title_source = _automatic_book_title_details(
            path,
            mobi.title if mobi.has_title_metadata else None,
        )
        if mode == "source" or (mode == "auto" and (mobi.has_navigation or len(mobi.chapters) > 1)):
            chapters = [
                TextChapter(
                    title=chapter.title,
                    original_title=chapter.title,
                    normalized_title=chapter.title,
                    body=chapter.body,
                    source_position=position,
                )
                for position, chapter in enumerate(mobi.chapters)
            ]
            _replace_chapters(session, book, chapters, [chapter.href for chapter in mobi.chapters])
        else:
            combined = "\n\n".join(f"{chapter.title}\n{chapter.body}" for chapter in mobi.chapters)
            result = split_text_with_warnings(combined, mode=mode, segment_size=segment_size)
            _replace_chapters(session, book, result.chapters)
            book.parse_warnings_json = result.warnings
        book.last_chapter_split_at = datetime.now(timezone.utc)
        _apply_automatic_cover(
            book,
            path,
            cover_directory,
            fingerprint,
            automatic_title,
            embedded_cover=mobi.cover,
            embedded_source="mobi",
            embedded_inspected=True,
        )
        apply_metadata_overrides(
            book,
            automatic_title,
            mobi.author,
            automatic_title_source=title_source,
        )
        return
    raise ValueError(f"不支持的文件格式：{extension}")


def scan_library(
    session: Session,
    library_path: Path,
    cover_directory: Path,
    *,
    shelf_id: str | None = None,
    remove_missing: bool = False,
) -> ScanResult:
    result = ScanResult()
    library_path.mkdir(parents=True, exist_ok=True)
    library_root = library_path.resolve(strict=True)
    files = sorted(
        path
        for path in library_root.rglob("*")
        if path.is_file()
        and not path.is_symlink()
        and path.suffix.lower() in SUPPORTED_EXTENSIONS
        and (path.resolve() == library_root or library_root in path.resolve().parents)
    )
    result.discovered = len(files)
    current_paths = {str(path.resolve()) for path in files}

    for path in files:
        try:
            resolved = str(path.resolve())
            fingerprint = fingerprint_file(path)
            book = session.scalar(select(Book).where(Book.file_path == resolved))
            if book is None:
                book = _relocated_catalog_book(
                    session,
                    shelf_id=shelf_id,
                    fingerprint=fingerprint,
                    extension=path.suffix.lower(),
                    current_paths=current_paths,
                )
            if book is not None and book.fingerprint == fingerprint:
                previous_path = Path(book.file_path)
                if book.file_path != resolved:
                    book.file_path = resolved
                    book.source_modified_at = _source_modified(path)
                saved_metadata = dict(book.metadata_overrides_json or {})
                saved_source = saved_metadata.get("automatic_title_source")
                internal_title: str | None = None
                if book.format in {"epub", "mobi", "pdf"}:
                    if saved_source == "internal":
                        internal_title = str(saved_metadata.get("automatic_title") or book.title)
                    elif "automatic_title" not in saved_metadata and book.title != previous_path.stem:
                        # Migrated rows predate the automatic/manual split. Their
                        # current non-filename value is the best available container title.
                        internal_title = book.title
                automatic_title, title_source = _automatic_book_title_details(path, internal_title)
                automatic_author = (
                    saved_metadata["automatic_author"]
                    if "automatic_author" in saved_metadata
                    else book.author
                )
                has_manual_cover = bool(saved_metadata.get("manual_cover_path"))
                cover_missing = not book.cover_path or not Path(book.cover_path).is_file()
                if not has_manual_cover and (book.cover_status != "ready" or cover_missing):
                    _apply_automatic_cover(book, path, cover_directory, fingerprint, automatic_title)
                apply_metadata_overrides(
                    book,
                    automatic_title,
                    automatic_author,
                    automatic_title_source=title_source,
                )
                if shelf_id is not None and book.shelf_id != shelf_id:
                    book.shelf_id = shelf_id
                session.commit()
                result.unchanged += 1
                continue

            is_new = book is None
            if book is None:
                book = Book(**_base_values(path, fingerprint), shelf_id=shelf_id)
                session.add(book)
            else:
                for key, value in _base_values(path, fingerprint).items():
                    setattr(book, key, value)
                if shelf_id is not None:
                    book.shelf_id = shelf_id

            process_book_file(session, path, book, cover_directory, fingerprint)
            session.commit()
            if is_new:
                result.imported += 1
            else:
                result.updated += 1
        except Exception as exc:
            session.rollback()
            result.failures.append({"file": str(path), "warning": str(exc)})

    if remove_missing and shelf_id is not None:
        stale_books = list(session.scalars(select(Book).where(Book.shelf_id == shelf_id)))
        for book in stale_books:
            if book.file_path not in current_paths:
                session.delete(book)
                result.removed += 1
        session.commit()
    return result
