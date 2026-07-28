from __future__ import annotations

import zipfile
from pathlib import Path

import fitz
import pytest
from sqlalchemy import select

from app.models import Book
from app.services import mobi_processor
from app.services.mobi_processor import inspect_mobi
from conftest import TEST_LIBRARY


def _png_bytes() -> bytes:
    document = fitz.open()
    try:
        page = document.new_page(width=96, height=144)
        page.draw_rect(page.rect, fill=(0.18, 0.42, 0.32), color=(0.18, 0.42, 0.32))
        return page.get_pixmap(alpha=False).tobytes("png")
    finally:
        document.close()


def _write_mobi7_output(output: Path, cover: bytes | None = None) -> None:
    mobi7 = output / "mobi7"
    images = mobi7 / "Images"
    images.mkdir(parents=True)
    (mobi7 / "content.opf").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>内部 MOBI 书名</dc:title>
            <dc:creator>测试作者</dc:creator>
            <meta name="cover" content="cover-id"/>
          </metadata>
          <manifest>
            <item id="html" href="book.html" media-type="text/html"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="cover-id" href="Images/cover.png" media-type="image/png"/>
          </manifest>
          <spine toc="ncx"><itemref idref="html"/></spine>
        </package>""",
        encoding="utf-8",
    )
    (mobi7 / "toc.ncx").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
          <navMap>
            <navPoint><navLabel><text>目录第一章</text></navLabel><content src="book.html#filepos10"/></navPoint>
            <navPoint><navLabel><text>目录第二章</text></navLabel><content src="book.html#filepos20"/></navPoint>
          </navMap>
        </ncx>""",
        encoding="utf-8",
    )
    (mobi7 / "book.html").write_text(
        """<html><head><meta charset="utf-8"/></head><body>
        <a id="filepos10"/><h1>原文第一章</h1><p>第一章正文</p>
        <a id="filepos20"/><h1>原文第二章</h1><p>第二章正文</p>
        </body></html>""",
        encoding="utf-8",
    )
    if cover is not None:
        (images / "cover.png").write_bytes(cover)


def _write_epub_output(output: Path) -> None:
    mobi8 = output / "mobi8"
    mobi8.mkdir(parents=True)
    with zipfile.ZipFile(mobi8 / "modern.epub", "w") as archive:
        archive.writestr(
            "META-INF/container.xml",
            """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
        )
        archive.writestr(
            "OPS/package.opf",
            """<package xmlns="http://www.idpf.org/2007/opf">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>现代 MOBI</dc:title><dc:creator>现代作者</dc:creator>
            </metadata>
            <manifest>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="chapter"/></spine></package>""",
        )
        archive.writestr(
            "OPS/nav.xhtml",
            """<html><body><nav><a href="chapter.xhtml">现代目录</a></nav></body></html>""",
        )
        archive.writestr(
            "OPS/chapter.xhtml",
            """<html><body><h1>现代目录</h1><p>现代正文</p></body></html>""",
        )


def test_mobi7_extracts_metadata_navigation_cover_and_cleans_temp_directory(tmp_path, monkeypatch):
    source = tmp_path / "legacy.mobi"
    source.write_bytes(b"not-a-real-mobi")
    temporary_roots: list[Path] = []
    cover = _png_bytes()

    def fake_unpack(_source, output, **_kwargs):
        root = Path(output)
        temporary_roots.append(root)
        _write_mobi7_output(root, cover)

    monkeypatch.setattr(mobi_processor, "unpackBook", fake_unpack)

    book = inspect_mobi(source)

    assert book.title == "内部 MOBI 书名"
    assert book.author == "测试作者"
    assert book.has_title_metadata is True
    assert book.has_navigation is True
    assert [chapter.title for chapter in book.chapters] == ["目录第一章", "目录第二章"]
    assert "第一章正文" in book.chapters[0].body
    assert "第二章正文" in book.chapters[1].body
    assert book.cover is not None
    assert book.cover.data == cover
    assert temporary_roots and not temporary_roots[0].exists()


def test_modern_mobi_reuses_epub_spine_and_navigation(tmp_path, monkeypatch):
    source = tmp_path / "modern.mobi"
    source.write_bytes(b"not-a-real-mobi")
    monkeypatch.setattr(
        mobi_processor,
        "unpackBook",
        lambda _source, output, **_kwargs: _write_epub_output(Path(output)),
    )

    book = inspect_mobi(source)

    assert book.title == "现代 MOBI"
    assert book.author == "现代作者"
    assert book.has_navigation is True
    assert [(chapter.title, chapter.body) for chapter in book.chapters] == [
        ("现代目录", "现代目录\n现代正文")
    ]


def test_encrypted_and_print_replica_mobi_are_rejected(tmp_path, monkeypatch):
    source = tmp_path / "protected.mobi"
    source.write_bytes(b"not-a-real-mobi")

    def encrypted(*_args, **_kwargs):
        raise RuntimeError("Book is encrypted")

    monkeypatch.setattr(mobi_processor, "unpackBook", encrypted)
    with pytest.raises(ValueError, match="DRM"):
        inspect_mobi(source)

    def print_replica(_source, output, **_kwargs):
        (Path(output) / "protected.001.pdf").write_bytes(b"%PDF-1.4")

    monkeypatch.setattr(mobi_processor, "unpackBook", print_replica)
    with pytest.raises(ValueError, match="Print Replica"):
        inspect_mobi(source)


def test_scan_imports_mobi_for_reader_and_offline_capabilities(client, db_session, monkeypatch):
    source = TEST_LIBRARY / "legacy.mobi"
    source.write_bytes(b"not-a-real-mobi")
    cover = _png_bytes()
    monkeypatch.setattr(
        mobi_processor,
        "unpackBook",
        lambda _source, output, **_kwargs: _write_mobi7_output(Path(output), cover),
    )

    scan = client.post("/api/v1/library/scan")

    assert scan.status_code == 200
    assert scan.json()["imported"] == 1
    assert scan.json()["failed"] == 0
    book = db_session.scalar(select(Book))
    assert book is not None
    assert book.format == "mobi"
    assert book.mime_type == "application/x-mobipocket-ebook"
    assert book.title == "内部 MOBI 书名"
    assert book.author == "测试作者"
    assert book.chapter_count == 2
    assert book.metadata_overrides_json["automatic_cover_source"] == "mobi"

    detail = client.get(f"/api/v1/books/{book.id}")
    toc = client.get(f"/api/v1/books/{book.id}/toc")
    first_chapter = client.get(f"/api/v1/books/{book.id}/chapters/{book.chapters[0].id}")
    progress = client.put(
        f"/api/v1/books/{book.id}/progress/mobi-test",
        json={
            "progression": 0.25,
            "locator_json": {
                "type": "mobi",
                "chapter_id": book.chapters[0].id,
                "chapter_index": 0,
            },
        },
    )

    assert detail.status_code == 200
    assert detail.json()["capabilities"] == {
        "chapters": True,
        "reflowable_text": True,
        "font_settings": True,
        "page_navigation": False,
        "zoom": False,
        "offline_download": True,
        "progress_sync": True,
    }
    assert [item["title"] for item in toc.json()["items"]] == ["目录第一章", "目录第二章"]
    assert "第一章正文" in first_chapter.json()["body"]
    assert progress.status_code == 200
    assert progress.json()["locator_json"]["type"] == "mobi"
