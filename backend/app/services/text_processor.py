from __future__ import annotations

import re
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Literal


SplitMode = Literal["auto", "strict", "expanded", "fixed", "single", "source"]
SUPPORTED_SPLIT_MODES = {"auto", "strict", "expanded", "fixed", "single", "source"}
DEFAULT_SEGMENT_SIZE = 12_000
MIN_SEGMENT_SIZE = 1_000
MAX_SEGMENT_SIZE = 100_000
MAX_TEXT_BYTES = 256 * 1024 * 1024

_CN_DIGITS = {"零": 0, "〇": 0, "○": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
              "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
_CN_UNITS = {"十": 10, "百": 100, "千": 1_000, "万": 10_000}
_NUMBER_CHARACTER = r"[0-9零〇○一二两三四五六七八九十百千万]"
_NUMBER = rf"{_NUMBER_CHARACTER}(?:\s*{_NUMBER_CHARACTER})*"
_ROMAN = r"[IVXLCDM]+"
_TRAILING_SEPARATOR = r"(?:\s*[:：、,，.．·|\-—–]+\s*|\s+)"

_CHINESE_CHAPTER = re.compile(
    rf"^(?:第|弟)?\s*(?P<number>{_NUMBER})\s*(?:(?P<letter>[A-Za-z])|[-.]\s*(?P<secondary>\d+))?\s*"
    rf"(?P<kind>章|章节|回|張)(?P<rest>.*)$",
    re.IGNORECASE,
)
_CHINESE_STRUCTURE = re.compile(
    rf"^(?:(?:第\s*)?(?P<number>{_NUMBER})\s*(?P<kind>卷|部|篇|集|册|幕)|"
    rf"(?P<prefix>卷|部|篇)\s*(?P<prefix_number>{_NUMBER}))(?P<rest>.*)$",
    re.IGNORECASE,
)
_CHINESE_SECTION = re.compile(
    rf"^(?:第\s*)?(?P<number>{_NUMBER})\s*(?P<kind>节|小节)(?P<rest>.*)$",
    re.IGNORECASE,
)
_ENGLISH_CHAPTER = re.compile(
    rf"^(?P<kind>chapter|chap\.?|ch\.?)\s+(?P<number>\d+|{_ROMAN})\b(?P<rest>.*)$",
    re.IGNORECASE,
)
_ENGLISH_STRUCTURE = re.compile(
    rf"^(?P<kind>part|book)\s+(?P<number>\d+|{_ROMAN}|one|two|three|four|five|six|seven|eight|nine|ten)\b(?P<rest>.*)$",
    re.IGNORECASE,
)
_SPECIAL = re.compile(
    r"^(?P<kind>序章|楔子|引子|引言|前言|序言|开篇|序幕|前传|正文|终章|尾声|后记|附录|"
    r"番外|番外篇|外传|特别篇|间章|幕间|插曲|补遗|作者的话|作者有话说|完本感言|后日谈)"
    rf"(?P<number>{_NUMBER}|\d+)?(?P<rest>.*)$",
)
_NUMERIC = re.compile(
    r"^[【\[(（]?\s*(?P<number>\d{1,6})(?:[-.](?P<secondary>\d+))?\s*[】\])）]?"
    r"\s*(?:[.．、:：\-—]\s*)?(?P<rest>.{0,40})$"
)
_SUFFIX = re.compile(r"^[\s:：·（(【]*(上篇|上部|上|中篇|中部|中|下篇|下部|下)[）)】]?")


@dataclass(slots=True)
class TextChapter:
    title: str
    body: str
    original_title: str | None = None
    normalized_title: str | None = None
    volume_index: int | None = None
    chapter_index: int | None = None
    secondary_index: int | None = None
    suffix_order: int = 0
    level: str = "chapter"
    special_type: str | None = None
    start_offset: int | None = None
    end_offset: int | None = None
    source_position: int | None = None


@dataclass(slots=True)
class TextSplitResult:
    chapters: list[TextChapter]
    warnings: list[str]


@dataclass(slots=True)
class _Candidate:
    start: int
    end: int
    original: str
    normalized: str
    style: str
    level: str
    number: int | None = None
    secondary: int | None = None
    suffix_order: int = 0
    special_type: str | None = None


def read_text(path: Path) -> str:
    if path.stat().st_size > MAX_TEXT_BYTES:
        raise ValueError("TXT 文件超过 256 MB，拒绝一次性载入以保护服务器内存")
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-16", "gb18030", "big5"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def chinese_number(value: str) -> int | None:
    """Convert common Chinese numerals without changing their displayed spelling."""
    value = unicodedata.normalize("NFKC", value).replace(" ", "")
    if value.isdigit():
        return int(value)
    if not value or any(character not in _CN_DIGITS and character not in _CN_UNITS for character in value):
        return None
    total = section = number = 0
    for character in value:
        if character in _CN_DIGITS:
            number = _CN_DIGITS[character]
            continue
        unit = _CN_UNITS[character]
        if unit == 10_000:
            section += number
            total += (section or 1) * unit
            section = number = 0
        else:
            section += (number or 1) * unit
            number = 0
    return total + section + number


def roman_number(value: str) -> int | None:
    values = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1_000}
    normalized = value.upper()
    if not normalized or any(character not in values for character in normalized):
        return None
    total = 0
    previous = 0
    for character in reversed(normalized):
        current = values[character]
        total += -current if current < previous else current
        previous = max(previous, current)
    # Reject arbitrary Roman-looking words and non-canonical spellings.
    return total if 0 < total <= 3999 and _to_roman(total) == normalized else None


