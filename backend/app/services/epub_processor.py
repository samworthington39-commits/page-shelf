from __future__ import annotations

import html
import posixpath
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException


MAX_XML_BYTES = 4 * 1024 * 1024
MAX_COVER_BYTES = 12 * 1024 * 1024
MAX_CHAPTER_BYTES = 16 * 1024 * 1024
MAX_TOTAL_CHAPTER_BYTES = 256 * 1024 * 1024
MAX_SPINE_ITEMS = 10_000
MAX_COMPRESSION_RATIO = 250


@dataclass(slots=True)
class EpubChapter:
    title: str
    body: str
    href: str


@dataclass(slots=True)
class EpubBook:
    title: str
    author: str | None
    chapters: list[EpubChapter]
    has_title_metadata: bool = False
    has_navigation: bool = False


@dataclass(slots=True, frozen=True)
class EpubCover:
    data: bytes
    mime_type: str


def _member_path(base: PurePosixPath, href: str) -> str:
    raw = href.split("#", 1)[0].replace("\\", "/")
    normalized = posixpath.normpath(str(base / PurePosixPath(raw)))
    if not raw or "\x00" in raw or normalized.startswith("/") or normalized == ".." or normalized.startswith("../"):
        raise ValueError("EPUB 内部路径越界")
    return normalized


def _read_member(archive: zipfile.ZipFile, name: str, limit: int) -> bytes:
    try:
        info = archive.getinfo(name)
    except KeyError as exc:
        raise ValueError(f"EPUB 缺少必要文件：{name}") from exc
    if info.is_dir() or info.file_size > limit:
        raise ValueError(f"EPUB 内容项过大：{name}")
    if info.file_size and info.compress_size <= 0:
        raise ValueError(f"EPUB 内容项压缩信息异常：{name}")
    if info.file_size > 1024 * 1024 and info.file_size / info.compress_size > MAX_COMPRESSION_RATIO:
        raise ValueError(f"EPUB 内容项压缩比异常：{name}")
    return archive.read(info)


def _xml_root(payload: bytes) -> ElementTree.Element:
    upper = payload.upper()
    if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper:
        raise ValueError("EPUB XML 包含不允许的实体声明")
    try:
        root = ElementTree.fromstring(payload)
    except (ElementTree.ParseError, DefusedXmlException) as exc:
        raise ValueError("EPUB XML 格式无效") from exc
    if sum(1 for _ in root.iter()) > 100_000:
        raise ValueError("EPUB XML 结构过于复杂")
    return root


def _metadata_text(value: str | None, limit: int = 500) -> str | None:
    normalized = " ".join((value or "").split())
    return normalized[:limit] or None


def _plain_text(markup: str) -> str:
    markup = re.sub(r"(?is)<(script|style).*?>.*?</\1>", "", markup)
    markup = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</h[1-6]>", "\n", markup)
    return html.unescape(re.sub(r"(?s)<[^>]+>", "", markup)).strip()


def _package_document(archive: zipfile.ZipFile) -> tuple[PurePosixPath, ElementTree.Element]:
    container = _xml_root(_read_member(archive, "META-INF/container.xml", MAX_XML_BYTES))
    rootfile = next((node for node in container.iter() if node.tag.endswith("rootfile")), None)
    if rootfile is None or not rootfile.attrib.get("full-path"):
        raise ValueError("EPUB 未声明内容包")
    package_path = PurePosixPath(_member_path(PurePosixPath("."), rootfile.attrib["full-path"]))
    return package_path, _xml_root(_read_member(archive, str(package_path), MAX_XML_BYTES))


def extract_epub_cover(path: Path) -> EpubCover | None:
    """Return the declared EPUB cover without extracting any archive path to disk.

    EPUB 3 ``cover-image`` has priority, followed by the EPUB 2 ``meta name=cover``
    convention and the older guide reference. All member paths and sizes pass through
    the same traversal, compression-ratio and uncompressed-size checks as chapters.
    """
    with zipfile.ZipFile(path) as archive:
        package_path, package = _package_document(archive)
        base = package_path.parent
        manifest: dict[str, tuple[str, str]] = {}
        epub3_cover_id: str | None = None
        epub2_cover_id: str | None = None
        guide_cover_href: str | None = None

        for node in package.iter():
            local = node.tag.rsplit("}", 1)[-1]
            if local == "item":
                item_id = node.attrib.get("id", "")
                href = node.attrib.get("href", "")
                mime_type = node.attrib.get("media-type", "application/octet-stream")
                if item_id and href:
                    manifest[item_id] = (href, mime_type)
                    if "cover-image" in node.attrib.get("properties", "").split():
                        epub3_cover_id = epub3_cover_id or item_id
            elif local == "meta" and node.attrib.get("name", "").casefold() == "cover":
                epub2_cover_id = epub2_cover_id or node.attrib.get("content")
            elif local == "reference" and "cover" in node.attrib.get("type", "").casefold().split():
                guide_cover_href = guide_cover_href or node.attrib.get("href")

        declared: tuple[str, str] | None = None
        declared_from_guide = False
        for item_id in (epub3_cover_id, epub2_cover_id):
            if item_id and item_id in manifest:
                declared = manifest[item_id]
                break
        if declared is None and guide_cover_href:
            declared = (guide_cover_href, "application/octet-stream")
            declared_from_guide = True
        if declared is None:
            return None

        href, mime_type = declared
        cover_path = _member_path(base, href)
        payload = _read_member(archive, cover_path, MAX_COVER_BYTES)
        normalized_mime = mime_type.partition(";")[0].strip().lower() or "application/octet-stream"
        if declared_from_guide and not normalized_mime.startswith("image/"):
            guide_root = _xml_root(payload)
            image_node = next(
                (node for node in guide_root.iter() if node.tag.rsplit("}", 1)[-1] in {"img", "image"}),
                None,
            )
            if image_node is not None:
                image_href = image_node.attrib.get("src") or next(
                    (
                        value
                        for attribute, value in image_node.attrib.items()
                        if attribute.rsplit("}", 1)[-1] == "href"
                    ),
                    None,
                )
                if image_href:
                    image_path = _member_path(PurePosixPath(cover_path).parent, image_href)
                    payload = _read_member(archive, image_path, MAX_COVER_BYTES)
                    normalized_mime = "application/octet-stream"
                    for candidate_href, candidate_mime in manifest.values():
                        try:
                            matches_image = _member_path(base, candidate_href) == image_path
                        except ValueError:
                            continue
                        if matches_image:
                            normalized_mime = candidate_mime.partition(";")[0].strip().lower()
                            break
        return EpubCover(data=payload, mime_type=normalized_mime)


