#!/usr/bin/env python3
"""Build the compact Matcha phrase lexicon used by the Android app."""

from __future__ import annotations

import argparse
import hashlib
import tempfile
import unicodedata
import urllib.request
from collections import OrderedDict, defaultdict
from pathlib import Path


UPSTREAM_COMMIT = "cee0ed6e6e4898580cafd2bd5e3723e20b214aa0"
UPSTREAM_URL = (
    "https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/"
    f"{UPSTREAM_COMMIT}/large_pinyin.txt"
)
UPSTREAM_SHA256 = "4cf565635a092f3911a7f560fc604aa1a05c95a121488fb28562f3dd2d1441f7"
ANDROID_ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = ANDROID_ROOT / "app/src/main/assets/tts/matcha_zh_en"
DEFAULT_OVERRIDES = ANDROID_ROOT / "tts-data/novel-polyphone-overrides.txt"
DEFAULT_OUTPUT = MODEL_ROOT / "novel-phrase-lexicon.txt"
DEFAULT_BASE_LEXICON = MODEL_ROOT / "lexicon.txt"
DEFAULT_TOKENS = MODEL_ROOT / "tokens.txt"
TONE_MARKS = {
    "\N{COMBINING MACRON}": "1",
    "\N{COMBINING ACUTE ACCENT}": "2",
    "\N{COMBINING CARON}": "3",
    "\N{COMBINING GRAVE ACCENT}": "4",
}


def uncommented(line: str) -> str:
    return line.split("#", 1)[0].strip()


def numbered_pinyin(syllable: str) -> str:
    """Convert one accented pinyin syllable to the Matcha token spelling."""
    normalized = unicodedata.normalize("NFD", syllable.strip().lower().replace("u:", "v"))
    letters: list[str] = []
    tone = "5"
    for character in normalized:
        if character in TONE_MARKS:
            tone = TONE_MARKS[character]
        elif character == "\N{COMBINING DIAERESIS}":
            if letters and letters[-1] == "u":
                letters[-1] = "v"
        elif unicodedata.combining(character):
            # Circumflex in ê and other pronunciation hints are not Matcha tokens.
            continue
        elif "a" <= character <= "z":
            letters.append(character)
        else:
            raise ValueError(f"unsupported pinyin character {character!r} in {syllable!r}")
    if not letters:
        raise ValueError(f"empty pinyin syllable: {syllable!r}")
    return "".join(letters) + tone


def is_han(character: str) -> bool:
    codepoint = ord(character)
    return (
        0x3400 <= codepoint <= 0x4DBF
        or 0x4E00 <= codepoint <= 0x9FFF
        or 0xF900 <= codepoint <= 0xFAFF
        or 0x20000 <= codepoint <= 0x323AF
    )


def read_tokens(path: Path) -> set[str]:
    return {
        parts[0]
        for line in path.read_text(encoding="utf-8").splitlines()
        if (parts := line.split())
    }


def read_matcha_lexicon(path: Path) -> dict[str, tuple[str, ...]]:
    entries: dict[str, tuple[str, ...]] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = uncommented(raw_line)
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            raise ValueError(f"{path}:{line_number}: malformed lexicon entry")
        word, pronunciation = parts[0], tuple(parts[1:])
        previous = entries.setdefault(word, pronunciation)
        if previous != pronunciation:
            raise ValueError(f"{path}:{line_number}: conflicting duplicate for {word}")
    return entries


def read_overrides(
    path: Path,
    tokens: set[str],
) -> OrderedDict[str, tuple[str, ...]]:
    entries: OrderedDict[str, tuple[str, ...]] = OrderedDict()
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = uncommented(raw_line)
        if not line:
            continue
        parts = line.split()
        word, pronunciation = parts[0], tuple(parts[1:])
        if not 2 <= len(word) <= 10 or len(word) != len(pronunciation):
            raise ValueError(
                f"{path}:{line_number}: phrase must contain 2-10 Han characters "
                "and one token per character"
            )
        if not all(is_han(character) for character in word):
            raise ValueError(f"{path}:{line_number}: override contains a non-Han character")
        unknown = [token for token in pronunciation if token not in tokens]
        if unknown:
            raise ValueError(f"{path}:{line_number}: unknown Matcha tokens: {unknown}")
        previous = entries.setdefault(word, pronunciation)
        if previous != pronunciation:
            raise ValueError(f"{path}:{line_number}: conflicting duplicate for {word}")
    return entries