def _to_roman(value: int) -> str:
    result = []
    for number, token in ((1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"),
                          (90, "XC"), (50, "L"), (40, "XL"), (10, "X"), (9, "IX"),
                          (5, "V"), (4, "IV"), (1, "I")):
        while value >= number:
            result.append(token)
            value -= number
    return "".join(result)


def _parse_number(value: str) -> int | None:
    normalized = unicodedata.normalize("NFKC", value).strip()
    if normalized.isdigit():
        return int(normalized)
    english = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
               "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
    if normalized.lower() in english:
        return english[normalized.lower()]
    return roman_number(normalized) if re.fullmatch(_ROMAN, normalized, re.IGNORECASE) else chinese_number(normalized)


def _suffix_order(rest: str) -> int:
    match = _SUFFIX.match(rest)
    if not match:
        return 0
    return {"上": 1, "上篇": 1, "上部": 1, "中": 2, "中篇": 2, "中部": 2,
            "下": 3, "下篇": 3, "下部": 3}[match.group(1)]


def _clean_title(raw: str) -> str:
    """Keep the historical normalized title while original_title preserves exact display text."""
    text = unicodedata.normalize("NFKC", raw).strip()
    text = re.sub(r"^(第|弟)\s+", r"\1", text)
    text = re.sub(rf"^((?:第|弟)?\s*{_NUMBER})\s+(章|章节|回|卷|部|篇|集|册|幕|节)", r"\1\2", text)
    text = re.sub(_TRAILING_SEPARATOR, " ", text, count=1) if re.match(
        rf"^(?:(?:第|弟)?\s*{_NUMBER}\s*(?:章|章节|回|卷|部|篇|集|册|幕|节)|"
        rf"(?:chapter|chap\.?|ch\.?|part|book)\s+(?:\d+|{_ROMAN}))",
        text,
        re.IGNORECASE,
    ) else text
    return re.sub(r"[ \t　]+", " ", text).strip()


def _candidate(line: str, start: int, end: int) -> _Candidate | None:
    original = line.strip(" \t\r\n　")
    if not original or len(original) > 100:
        return None
    normalized_line = unicodedata.normalize("NFKC", original)

    for pattern, style, level in (
        (_CHINESE_CHAPTER, "chapter-cn", "chapter"),
        (_ENGLISH_CHAPTER, "chapter-en", "chapter"),
        (_CHINESE_STRUCTURE, "structure-cn", "volume"),
        (_ENGLISH_STRUCTURE, "structure-en", "volume"),
        (_CHINESE_SECTION, "section-cn", "section"),
    ):
        match = pattern.fullmatch(normalized_line)
        if not match:
            continue
        groups = match.groupdict()
        number_text = groups.get("number") or groups.get("prefix_number") or ""
        number = _parse_number(number_text)
        if number is None:
            continue
        rest = groups.get("rest") or ""
        secondary = int(groups["secondary"]) if groups.get("secondary") else None
        if groups.get("letter"):
            secondary = ord(groups["letter"].upper()) - ord("A") + 1
        return _Candidate(start, end, original, _clean_title(original), style, level, number,
                          secondary, _suffix_order(rest))

    special = _SPECIAL.fullmatch(normalized_line)
    if special:
        if special.group("kind") == "正文" and (special.group("number") or special.group("rest").strip()):
            return None
        number = _parse_number(special.group("number")) if special.group("number") else None
        return _Candidate(start, end, original, _clean_title(original), "special", "special", number,
                          special_type=special.group("kind"))

    numeric = _NUMERIC.fullmatch(normalized_line)
    if numeric:
        return _Candidate(start, end, original, original, "numeric", "chapter",
                          int(numeric.group("number")),
                          int(numeric.group("secondary")) if numeric.group("secondary") else None)
    return None


def _numeric_candidates_are_consistent(candidates: list[_Candidate]) -> bool:
    numbered = [candidate for candidate in candidates if candidate.style == "numeric"]
    if len(numbered) < 3:
        return False
    transitions = [right.number - left.number for left, right in zip(numbered, numbered[1:])
                   if left.number is not None and right.number is not None]
    return bool(transitions) and sum(1 <= difference <= 5 for difference in transitions) / len(transitions) >= 0.6


def _select_candidates(candidates: list[_Candidate], mode: SplitMode) -> list[_Candidate]:
    strict = [candidate for candidate in candidates if candidate.style.startswith("chapter-")]
    if mode == "strict":
        return strict
    counts = Counter(candidate.style for candidate in candidates)
    special_counts = Counter(candidate.special_type for candidate in candidates if candidate.style == "special")
    numeric_ok = _numeric_candidates_are_consistent(candidates)
    if mode == "expanded":
        return [candidate for candidate in candidates if candidate.style != "numeric" or numeric_ok]
    # Auto mode is conservative about one-off structural/special lines when a dominant chapter style exists.
    selected = list(strict)
    selected.extend(candidate for candidate in candidates if candidate.style.startswith("structure-")
                    and (counts[candidate.style] >= 2 or not strict))
    selected.extend(candidate for candidate in candidates if candidate.style == "section-cn"
                    and counts[candidate.style] >= 2)
    selected.extend(candidate for candidate in candidates if candidate.style == "special"
                    and candidate.special_type != "正文"
                    and (special_counts[candidate.special_type] >= 2 or not strict))
    if numeric_ok and not strict:
        selected.extend(candidate for candidate in candidates if candidate.style == "numeric")
    return sorted({candidate.start: candidate for candidate in selected}.values(), key=lambda item: item.start)


def _fixed_segments(text: str, segment_size: int) -> list[TextChapter]:
    normalized = text.strip()
    if not normalized:
        return [TextChapter(title="正文", original_title="正文", normalized_title="正文", body="")]
    chapters: list[TextChapter] = []
    start = 0
    while start < len(normalized):
        target = min(start + segment_size, len(normalized))
        if target < len(normalized):
            paragraph = normalized.rfind("\n", start + segment_size // 2, target + 1)
            if paragraph > start:
                target = paragraph + 1
        body = normalized[start:target].strip()
        title = f"正文 {len(chapters) + 1}"
        chapters.append(TextChapter(title, body, title, title, start_offset=start, end_offset=target,
                                    source_position=start))
        start = target
    return chapters


def split_text_with_warnings(
    text: str,
    mode: SplitMode = "auto",
    segment_size: int = DEFAULT_SEGMENT_SIZE,
) -> TextSplitResult:
    if mode not in SUPPORTED_SPLIT_MODES - {"source"}:
        raise ValueError(f"TXT 不支持拆分方式：{mode}")
    if not MIN_SEGMENT_SIZE <= segment_size <= MAX_SEGMENT_SIZE:
        raise ValueError(f"固定分段字数必须在 {MIN_SEGMENT_SIZE} 到 {MAX_SEGMENT_SIZE} 之间")
    if mode == "single":
        body = text.strip()
        return TextSplitResult([TextChapter("正文", body, "正文", "正文", start_offset=0,
                                            end_offset=len(text), source_position=0)], [])
    if mode == "fixed":
        return TextSplitResult(_fixed_segments(text, segment_size), [])

    candidates: list[_Candidate] = []
    offset = 0
    for line in text.splitlines(keepends=True):
        end = offset + len(line)
        item = _candidate(line, offset, end)
        if item:
            candidates.append(item)
        offset = end
    if offset < len(text):
        item = _candidate(text[offset:], offset, len(text))
        if item:
            candidates.append(item)
    selected = _select_candidates(candidates, mode)
    warnings: list[str] = []

    if len(selected) > max(100, len(text.splitlines()) // 3):
        selected = [candidate for candidate in selected if candidate.style != "numeric"]
        warnings.append("候选标题过多，已禁用低可信度纯数字规则")

    if not selected:
        warnings.append("未找到可靠章节标题，已将整本书作为单章")
        return TextSplitResult([TextChapter("正文", text.strip(), "正文", "正文", start_offset=0,
                                            end_offset=len(text), source_position=0)], warnings)

    chapters: list[TextChapter] = []
    preamble = text[:selected[0].start].strip()
    if preamble:
        chapters.append(TextChapter("正文开篇", preamble, "正文开篇", "正文开篇", start_offset=0,
                                    end_offset=selected[0].start, source_position=0))
    current_volume: int | None = None
    for index, item in enumerate(selected):
        end = selected[index + 1].start if index + 1 < len(selected) else len(text)
        body = text[item.end:end].strip()
        if item.level == "volume":
            current_volume = item.number
        chapters.append(TextChapter(
            title=item.normalized,
            body=body,
            original_title=item.original,
            normalized_title=item.normalized,
            volume_index=current_volume,
            chapter_index=item.number if item.level in {"chapter", "section"} else None,
            secondary_index=item.secondary,
            suffix_order=item.suffix_order,
            level=item.level,
            special_type=item.special_type,
            start_offset=item.start,
            end_offset=end,
            source_position=item.start,
        ))

    empty_ratio = sum(not chapter.body for chapter in chapters) / len(chapters)
    average_length = sum(len(chapter.body) for chapter in chapters) / len(chapters)
    if mode == "auto" and len(chapters) >= 8 and (empty_ratio > 0.5 or average_length < 30):
        conservative = split_text_with_warnings(text, "strict", segment_size)
        if len(conservative.chapters) < len(chapters):
            conservative.warnings.insert(0, "智能识别结果过密，已回退到严格标题规则")
            return conservative
    return TextSplitResult(chapters, warnings)


def split_txt_chapters(
    text: str,
    segment_size: int = DEFAULT_SEGMENT_SIZE,
    mode: SplitMode = "auto",
) -> list[TextChapter]:
    """Compatibility wrapper used by callers that only need the chapter list."""
    return split_text_with_warnings(text, mode=mode, segment_size=segment_size).chapters
