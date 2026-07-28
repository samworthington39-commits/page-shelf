from __future__ import annotations

import html
import re
from dataclasses import dataclass, field
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Lock

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException
from mobi.kindleunpack import unpackBook

from .epub_processor import (
    MAX_COVER_BYTES,
    EpubChapter,
    EpubCover,
    extract_epub_cover,
    inspect_epub,
)


MAX_MOBI_SOURCE_BYTES = 256 * 1024 * 1024
MAX_MOBI_EXTRACTED_BYTES = 512 * 1024 * 1024
MAX_MOBI_HTML_BYTES = 256 * 1024 * 1024
MAX_MOBI_XML_BYTES = 4 * 1024 * 1024
MAX_MOBI_FILES = 20_000
MAX_MOBI_CHAPTERS = 10_000

_UNPACK_LOCK = Lock()


@dataclass(slots=True)
class MobiBook:
    title: str
    author: str | None
    chapters: list[EpubChapter]
    cover: EpubCover | None = None
    has_title_metadata: bool = False
    has_navigation: bool = False
    warnings: list[str] = field(default_factory=list)


def _xml_root(payload: bytes) -> ElementTree.Element:
    upper = payload.upper()
    if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper:
        raise ValueError("MOBI XML 包含不允许的实体声明")
    try:
        root = ElementTree.fromstring(payload)
    except (ElementTree.ParseError, DefusedXmlException) as exc:
        raise ValueError("MOBI XML 格式无效") from exc
    if sum(1 for _ in root.iter()) > 100_000:
        raise ValueError("MOBI XML 结构过于复杂")
    return root


def _read_file(path: Path, limit: int, description: str) -> bytes:
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise ValueError(f"MOBI 缺少{description}") from exc
    if not path.is_file() or path.is_symlink() or size > limit:
        raise ValueError(f"MOBI {description}过大或无效")
    return path.read_bytes()


def _safe_child(root: Path, href: str) -> Path:
    raw = href.split("#", 1)[0].replace("\\", "/")
    if not raw or "\x00" in raw:
        raise ValueError("MOBI 内部路径无效")
    candidate = (root / raw).resolve(strict=False)
    resolved_root = root.resolve(strict=True)
    if candidate != resolved_root and resolved_root not in candidate.parents:
        raise ValueError("MOBI 内部路径越界")
    if candidate.is_symlink():
        raise ValueError("MOBI 内部路径不允许使用符号链接")
    return candidate


def _validate_extracted_tree(root: Path) -> None:
    total_size = 0
    file_count = 0
    resolved_root = root.resolve(strict=True)
    for candidate in root.rglob("*"):
        if candidate.is_symlink():
            raise ValueError("MOBI 解包结果包含符号链接")
        if not candidate.is_file():
            continue
        resolved = candidate.resolve(strict=True)
        if resolved_root not in resolved.parents:
            raise ValueError("MOBI 解包结果路径越界")
        file_count += 1
        if file_count > MAX_MOBI_FILES:
            raise ValueError("MOBI 解包后的文件数量过多")
        total_size += candidate.stat().st_size
        if total_size > MAX_MOBI_EXTRACTED_BYTES:
            raise ValueError("MOBI 解包后的内容总量过大")


def _metadata_text(value: str | None, limit: int = 500) -> str | None:
    normalized = " ".join((value or "").split())
    return normalized[:limit] or None


def _plain_text(markup: str) -> str:
    markup = re.sub(r"(?is)<(script|style).*?>.*?</\1>", "", markup)
    markup = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</h[1-6]>|</li>", "\n", markup)
    return html.unescape(re.sub(r"(?s)<[^>]+>", "", markup)).strip()


def _decode_html(payload: bytes) -> str:
    prefix = payload[:4096].decode("ascii", errors="ignore")
    declared = re.search(
        r"(?i)(?:charset\s*=\s*[\"']?\s*|encoding\s*=\s*[\"'])([a-z0-9._-]+)",
        prefix,
    )
    encodings = [declared.group(1)] if declared else []
    encodings.extend(["utf-8-sig", "utf-8", "gb18030", "big5", "cp1252"])
    for encoding in dict.fromkeys(encodings):
        try:
            return payload.decode(encoding)
        except (LookupError, UnicodeDecodeError):
            continue
    return payload.decode("utf-8", errors="replace")


def _mobi7_metadata(package: ElementTree.Element, fallback_title: str) -> tuple[
    str,
    str | None,
    bool,
    dict[str, tuple[str, str]],
    str | None,
    str | None,
]:
    title = fallback_title
    author: str | None = None
    has_title_metadata = False
    manifest: dict[str, tuple[str, str]] = {}
    ncx_href: str | None = None
    cover_id: str | None = None
    for node in package.iter():
        local = node.tag.rsplit("}", 1)[-1]
        if local == "title" and node.text and not has_title_metadata:
            metadata_title = _metadata_text(node.text)
            if metadata_title:
                title = metadata_title
                has_title_metadata = True
        elif local == "creator" and node.text and author is None:
            author = _metadata_text(node.text)
        elif local == "item":
            item_id = node.attrib.get("id", "")
            href = node.attrib.get("href", "")
            mime_type = node.attrib.get("media-type", "application/octet-stream")
            if item_id and href:
                manifest[item_id] = (href, mime_type)
                if mime_type.partition(";")[0].strip().lower() == "application/x-dtbncx+xml":
                    ncx_href = href
        elif local == "meta" and node.attrib.get("name", "").casefold() == "cover":
            cover_id = cover_id or node.attrib.get("content")
    return title, author, has_title_metadata, manifest, ncx_href, cover_id


