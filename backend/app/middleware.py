from __future__ import annotations

from starlette.middleware.gzip import GZipMiddleware
from starlette.types import ASGIApp, Receive, Scope, Send


class ReaderTextGZipMiddleware:
    """Compress only text-reader JSON while preserving PDF byte-range semantics."""

    def __init__(self, app: ASGIApp, minimum_size: int = 1_024, compresslevel: int = 5) -> None:
        self.app = app
        self.gzip_app = GZipMiddleware(
            app,
            minimum_size=minimum_size,
            compresslevel=compresslevel,
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = str(scope.get("path", ""))
        is_reader_text = (
            scope["type"] == "http"
            and path.startswith("/api/v1/books/")
            and ("/chapters/" in path or path.endswith("/toc"))
        )
        if is_reader_text:
            await self.gzip_app(scope, receive, send)
        else:
            await self.app(scope, receive, send)