def _navigation_titles(
    archive: zipfile.ZipFile,
    package_base: PurePosixPath,
    nav_href: str | None,
    ncx_href: str | None,
) -> dict[str, str]:
    titles: dict[str, str] = {}
    navigation_href = nav_href or ncx_href
    if not navigation_href:
        return titles
    try:
        navigation_path = PurePosixPath(_member_path(package_base, navigation_href))
    except ValueError:
        return titles
    try:
        root = _xml_root(_read_member(archive, str(navigation_path), MAX_XML_BYTES))
    except ValueError:
        return titles

    if nav_href:
        for node in root.iter():
            if node.tag.rsplit("}", 1)[-1] != "a" or not node.attrib.get("href"):
                continue
            try:
                target = _member_path(navigation_path.parent, node.attrib["href"])
            except ValueError:
                continue
            title = " ".join("".join(node.itertext()).split())
            if title:
                titles.setdefault(target, title[:500])
        return titles

    for nav_point in (node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == "navPoint"):
        label = next((node for node in nav_point.iter() if node.tag.rsplit("}", 1)[-1] == "text"), None)
        content = next((node for node in nav_point.iter() if node.tag.rsplit("}", 1)[-1] == "content"), None)
        if label is None or content is None or not label.text or not content.attrib.get("src"):
            continue
        try:
            target = _member_path(navigation_path.parent, content.attrib["src"])
        except ValueError:
            continue
        titles.setdefault(target, label.text.strip()[:500])
    return titles


def inspect_epub(path: Path) -> EpubBook:
    with zipfile.ZipFile(path) as archive:
        package_path, package = _package_document(archive)
        base = package_path.parent

        title = path.stem
        has_title_metadata = False
        author: str | None = None
        manifest: dict[str, str] = {}
        spine: list[str] = []
        nav_href: str | None = None
        ncx_href: str | None = None
        for node in package.iter():
            local = node.tag.rsplit("}", 1)[-1]
            if local == "title" and node.text and title == path.stem:
                metadata_title = _metadata_text(node.text)
                if metadata_title:
                    title = metadata_title
                    has_title_metadata = True
            elif local == "creator" and node.text and author is None:
                author = _metadata_text(node.text)
            elif local == "item":
                href = node.attrib.get("href", "")
                manifest[node.attrib.get("id", "")] = href
                if "nav" in node.attrib.get("properties", "").split():
                    nav_href = href
                if node.attrib.get("media-type") == "application/x-dtbncx+xml":
                    ncx_href = href
            elif local == "itemref":
                spine.append(node.attrib.get("idref", ""))

        if len(spine) > MAX_SPINE_ITEMS:
            raise ValueError("EPUB 阅读项数量异常")

        navigation_titles = _navigation_titles(archive, base, nav_href, ncx_href)
        chapters: list[EpubChapter] = []
        total_chapter_bytes = 0
        for position, item_id in enumerate(spine):
            href = manifest.get(item_id)
            if not href:
                continue
            try:
                content_path = _member_path(base, href)
                payload = _read_member(archive, content_path, MAX_CHAPTER_BYTES)
            except ValueError:
                continue
            total_chapter_bytes += len(payload)
            if total_chapter_bytes > MAX_TOTAL_CHAPTER_BYTES:
                raise ValueError("EPUB 章节文本总量过大")
            markup = payload.decode("utf-8", errors="replace")
            heading = re.search(r"(?is)<h[1-3][^>]*>(.*?)</h[1-3]>", markup)
            chapter_title = navigation_titles.get(content_path)
            if not chapter_title:
                chapter_title = _plain_text(heading.group(1)) if heading else f"阅读项 {position + 1}"
            chapters.append(EpubChapter(chapter_title[:500], _plain_text(markup), href[:2000]))
        return EpubBook(
            title=title,
            author=author,
            chapters=chapters,
            has_title_metadata=has_title_metadata,
            has_navigation=bool(navigation_titles),
        )
