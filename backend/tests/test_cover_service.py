from __future__ import annotations

import zipfile
from pathlib import Path

import fitz
import pytest

from app.services.cover_service import (
    MAX_INPUT_DIMENSION,
    automatic_cover,
    generated_cover,
    store_cover_bytes,
)
from app.services.epub_processor import MAX_COVER_BYTES, extract_epub_cover


CONTAINER_XML = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""


def _png_bytes(width: int = 96, height: int = 144) -> bytes:
    document = fitz.open()
    try:
        page = document.new_page(width=width, height=height)
        page.draw_rect(page.rect, fill=(0.18, 0.42, 0.32), color=(0.18, 0.42, 0.32))
        page.draw_circle((width / 2, height / 2), min(width, height) / 4, fill=(0.92, 0.78, 0.42))
        return page.get_pixmap(alpha=False).tobytes("png")
    finally:
        document.close()


def _write_epub(path: Path, package: str, members: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/container.xml", CONTAINER_XML)
        archive.writestr("OEBPS/content.opf", package)
        for name, payload in members.items():
            archive.writestr(name, payload)
    return path


@pytest.mark.parametrize(
    "cover_declaration",
    [
        '<meta name="cover" content="cover-id"/>',
        "",
    ],
)
def test_epub2_and_epub3_internal_covers_are_extracted_and_normalized(tmp_path, cover_declaration):
    properties = "" if cover_declaration else ' properties="cover-image"'
    package = f"""<?xml version="1.0"?>
    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
      <metadata>{cover_declaration}</metadata>
      <manifest><item id="cover-id" href="images/cover.png" media-type="image/png"{properties}/></manifest>
      <spine/>
    </package>
    """
    image = _png_bytes()
    epub_path = _write_epub(tmp_path / "covered.epub", package, {"OEBPS/images/cover.png": image})

    extracted = extract_epub_cover(epub_path)
    result = automatic_cover(epub_path, "内封面测试", tmp_path / "covers", "abc123")

    assert extracted is not None
    assert extracted.data == image
    assert extracted.mime_type == "image/png"
    assert result.status == "ready"
    assert result.source == "epub"
    assert result.path is not None
    assert Path(result.path).read_bytes().startswith(b"\xff\xd8")


def test_epub2_guide_cover_page_resolves_its_first_image(tmp_path):
    package = """<?xml version="1.0"?>
    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
      <metadata/>
      <manifest>
        <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>
        <item id="cover-image" href="images/cover.png" media-type="image/png"/>
      </manifest>
      <spine/>
      <guide><reference type="cover" href="cover.xhtml"/></guide>
    </package>
    """
    image = _png_bytes()
    epub_path = _write_epub(
        tmp_path / "guide.epub",
        package,
        {
            "OEBPS/cover.xhtml": b'<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="images/cover.png"/></body></html>',
            "OEBPS/images/cover.png": image,
        },
    )

    extracted = extract_epub_cover(epub_path)

    assert extracted is not None
    assert extracted.data == image
    assert extracted.mime_type == "image/png"


def test_missing_cover_generates_bounded_cjk_fallback(tmp_path):
    source = tmp_path / "book.txt"
    source.write_text("正文", encoding="utf-8")

    result = automatic_cover(source, "中文书名测试", tmp_path / "covers", "fallback1")

    assert result.status == "ready"
    assert result.source == "generated"
    assert result.warnings == []
    destination = Path(result.path or "")
    assert destination.read_bytes().startswith(b"\xff\xd8")
    pixmap = fitz.Pixmap(destination)
    assert (pixmap.width, pixmap.height) == (720, 1080)


def test_epub_cover_rejects_traversal_and_oversized_zip_entry(tmp_path):
    traversal_package = """<package xmlns="http://www.idpf.org/2007/opf">
      <metadata/><manifest><item id="cover" href="../../escape.png" media-type="image/png" properties="cover-image"/></manifest><spine/>
    </package>"""
    traversal = _write_epub(tmp_path / "traversal.epub", traversal_package, {})
    with pytest.raises(ValueError, match="路径越界"):
        extract_epub_cover(traversal)

    oversized_package = """<package xmlns="http://www.idpf.org/2007/opf">
      <metadata/><manifest><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest><spine/>
    </package>"""
    oversized = _write_epub(
        tmp_path / "oversized.epub",
        oversized_package,
        {"OEBPS/cover.png": b"0" * (MAX_COVER_BYTES + 1)},
    )
    with pytest.raises(ValueError, match="过大"):
        extract_epub_cover(oversized)


def test_invalid_or_excessive_uploaded_image_is_not_written(tmp_path):
    destination = tmp_path / "cover.jpg"
    with pytest.raises(ValueError, match="JPEG 或 PNG"):
        store_cover_bytes(b"this is not an image", destination)
    assert not destination.exists()

    forged_png = (
        b"\x89PNG\r\n\x1a\n"
        + b"\x00\x00\x00\x0dIHDR"
        + (MAX_INPUT_DIMENSION + 1).to_bytes(4, "big")
        + (1).to_bytes(4, "big")
    )
    with pytest.raises(ValueError, match="像素尺寸"):
        store_cover_bytes(forged_png, destination)
    assert not destination.exists()


def test_valid_uploaded_png_is_reencoded_as_jpeg(tmp_path):
    destination = generated_cover("占位", tmp_path / "existing.jpg")
    stored = store_cover_bytes(_png_bytes(320, 480), destination)

    assert stored == destination.resolve()
    assert stored.read_bytes().startswith(b"\xff\xd8")
    pixmap = fitz.Pixmap(stored)
    assert (pixmap.width, pixmap.height) == (320, 480)
