from contextlib import asynccontextmanager

from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from .config import get_settings
from .db import initialize_database
from .middleware import ReaderTextGZipMiddleware
from .routers import admin, auth, books, library, progress, reader, shelves
from .services.auto_scanner import auto_scan_lifespan


DEFAULT_REQUEST_LIMIT = 1_048_576
BOOK_METADATA_REQUEST_LIMIT = 12 * 1024 * 1024


@asynccontextmanager
async def lifespan(_app: FastAPI):
    settings = get_settings()
    settings.library_path.mkdir(parents=True, exist_ok=True)
    settings.cover_path.mkdir(parents=True, exist_ok=True)
    initialize_database()
    async with auto_scan_lifespan(settings):
        yield


app = FastAPI(
    title="Page Shelf API",
    version="1.1.0",
    description="TXT/EPUB/MOBI chapters and page-oriented PDF reading without OCR or synthetic chapters.",
    lifespan=lifespan,
    docs_url="/docs" if get_settings().enable_api_docs else None,
    redoc_url="/redoc" if get_settings().enable_api_docs else None,
    openapi_url="/openapi.json" if get_settings().enable_api_docs else None,
)

# Chapter JSON is mostly text and compresses extremely well. OkHttp transparently decompresses it,
# reducing EPUB preload transfer time without adding any work to the animation/main UI thread.
app.add_middleware(ReaderTextGZipMiddleware, minimum_size=1_024, compresslevel=5)

settings = get_settings()
if settings.cors_origin_list:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=False,
        allow_methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=[
            "Authorization",
            "Content-Type",
            "Range",
            "If-Range",
            "If-None-Match",
            "If-Modified-Since",
            "X-Shelf-Pin",
            "X-Page-Shelf-Admin",
        ],
    )


@app.middleware("http")
async def harden_responses(request: Request, call_next):  # type: ignore[no-untyped-def]
    content_length = request.headers.get("content-length")
    is_book_metadata_update = (
        request.method == "PATCH"
        and request.url.path.startswith("/api/v1/admin/books/")
        and request.url.path.endswith("/metadata")
    )
    request_limit = BOOK_METADATA_REQUEST_LIMIT if is_book_metadata_update else DEFAULT_REQUEST_LIMIT
    if content_length and content_length.isdigit() and int(content_length) > request_limit:
        return JSONResponse(status_code=413, content={"detail": "请求内容过大"})
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    if request.url.path.startswith("/api/v1/admin") or request.url.path.startswith("/api/v1/auth"):
        response.headers.setdefault("Cache-Control", "no-store")
    return response
app.include_router(library.router, prefix="/api/v1")
app.include_router(auth.router, prefix="/api/v1")
app.include_router(books.router, prefix="/api/v1")
app.include_router(progress.router, prefix="/api/v1")
app.include_router(shelves.router, prefix="/api/v1")
app.include_router(admin.router, prefix="/api/v1")

admin_directory = Path(__file__).parent / "admin"
app.mount("/admin/assets", StaticFiles(directory=admin_directory), name="admin-assets")
app.add_api_route("/admin", admin.admin_page, methods=["GET"], include_in_schema=False)

reader_directory = Path(__file__).parent / "reader"
app.mount("/reader/assets", StaticFiles(directory=reader_directory), name="reader-assets")
app.add_api_route("/reader", reader.reader_page, methods=["GET"], include_in_schema=False)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "api_version": auth.API_VERSION}
