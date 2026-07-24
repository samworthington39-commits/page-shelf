from __future__ import annotations

from pathlib import Path
from threading import RLock

from ..config import Settings, get_settings


_removed_roots: set[Path] = set()
_lock = RLock()


def active_storage_roots(settings: Settings) -> list[Path]:
    with _lock:
        removed = set(_removed_roots)
    return [root for root in settings.storage_root_paths if root.resolve() not in removed]


def remove_storage_root(path: str, settings: Settings) -> Path:
    candidate = Path(path).resolve()
    available = {root.resolve() for root in active_storage_roots(settings)}
    if candidate not in available:
        raise ValueError("授权路径不存在或已被移除")
    with _lock:
        _removed_roots.add(candidate)
    return candidate


def refresh_storage_roots() -> Settings:
    with _lock:
        _removed_roots.clear()
    get_settings.cache_clear()
    return get_settings()