def _navigation_entries(ncx_path: Path) -> list[tuple[str, str]]:
    if not ncx_path.is_file():
        return []
    root = _xml_root(_read_file(ncx_path, MAX_MOBI_XML_BYTES, "目录文件"))
    entries: list[tuple[str, str]] = []
    for nav_point in (node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == "navPoint"):
        label = next((node for node in nav_point.iter() if node.tag.rsplit("}", 1)[-1] == "text"), None)
        content = next((node for node in nav_point.iter() if node.tag.rsplit("}", 1)[-1] == "content"), None)
        title = _metadata_text("".join(label.itertext()) if label is not None else None)
        source = content.attrib.get("src", "") if content is not None else ""
        if title and source:
            entries.append((title, source[:2000]))
        if len(entries) > MAX_MOBI_CHAPTERS:
            raise ValueError("MOBI 目录项数量异常")
    return entries


def _chapters_from_mobi7(markup: str, entries: list[tuple[str, str]]) -> tuple[list[EpubChapter], bool]:
    located: list[tuple[int, str, str]] = []
    seen_positions: set[int] = set()
    for title, source in entries:
        target = source.partition("#")[2]
        if not target:
            continue
        anchor = re.search(
            rf"""(?is)<a\b[^>]*(?:id|name)\s*=\s*["']{re.escape(target)}["'][^>]*>""",
            markup,
        )
        if anchor is None or anchor.start() in seen_positions:
            continue
        seen_positions.add(anchor.start())
        located.append((anchor.start(), title, source))

    located.sort(key=lambda item: item[0])
    chapters: list[EpubChapter] = []
    for position, (start, title, source) in enumerate(located):
        slice_start = 0 if position == 0 else start
        slice_end = located[position + 1][0] if position + 1 < len(located) else len(markup)
        body = _plain_text(markup[slice_start:slice_end])
        if body:
            chapters.append(EpubChapter(title=title, body=body, href=source))
    if chapters:
        return chapters, True

    body = _plain_text(markup)
    if not body:
        raise ValueError("MOBI 没有可读取的正文")
    heading = re.search(r"(?is)<h[1-3][^>]*>(.*?)</h[1-3]>", markup)
    title = _plain_text(heading.group(1))[:500] if heading else "正文"
    return [EpubChapter(title=title or "正文", body=body, href="book.html")], False


def _mobi7_cover(
    mobi7_root: Path,
    manifest: dict[str, tuple[str, str]],
    cover_id: str | None,
) -> EpubCover | None:
    if not cover_id or cover_id not in manifest:
        return None
    href, mime_type = manifest[cover_id]
    path = _safe_child(mobi7_root, href)
    if not path.is_file():
        return None
    return EpubCover(
        data=_read_file(path, MAX_COVER_BYTES, "封面文件"),
        mime_type=mime_type.partition(";")[0].strip().lower() or "application/octet-stream",
    )


def _inspect_mobi7(root: Path, fallback_title: str) -> MobiBook:
    mobi7_root = root / "mobi7"
    package_path = mobi7_root / "content.opf"
    html_path = mobi7_root / "book.html"
    package = _xml_root(_read_file(package_path, MAX_MOBI_XML_BYTES, "内容包"))
    title, author, has_title_metadata, manifest, ncx_href, cover_id = _mobi7_metadata(
        package,
        fallback_title,
    )
    markup = _decode_html(_read_file(html_path, MAX_MOBI_HTML_BYTES, "正文"))
    entries = _navigation_entries(_safe_child(mobi7_root, ncx_href)) if ncx_href else []
    chapters, has_navigation = _chapters_from_mobi7(markup, entries)
    warnings: list[str] = []
    try:
        cover = _mobi7_cover(mobi7_root, manifest, cover_id)
    except Exception as exc:
        cover = None
        warnings.append(f"MOBI 内嵌封面提取失败：{exc}")
    return MobiBook(
        title=title,
        author=author,
        chapters=chapters,
        cover=cover,
        has_title_metadata=has_title_metadata,
        has_navigation=has_navigation,
        warnings=warnings,
    )


def inspect_mobi(path: Path) -> MobiBook:
    try:
        source_size = path.stat().st_size
    except OSError as exc:
        raise ValueError("无法读取 MOBI 文件") from exc
    if source_size > MAX_MOBI_SOURCE_BYTES:
        raise ValueError("MOBI 文件超过 256 MiB 导入上限")

    with _UNPACK_LOCK:
        with TemporaryDirectory(prefix="page-shelf-mobi-") as temporary:
            root = Path(temporary)
            try:
                unpackBook(str(path), str(root), epubver="A")
            except Exception as exc:
                message = str(exc)
                if "encrypt" in message.casefold() or "drm" in message.casefold():
                    raise ValueError("MOBI 已加密或受 DRM 保护，无法导入") from exc
                raise ValueError(f"MOBI 解包失败：{message or type(exc).__name__}") from exc

            _validate_extracted_tree(root)
            epub_files = sorted((root / "mobi8").glob("*.epub")) if (root / "mobi8").is_dir() else []
            if epub_files:
                epub = inspect_epub(epub_files[0])
                warnings: list[str] = []
                try:
                    cover = extract_epub_cover(epub_files[0])
                except Exception as exc:
                    cover = None
                    warnings.append(f"MOBI 内嵌封面提取失败：{exc}")
                return MobiBook(
                    title=epub.title,
                    author=epub.author,
                    chapters=epub.chapters,
                    cover=cover,
                    has_title_metadata=epub.has_title_metadata,
                    has_navigation=epub.has_navigation,
                    warnings=warnings,
                )

            if any(root.glob("*.001.pdf")):
                raise ValueError("MOBI Print Replica 固定版式暂不支持，请转换为 PDF 后导入")
            return _inspect_mobi7(root, path.stem)


def extract_mobi_cover(path: Path) -> EpubCover | None:
    return inspect_mobi(path).cover
