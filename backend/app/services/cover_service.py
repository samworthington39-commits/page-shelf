from __future__ import annotations

import os
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import fitz

from .epub_processor import EpubCover, extract_epub_cover


MAX_INPUT_BYTES = 12 * 1024 * 1024
MAX_INPUT_PIXELS = 24_000_000
MAX_INPUT_DIMENSION = 12_000
MAX_OUTPUT_WIDTH = 1_200
MAX_OUTPUT_HEIGHT = 1_800
MAX_OUTPUT_BYTES = 6 * 1024 * 1024
JPEG_QUALITY = 86
FALLBACK_WIDTH = 720
FALLBACK_HEIGHT = 1_080

_SAFE_KEY = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_JPEG_SOF_MARKERS = {
    0xC0,
    0xC1,
    0xC2,
    0xC3,
    0xC5,
    0xC6,
    0xC7,
    0xC9,
    0xCA,
    0xCB,
    0xCD,
    0xCE,
    0xCF,
}


@dataclass(slots=True)
class CoverResult:
    status: str
    path: str | None
    source: str
    warnings: list[str] = field(default_factory=list)


def _safe_key(value: str) -> str:
    if not _SAFE_KEY.fullmatch(value):
        raise ValueError("封面标识格式无效")
    return value


def _jpeg_dimensions(payload: bytes) -> tuple[int, int]:
    if not payload.startswith(b"\xff\xd8"):
        raise ValueError("图片不是有效的 JPEG")
    offset = 2
    while offset < len(payload):
        while offset < len(payload) and payload[offset] != 0xFF:
            offset += 1
        while offset < len(payload) and payload[offset] == 0xFF:
            offset += 1
        if offset >= len(payload):
            break
        marker = payload[offset]
        offset += 1
        if marker in {0x01, 0xD8, 0xD9, *range(0xD0, 0xD8)}:
            continue
        if offset + 2 > len(payload):
            break
        segment_length = int.from_bytes(payload[offset : offset + 2], "big")
        if segment_length < 2 or offset + segment_length > len(payload):
            raise ValueError("JPEG 数据段不完整")
        if marker in _JPEG_SOF_MARKERS:
            if segment_length < 7:
                raise ValueError("JPEG 尺寸数据无效")
            height = int.from_bytes(payload[offset + 3 : offset + 5], "big")
            width = int.from_bytes(payload[offset + 5 : offset + 7], "big")
            return width, height
        if marker == 0xDA:
            break
        offset += segment_length
    raise ValueError("JPEG 缺少尺寸信息")


def _image_kind_and_dimensions(payload: bytes) -> tuple[str, int, int]:
    if not payload:
        raise ValueError("封面图片为空")
    if len(payload) > MAX_INPUT_BYTES:
        raise ValueError("封面图片超过大小限制")
    if payload.startswith(_PNG_SIGNATURE):
        if len(payload) < 24 or payload[12:16] != b"IHDR":
            raise ValueError("PNG 头部无效")
        width = int.from_bytes(payload[16:20], "big")
        height = int.from_bytes(payload[20:24], "big")
        kind = "png"
    elif payload.startswith(b"\xff\xd8"):
        width, height = _jpeg_dimensions(payload)
        kind = "jpeg"
    else:
        raise ValueError("只支持 JPEG 或 PNG 封面图片")
    if width <= 0 or height <= 0:
        raise ValueError("封面图片尺寸无效")
    if width > MAX_INPUT_DIMENSION or height > MAX_INPUT_DIMENSION or width * height > MAX_INPUT_PIXELS:
        raise ValueError("封面图片像素尺寸超过限制")
    return kind, width, height


