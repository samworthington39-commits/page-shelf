from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pypdf import PdfReader


@dataclass(slots=True)
class PdfMetadata:
    title: str
    has_title_metadata: bool = False
    author: str | None = None
    subject: str | None = None
    keywords: str | None = None
    document_created_at: datetime | None = None
    document_modified_at: datetime | None = None
    page_count: int | None = None
    password_required: bool = False
    can_open: bool = True
    navigation: list[dict[str, Any]] = field(default_factory=list)
    cover_status: str = "unavailable"
    cover_path: str | None = None
    parse_status: str = "ready"
    warnings: list[str] = field(default_factory=list)


def fingerprint_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _text(value: object | None) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _pdf_datetime(value: object | None) -> datetime | None:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value
    return None


def _outline_items(reader: PdfReader, outline: list[Any], warnings: list[str]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    last: dict[str, Any] | None = None
    for entry in outline:
        if isinstance(entry, list):
            children = _outline_items(reader, entry, warnings)
            if last is not None:
                last["children"] = children
            else:
                result.extend(children)
            continue

        title = _text(getattr(entry, "title", None))
        if not title:
            continue
        try:
            page_index = reader.get_destination_page_number(entry)
        except Exception as exc:  # an invalid bookmark must not reject the document
            warnings.append(f"书签“{title}”无法定位：{exc}")
            continue
        if page_index is None or page_index < 0:
            warnings.append(f"书签“{title}”没有有效页码")
            continue
        last = {"title": title, "page_index": int(page_index), "children": []}
        result.append(last)
    return result


def _render_cover(path: Path, destination: Path, warnings: list[str]) -> tuple[str, str | None]:
    try:
        import fitz

        destination.parent.mkdir(parents=True, exist_ok=True)
        with fitz.open(path) as document:
            if document.needs_pass or document.page_count == 0:
                return "unavailable", None
            page = document.load_page(0)
            scale = min(2.0, 360.0 / max(page.rect.width, 1.0))
            pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            pixmap.save(destination)
        return "ready", str(destination.resolve())
    except Exception as exc:
        warnings.append(f"封面缩略图生成失败：{exc}")
        return "failed", None


def inspect_pdf(
    path: Path,
    cover_directory: Path,
    fingerprint: str,
    *,
    render_cover: bool = True,
) -> PdfMetadata:
    """Read PDF container metadata only; no text extraction, OCR, or chapters."""
    result = PdfMetadata(title=path.stem)
    try:
        reader = PdfReader(str(path), strict=False)
        result.password_required = bool(reader.is_encrypted)
        if reader.is_encrypted:
            try:
                if reader.decrypt("") == 0:
                    result.can_open = False
                    result.parse_status = "warning"
                    result.warnings.append("PDF 已加密，需要密码；无法读取总页数")
                    return result
                result.password_required = False
            except Exception as exc:
                result.can_open = False
                result.parse_status = "warning"
                result.warnings.append(f"PDF 已加密，需要密码；无法读取总页数：{exc}")
                return result

        try:
            metadata = reader.metadata
            if metadata is not None:
                metadata_title = _text(metadata.title)
                if metadata_title:
                    result.title = metadata_title
                    result.has_title_metadata = True
                result.author = _text(metadata.author)
                result.subject = _text(metadata.subject)
                result.keywords = _text(metadata.get("/Keywords"))
                result.document_created_at = _pdf_datetime(metadata.creation_date)
                result.document_modified_at = _pdf_datetime(metadata.modification_date)
        except Exception as exc:
            result.warnings.append(f"部分 PDF 元数据无法读取：{exc}")

        result.page_count = len(reader.pages)
        try:
            outline = reader.outline
            if isinstance(outline, list):
                result.navigation = _outline_items(reader, outline, result.warnings)
        except Exception as exc:
            result.warnings.append(f"PDF 书签无法读取：{exc}")

        if render_cover:
            cover_file = cover_directory / f"{fingerprint}.jpg"
            result.cover_status, result.cover_path = _render_cover(path, cover_file, result.warnings)
        if result.warnings:
            result.parse_status = "warning"
    except Exception as exc:
        result.can_open = False
        result.parse_status = "warning"
        result.warnings.append(f"PDF 无法正常打开：{exc}")
    return result
