from __future__ import annotations

import os
from pathlib import Path, PurePosixPath

from ..config import Settings
from .storage_roots import active_storage_roots


class StoragePathError(ValueError):
    pass


def _is_within(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def allowed_storage_path(path_value: str, settings: Settings, *, create: bool) -> Path:
    candidate = Path(path_value).expanduser().resolve(strict=False)
    allowed_roots = [root.resolve(strict=False) for root in active_storage_roots(settings)]
    matching_roots = [root for root in allowed_roots if _is_within(candidate, root)]
    if not matching_roots:
        roots = "、".join(str(root) for root in allowed_roots)
        raise StoragePathError(f"存储位置 {candidate} 不在已授权范围内：{roots}")
    missing_roots = [root for root in matching_roots if not root.is_dir()]
    if missing_roots:
        raise StoragePathError(f"授权根目录不存在，请检查 Docker 存储挂载：{missing_roots[0]}")
    if create:
        candidate.mkdir(parents=True, exist_ok=True)
    if not candidate.is_dir():
        raise StoragePathError("存储位置不存在或不是目录")

    resolved = candidate.resolve(strict=True)
    if not any(_is_within(resolved, root.resolve(strict=True)) for root in matching_roots):
        raise StoragePathError("存储位置通过符号链接越过了授权范围")
    if not os.access(resolved, os.R_OK | os.X_OK):
        raise StoragePathError("容器没有该存储位置的读取权限")
    return resolved


def shelf_directory(storage_path: str, relative_path: str, *, create: bool) -> Path:
    normalized = relative_path.strip().replace("\\", "/").strip("/")
    parent = Path(storage_path).resolve(strict=True)
    if normalized in {".", "./"}:
        return parent

    pure = PurePosixPath(normalized)
    if not normalized or pure.is_absolute() or ".." in pure.parts:
        raise StoragePathError("书架目录必须是存储位置内的相对目录，或用 . 表示整个存储位置")

    candidate = parent.joinpath(*pure.parts).resolve(strict=False)
    if not _is_within(candidate, parent):
        raise StoragePathError("书架目录越过了存储位置")
    if create:
        candidate.mkdir(parents=True, exist_ok=True)
    if not candidate.is_dir():
        raise StoragePathError("书架目录不存在")
    resolved = candidate.resolve(strict=True)
    if not _is_within(resolved, parent):
        raise StoragePathError("书架目录通过符号链接越过了存储位置")
    return resolved


def paths_overlap(first: Path, second: Path) -> bool:
    first = first.resolve(strict=False)
    second = second.resolve(strict=False)
    return _is_within(first, second) or _is_within(second, first)
