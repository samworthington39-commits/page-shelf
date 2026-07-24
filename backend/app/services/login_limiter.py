from __future__ import annotations

import time
from collections import defaultdict, deque
from threading import RLock

from fastapi import HTTPException, Request, status


WINDOW_SECONDS = 5 * 60
MAX_FAILURES = 8
_failures: dict[str, deque[float]] = defaultdict(deque)
_lock = RLock()


def _client_key(request: Request) -> str:
    # Do not trust forwarding headers unless a trusted reverse proxy is configured.
    return request.client.host if request.client else "unknown"


def enforce_login_limit(request: Request) -> None:
    now = time.monotonic()
    key = _client_key(request)
    with _lock:
        attempts = _failures[key]
        while attempts and now - attempts[0] >= WINDOW_SECONDS:
            attempts.popleft()
        if len(attempts) < MAX_FAILURES:
            return
        retry_after = max(1, int(WINDOW_SECONDS - (now - attempts[0])))
    raise HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail="登录尝试过多，请稍后再试",
        headers={"Retry-After": str(retry_after)},
    )


def record_login_failure(request: Request) -> None:
    with _lock:
        _failures[_client_key(request)].append(time.monotonic())


def clear_login_failures(request: Request) -> None:
    with _lock:
        _failures.pop(_client_key(request), None)


def reset_login_limiter() -> None:
    """Clear process-local counters during controlled lifecycle/test resets."""
    with _lock:
        _failures.clear()