def read_upstream(
    path: Path,
    tokens: set[str],
) -> tuple[dict[str, set[tuple[str, ...]]], dict[str, int]]:
    entries: dict[str, set[tuple[str, ...]]] = defaultdict(set)
    stats = defaultdict(int)
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = uncommented(raw_line)
        if not line:
            continue
        word, separator, pinyin = line.partition(":")
        word = word.strip()
        if not separator:
            stats["malformed"] += 1
            continue
        if not 2 <= len(word) <= 10 or not all(is_han(character) for character in word):
            stats["outside_phrase_scope"] += 1
            continue
        try:
            pronunciation = tuple(numbered_pinyin(part) for part in pinyin.split())
        except ValueError:
            stats["unsupported_pinyin"] += 1
            continue
        if len(pronunciation) != len(word):
            stats["syllable_mismatch"] += 1
            continue
        if any(token not in tokens for token in pronunciation):
            stats["unknown_token"] += 1
            continue
        entries[word].add(pronunciation)
        stats["valid_lines"] += 1
    return entries, dict(stats)


def differs_from_character_defaults(
    word: str,
    pronunciation: tuple[str, ...],
    character_defaults: dict[str, str],
) -> bool:
    return any(
        character_defaults.get(character) != token
        for character, token in zip(word, pronunciation, strict=True)
    )


def build_lexicon(
    source: Path,
    overrides_path: Path,
    base_lexicon_path: Path,
    tokens_path: Path,
    output_path: Path,
) -> dict[str, int]:
    tokens = read_tokens(tokens_path)
    base = read_matcha_lexicon(base_lexicon_path)
    overrides = read_overrides(overrides_path, tokens)
    upstream, source_stats = read_upstream(source, tokens)
    character_defaults = {
        word: pronunciation[0]
        for word, pronunciation in base.items()
        if len(word) == 1 and len(pronunciation) == 1
    }

    merged: OrderedDict[str, tuple[str, ...]] = OrderedDict(overrides)
    stats = defaultdict(int, source_stats)
    for word in sorted(upstream):
        if word in overrides:
            stats["shadowed_by_override"] += 1
            continue
        pronunciations = upstream[word]
        if len(pronunciations) != 1:
            # Selecting one reading arbitrarily would merely move the polyphone bug.
            stats["ambiguous_phrase"] += 1
            continue
        pronunciation = next(iter(pronunciations))
        if base.get(word) == pronunciation:
            stats["already_in_base"] += 1
            continue
        if not differs_from_character_defaults(word, pronunciation, character_defaults):
            stats["not_context_sensitive"] += 1
            continue
        merged[word] = pronunciation
        stats["added_from_upstream"] += 1

    output_path.parent.mkdir(parents=True, exist_ok=True)
    contents = "".join(
        f"{word} {' '.join(pronunciation)}\n"
        for word, pronunciation in merged.items()
    )
    temporary_output = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_output.write_text(contents, encoding="utf-8", newline="\n")
    temporary_output.replace(output_path)
    stats["override_entries"] = len(overrides)
    stats["output_entries"] = len(merged)
    stats["output_bytes"] = output_path.stat().st_size
    return dict(stats)


def verified_source(path: Path) -> None:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != UPSTREAM_SHA256:
        raise ValueError(
            f"unexpected phrase-pinyin-data SHA-256: {digest}; expected {UPSTREAM_SHA256}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge phrase-pinyin-data with Page Shelf novel overrides.",
    )
    parser.add_argument(
        "--source",
        type=Path,
        help="Pinned phrase-pinyin-data large_pinyin.txt; downloaded when omitted.",
    )
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--base-lexicon", type=Path, default=DEFAULT_BASE_LEXICON)
    parser.add_argument("--tokens", type=Path, default=DEFAULT_TOKENS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def run(args: argparse.Namespace, source: Path) -> None:
    verified_source(source)
    stats = build_lexicon(
        source=source,
        overrides_path=args.overrides,
        base_lexicon_path=args.base_lexicon,
        tokens_path=args.tokens,
        output_path=args.output,
    )
    print(f"wrote {args.output}")
    for name in sorted(stats):
        print(f"{name}={stats[name]}")


def main() -> None:
    args = parse_args()
    if args.source is not None:
        run(args, args.source)
        return
    with tempfile.TemporaryDirectory(prefix="page-shelf-pinyin-") as directory:
        source = Path(directory) / "large_pinyin.txt"
        print(f"downloading {UPSTREAM_URL}")
        urllib.request.urlretrieve(UPSTREAM_URL, source)
        run(args, source)


if __name__ == "__main__":
    main()