def _normalized_jpeg(payload: bytes) -> bytes:
    kind, width, height = _image_kind_and_dimensions(payload)
    scale = min(1.0, MAX_OUTPUT_WIDTH / width, MAX_OUTPUT_HEIGHT / height)
    target_width = max(1, round(width * scale))
    target_height = max(1, round(height * scale))
    try:
        with fitz.open(stream=payload, filetype=kind) as document:
            if document.page_count != 1:
                raise ValueError("封面图片页数无效")
            page = document.load_page(0)
            if page.rect.width <= 0 or page.rect.height <= 0:
                raise ValueError("封面图片尺寸无效")
            matrix = fitz.Matrix(target_width / page.rect.width, target_height / page.rect.height)
            pixmap = page.get_pixmap(matrix=matrix, colorspace=fitz.csRGB, alpha=False)
            if pixmap.width > MAX_OUTPUT_WIDTH or pixmap.height > MAX_OUTPUT_HEIGHT:
                raise ValueError("封面输出尺寸超过限制")
            normalized = pixmap.tobytes("jpeg", jpg_quality=JPEG_QUALITY)
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError("封面图片无法解码") from exc
    if not normalized.startswith(b"\xff\xd8") or len(normalized) > MAX_OUTPUT_BYTES:
        raise ValueError("封面图片无法安全归一化")
    return normalized


def _atomic_write(payload: bytes, destination: Path) -> Path:
    destination = destination.resolve(strict=False)
    if destination.suffix.lower() not in {".jpg", ".jpeg"}:
        raise ValueError("封面输出必须使用 JPEG 扩展名")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.stem}-",
            suffix=".tmp",
            dir=destination.parent,
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, destination)
    finally:
        if temporary_name:
            Path(temporary_name).unlink(missing_ok=True)
    return destination


def store_cover_bytes(payload: bytes, destination: Path) -> Path:
    """Validate, bound and atomically persist uploaded or extracted image bytes as JPEG."""
    return _atomic_write(_normalized_jpeg(payload), destination)


def _wrapped_title(title: str, line_width: int = 12, max_lines: int = 5) -> str:
    clean = " ".join((title or "未命名书籍").split())[:160] or "未命名书籍"
    lines: list[str] = []
    current = ""
    current_width = 0
    for character in clean:
        width = 1 if character.isascii() else 2
        if current and current_width + width > line_width:
            lines.append(current.rstrip())
            current = ""
            current_width = 0
        current += character
        current_width += width
    if current:
        lines.append(current.rstrip())
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = f"{lines[-1][:-1]}…" if lines[-1] else "…"
    return "\n".join(lines)


def _generated_cover_bytes(title: str) -> bytes:
    document = fitz.open()
    try:
        page = document.new_page(width=FALLBACK_WIDTH, height=FALLBACK_HEIGHT)
        page.draw_rect(page.rect, fill=(0.10, 0.14, 0.13), color=(0.10, 0.14, 0.13))
        book = fitz.Rect(48, 48, FALLBACK_WIDTH - 48, FALLBACK_HEIGHT - 48)
        page.draw_rect(book, fill=(0.94, 0.90, 0.80), color=(0.78, 0.60, 0.34), width=8)
        page.draw_line((86, 55), (86, FALLBACK_HEIGHT - 55), color=(0.68, 0.43, 0.23), width=5)
        page.draw_line((108, 92), (FALLBACK_WIDTH - 86, 92), color=(0.78, 0.60, 0.34), width=2)
        page.draw_line(
            (108, FALLBACK_HEIGHT - 94),
            (FALLBACK_WIDTH - 86, FALLBACK_HEIGHT - 94),
            color=(0.78, 0.60, 0.34),
            width=2,
        )
        wrapped = _wrapped_title(title)
        line_count = wrapped.count("\n") + 1
        font_size = max(42, 66 - max(0, line_count - 2) * 6)
        text_rect = fitz.Rect(126, 250, FALLBACK_WIDTH - 88, 830)
        remaining = page.insert_textbox(
            text_rect,
            wrapped,
            fontname="china-s",
            fontsize=font_size,
            lineheight=1.35,
            color=(0.12, 0.17, 0.15),
            align=fitz.TEXT_ALIGN_CENTER,
        )
        if remaining < 0:
            raise ValueError("书名过长，无法生成封面")
        page.insert_textbox(
            fitz.Rect(128, 878, FALLBACK_WIDTH - 88, 930),
            "PAGE SHELF",
            fontname="helv",
            fontsize=18,
            color=(0.38, 0.31, 0.22),
            align=fitz.TEXT_ALIGN_CENTER,
        )
        pixmap = page.get_pixmap(colorspace=fitz.csRGB, alpha=False)
        payload = pixmap.tobytes("jpeg", jpg_quality=JPEG_QUALITY)
    finally:
        document.close()
    if len(payload) > MAX_OUTPUT_BYTES:
        raise ValueError("生成封面超过输出大小限制")
    return payload


