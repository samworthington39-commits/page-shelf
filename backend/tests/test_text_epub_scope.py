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


def test_classical_chinese_split_patterns():
    body = "古书正文" * 12  # 保持每章正文足够长，避免触发短正文回退
    text = (
        "卷之一 少年游\n" + body + "\n"
        "卷之二 凤求凰\n" + body + "\n"
        "学而第一\n" + body + "\n"
        "为政第二\n" + body + "\n"
        "项羽本纪\n" + body + "\n"
        "高祖本纪\n" + body + "\n"
        "其一\n" + body + "\n"
        "其二\n" + body + "\n"
        "第三回 甄士隐梦幻识通灵\n" + body + "\n"
        "第四回 贾夫人仙逝扬州城\n" + body
    )

    chapters = split_txt_chapters(text, mode="auto")

    assert [chapter.title for chapter in chapters] == [
        "卷之一 少年游",
        "卷之二 凤求凰",
        "学而第一",
        "为政第二",
        "项羽本纪",
        "高祖本纪",
        "其一",
        "其二",
        "第三回 甄士隐梦幻识通灵",
        "第四回 贾夫人仙逝扬州城",
    ]
    assert [chapter.volume_index for chapter in chapters[:2]] == [1, 2]
    assert chapters[0].level == "volume"
    assert chapters[2].level == "chapter"


def test_classical_volume_forms_and_conservative_auto_selection():
    # 卷上/中/下与“第X则”在 auto 模式下应被识别。
    volumes = split_txt_chapters("卷上\n上正文\n卷中\n中正文\n卷下\n下正文", mode="auto")
    assert [chapter.title for chapter in volumes] == ["卷上", "卷中", "卷下"]
    assert [chapter.volume_index for chapter in volumes] == [1, 2, 3]

    guwen = split_txt_chapters(
        "第一则 刻舟求剑\n楚人有涉江者\n第二则 守株待兔\n宋人有耕者",
        mode="auto",
    )
    assert [chapter.title for chapter in guwen] == ["第一则 刻舟求剑", "第二则 守株待兔"]

    # 现代文中零星的“卷一”不应在存在严格章节标记时被当作拆分点。
    modern = split_txt_chapters(
        "卷一 简介\n第一章 开始\n正文\n第二章 继续\n正文",
        mode="auto",
    )
    # “卷一 简介”只出现一次，不会被当作拆分点，作为开篇正文保留。
    assert [chapter.title for chapter in modern] == ["正文开篇", "第一章 开始", "第二章 继续"]


def test_classical_mode_is_a_distinct_split_option():
    body = "古籍正文" * 12  # 保持每章正文足够长，避免触发短正文回退
    text = (
        "第一章 白话序\n" + body + "\n"
        "卷一 少年游\n" + body + "\n"
        "学而第一\n" + body + "\n"
        "其一\n" + body + "\n"
        "Chapter 2 - English\n" + body + "\n"
        "10\n" + body
    )

    classical = split_txt_chapters(text, mode="classical")
    assert [chapter.title for chapter in classical] == [
        "第一章 白话序",
        "卷一 少年游",
        "学而第一",
        "其一",
    ]

    # auto 模式对零星的古籍标记保守：只有严格章节与英文章节被保留。
    auto = split_txt_chapters(text, mode="auto")
    assert [chapter.title for chapter in auto] == ["第一章 白话序", "Chapter 2 - English"]
