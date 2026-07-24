from __future__ import annotations

from pathlib import Path

from pypdf import PdfWriter


def create_pdf(
    path: Path,
    *,
    pages: int = 3,
    metadata: bool = True,
    bookmarks: bool = False,
    password: str | None = None,
) -> Path:
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=612, height=792)
    if metadata:
        writer.add_metadata(
            {
                "/Title": "测试 PDF",
                "/Author": "测试作者",
                "/Subject": "固定版式阅读",
                "/Keywords": "PDF,阅读器",
                "/CreationDate": "D:20240102030405+08'00'",
                "/ModDate": "D:20240203040506+08'00'",
            }
        )
    if bookmarks:
        preface = writer.add_outline_item("前言", 0)
        writer.add_outline_item("说明", min(1, pages - 1), parent=preface)
        writer.add_outline_item("正文", pages - 1)
    if password:
        writer.encrypt(password)
    with path.open("wb") as destination:
        writer.write(destination)
    return path