def generated_cover(title: str, destination: Path) -> Path:
    """Generate a bounded JPEG fallback with a book border and CJK-capable title text."""
    return _atomic_write(_generated_cover_bytes(title), destination)


def _pdf_cover_bytes(path: Path) -> bytes:
    with fitz.open(path) as document:
        if document.needs_pass or document.page_count == 0:
            raise ValueError("PDF 已加密或没有可渲染页面")
        page = document.load_page(0)
        scale = min(MAX_OUTPUT_WIDTH / max(page.rect.width, 1), MAX_OUTPUT_HEIGHT / max(page.rect.height, 1))
        pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), colorspace=fitz.csRGB, alpha=False)
        return pixmap.tobytes("jpeg", jpg_quality=JPEG_QUALITY)


def automatic_cover(
    path: Path,
    title: str,
    cover_directory: Path,
    fingerprint: str,
    *,
    embedded_cover: EpubCover | None = None,
    embedded_source: str | None = None,
    embedded_inspected: bool = False,
) -> CoverResult:
    """Refresh a TXT, EPUB, MOBI or PDF automatic cover, falling back to a generated JPEG."""
    destination = cover_directory.resolve(strict=False) / f"{_safe_key(fingerprint)}.jpg"
    warnings: list[str] = []
    extension = path.suffix.lower()
    try:
        if embedded_cover is not None:
            stored = store_cover_bytes(embedded_cover.data, destination)
            return CoverResult("ready", str(stored), embedded_source or "embedded", warnings)
        if extension == ".epub":
            cover = extract_epub_cover(path)
            if cover is not None:
                stored = store_cover_bytes(cover.data, destination)
                return CoverResult("ready", str(stored), "epub", warnings)
        elif extension == ".mobi" and not embedded_inspected:
            from .mobi_processor import extract_mobi_cover

            cover = extract_mobi_cover(path)
            if cover is not None:
                stored = store_cover_bytes(cover.data, destination)
                return CoverResult("ready", str(stored), "mobi", warnings)
        elif extension == ".pdf":
            stored = store_cover_bytes(_pdf_cover_bytes(path), destination)
            return CoverResult("ready", str(stored), "pdf", warnings)
    except Exception as exc:
        warnings.append(f"自动封面提取失败：{exc}")

    try:
        stored = generated_cover(title, destination)
        return CoverResult("ready", str(stored), "generated", warnings)
    except Exception as exc:
        warnings.append(f"默认封面生成失败：{exc}")
        return CoverResult("failed", None, "generated", warnings)


def refresh_cover(path: Path, title: str, cover_directory: Path, fingerprint: str) -> CoverResult:
    return automatic_cover(path, title, cover_directory, fingerprint)


def ensure_cover(path: Path, title: str, cover_directory: Path, fingerprint: str) -> CoverResult:
    destination = cover_directory.resolve(strict=False) / f"{_safe_key(fingerprint)}.jpg"
    if destination.is_file():
        try:
            payload = destination.read_bytes()
            _normalized_jpeg(payload)
            if len(payload) <= MAX_OUTPUT_BYTES:
                return CoverResult("ready", str(destination), "cached", [])
        except (OSError, ValueError):
            pass
    return automatic_cover(path, title, cover_directory, fingerprint)


def uploaded_cover_path(cover_directory: Path, book_id: str) -> Path:
    return cover_directory.resolve(strict=False) / f"manual-{_safe_key(book_id)}.jpg"


def store_uploaded_cover(payload: bytes, cover_directory: Path, book_id: str) -> Path:
    return store_cover_bytes(payload, uploaded_cover_path(cover_directory, book_id))


def remove_uploaded_cover(cover_directory: Path, book_id: str) -> bool:
    destination = uploaded_cover_path(cover_directory, book_id)
    try:
        destination.unlink()
        return True
    except FileNotFoundError:
        return False
