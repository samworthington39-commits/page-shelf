from __future__ import annotations

from collections.abc import Iterator
from datetime import datetime, timezone
from email.utils import format_datetime, parsedate_to_datetime
from pathlib import Path
from urllib.parse import quote

from fastapi import HTTPException, Request, status
from fastapi.responses import Response, StreamingResponse


CHUNK_SIZE = 1024 * 1024


def _http_date(timestamp: float) -> str:
    return format_datetime(datetime.fromtimestamp(timestamp, tz=timezone.utc), usegmt=True)


def _read_range(path: Path, start: int, end: int) -> Iterator[bytes]:
    remaining = end - start + 1
    with path.open("rb") as source:
        source.seek(start)
        while remaining > 0:
            chunk = source.read(min(CHUNK_SIZE, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk


def _parse_range(value: str, size: int) -> tuple[int, int]:
    if not value.startswith("bytes=") or "," in value:
        raise ValueError("仅支持单段 bytes Range")
    start_text, separator, end_text = value.removeprefix("bytes=").partition("-")
    if not separator:
        raise ValueError("Range 格式错误")
    if not start_text:
        suffix_length = int(end_text)
        if suffix_length <= 0:
            raise ValueError("Range 后缀长度无效")
        return max(0, size - suffix_length), size - 1
    start = int(start_text)
    end = int(end_text) if end_text else size - 1
    if start < 0 or start >= size or end < start:
        raise ValueError("Range 超出文件范围")
    return start, min(end, size - 1)


def _if_range_matches(request: Request, etag: str, modified_at: datetime) -> bool:
    value = request.headers.get("if-range")
    if not value:
        return True
    if value.startswith('"'):
        return value == etag
    try:
        candidate = parsedate_to_datetime(value)
        if candidate.tzinfo is None:
            candidate = candidate.replace(tzinfo=timezone.utc)
        return modified_at.replace(microsecond=0) <= candidate.astimezone(timezone.utc).replace(microsecond=0)
    except (TypeError, ValueError, OverflowError):
        return False


def _not_modified(request: Request, etag: str, modified_at: datetime) -> bool:
    if_none_match = request.headers.get("if-none-match")
    if if_none_match is not None:
        candidates = [candidate.strip().removeprefix("W/") for candidate in if_none_match.split(",")]
        return "*" in candidates or etag in candidates
    if_modified_since = request.headers.get("if-modified-since")
    if not if_modified_since:
        return False
    try:
        candidate = parsedate_to_datetime(if_modified_since)
        if candidate.tzinfo is None:
            candidate = candidate.replace(tzinfo=timezone.utc)
        return modified_at.replace(microsecond=0) <= candidate.astimezone(timezone.utc).replace(microsecond=0)
    except (TypeError, ValueError, OverflowError):
        return False


def stream_file(request: Request, path: Path, mime_type: str, fingerprint: str) -> Response:
    if not path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="原始文件不存在")
    stat = path.stat()
    size = stat.st_size
    modified_at = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc)
    etag = f'"{fingerprint}"'
    headers = {
        "Accept-Ranges": "bytes",
        "ETag": etag,
        "Last-Modified": _http_date(stat.st_mtime),
        "Content-Disposition": f"inline; filename*=UTF-8''{quote(path.name)}",
    }

    if "range" not in request.headers and _not_modified(request, etag, modified_at):
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)

    range_header = request.headers.get("range")
    if range_header and _if_range_matches(request, etag, modified_at):
        try:
            start, end = _parse_range(range_header, size)
        except (ValueError, TypeError):
            return Response(
                status_code=status.HTTP_416_RANGE_NOT_SATISFIABLE,
                headers={**headers, "Content-Range": f"bytes */{size}"},
            )
        headers.update(
            {
                "Content-Range": f"bytes {start}-{end}/{size}",
                "Content-Length": str(end - start + 1),
            }
        )
        return StreamingResponse(
            _read_range(path, start, end), status_code=status.HTTP_206_PARTIAL_CONTENT, headers=headers, media_type=mime_type
        )

    headers["Content-Length"] = str(size)
    return StreamingResponse(_read_range(path, 0, size - 1), headers=headers, media_type=mime_type)
