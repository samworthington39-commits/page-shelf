from __future__ import annotations

import zipfile

from sqlalchemy import select

from app.models import Book
from app.services.text_processor import chinese_number, roman_number, split_txt_chapters
from conftest import TEST_LIBRARY


def test_txt_detects_chapters_and_falls_back_to_body_segments(client, db_session):
    (TEST_LIBRARY / "chapters.txt").write_text(
        "第1章 开始\n第一段\n第2章 继续\n第二段\n第三章 终局\n第三段", encoding="utf-8"
    )
    (TEST_LIBRARY / "plain.txt").write_text("没有章节标题的正文", encoding="utf-8")

    response = client.post("/api/v1/library/scan")

    assert response.status_code == 200
    books = {book.title: book for book in db_session.scalars(select(Book))}
    assert [chapter.title for chapter in books["chapters"].chapters] == [
        "第1章 开始",
        "第2章 继续",
        "第三章 终局",
    ]
    assert books["plain"].chapter_count == 1
    assert books["plain"].chapters[0].body == "没有章节标题的正文"


def test_chapter_split_uses_only_chapter_marker_and_normalizes_title(client, db_session):
    content = (
        "第1章 龙城飞将\n正文一\n"
        "第二章 会猎吴越\n正文二\n"
        "第3章:听风\n正文三\n"
        "第4章\n正文四\n"
        "回想起第5章的内容，这一行不是章节标题\n接续\n"
        "5. 第6章 混在列表里\n第六段\n"
    )
    (TEST_LIBRARY / "titled.txt").write_text(content, encoding="utf-8")

    client.post("/api/v1/library/scan")

    books = {book.title: book for book in db_session.scalars(select(Book))}
    book = books["titled"]
    titles = [chapter.title for chapter in book.chapters]
    # 只有独占一行、且以“第XX章”开头的才被拆分；正文中的“第5章的内容”
    # 与列表里的“5. 第6章”都不会被误拆。
    assert titles == [
        "第1章 龙城飞将",
        "第二章 会猎吴越",
        "第3章 听风",
        "第4章",
    ]
    # 分隔符被规范化为单个空格。
    assert book.chapters[2].title == "第3章 听风"
    # 无标题章节只保留“第XX章”。
    assert book.chapters[3].title == "第4章"
    # 正文中的“第5章的内容”和列表项“5. 第6章”保留在第4章正文中。
    assert "回想起第5章的内容" in book.chapters[3].body
    assert "5. 第6章 混在列表里" in book.chapters[3].body


def test_chapter_split_ignores_non_chapter_markers(client, db_session):
    (TEST_LIBRARY / "mixed.txt").write_text(
        "第一卷 上部\n正文\n序章 楔子\n正文二\n第一章 真正的章节\n正文三", encoding="utf-8"
    )
    client.post("/api/v1/library/scan")
    books = {book.title: book for book in db_session.scalars(select(Book))}
    mixed = books["mixed"]
    # “第一卷”“序章”不再作为拆分标志，整本书合并成正文开篇 + 第一章。
    assert [chapter.title for chapter in mixed.chapters] == ["正文开篇", "第一章 真正的章节"]


def test_epub_uses_internal_nav_titles_and_spine_order(client, db_session):
    epub = TEST_LIBRARY / "book.epub"
    with zipfile.ZipFile(epub, "w") as archive:
        archive.writestr(
            "META-INF/container.xml",
            """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles>
            </container>""",
        )
        archive.writestr(
            "OPS/package.opf",
            """<package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>内部目录 EPUB</dc:title><dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c2" href="second.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="first.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>""",
        )
        archive.writestr(
            "OPS/nav.xhtml",
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><nav><ol>
              <li><a href="first.xhtml">目录中的第一章</a></li>
              <li><a href="second.xhtml">目录中的第二章</a></li>
            </ol></nav></body></html>""",
        )
        archive.writestr("OPS/first.xhtml", "<html><body><p>一</p></body></html>")
        archive.writestr("OPS/second.xhtml", "<html><body><p>二</p></body></html>")

    response = client.post("/api/v1/library/scan")

    assert response.status_code == 200
    book = db_session.scalar(select(Book))
    assert book.title == "内部目录 EPUB"
    assert [chapter.title for chapter in book.chapters] == ["目录中的第一章", "目录中的第二章"]
    assert [chapter.position for chapter in book.chapters] == [0, 1]


def test_epub_rejects_xml_entity_declarations(client, db_session):
    epub = TEST_LIBRARY / "unsafe.epub"
    with zipfile.ZipFile(epub, "w") as archive:
        archive.writestr(
            "META-INF/container.xml",
            """<?xml version="1.0"?>
            <!DOCTYPE container [<!ENTITY payload "unsafe">]>
            <container><rootfiles><rootfile full-path="package.opf"/></rootfiles></container>""",
        )

    response = client.post("/api/v1/library/scan")

    assert response.status_code == 200
    assert response.json()["failed"] == 1
    assert "实体声明" in response.json()["failures"][0]["warning"]
    assert db_session.scalar(select(Book)) is None


def test_expanded_split_recognizes_common_numbering_and_preserves_source_order():
    text = (
        "第 一 卷 少年游\n卷首正文\n"
        "第 〇 一 章：初入江湖\n正文一\n"
        "Chapter II - The Visitor\n正文二\n"
        "番外一 婚后生活\n正文三\n"
        "10\n正文十\n11\n正文十一\n12\n正文十二"
    )

    chapters = split_txt_chapters(text, mode="expanded")

    assert [chapter.original_title for chapter in chapters] == [
        "第 一 卷 少年游",
        "第 〇 一 章：初入江湖",
        "Chapter II - The Visitor",
        "番外一 婚后生活",
        "10",
        "11",
        "12",
    ]
    assert [chapter.source_position for chapter in chapters] == sorted(
        chapter.source_position for chapter in chapters
    )
    assert chapters[1].volume_index == 1
    assert chapters[1].chapter_index == 1
    assert chapters[2].chapter_index == 2
    assert chinese_number("第一千零二") is None
    assert chinese_number("一千零二") == 1002
    assert roman_number("XL") == 40
    assert roman_number("IIII") is None
