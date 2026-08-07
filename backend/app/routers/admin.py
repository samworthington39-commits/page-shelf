from __future__ import annotations

import base64
import binascii
import mimetypes
import os
import shutil
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import FileResponse
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..admin_schemas import (
    AdminBookView,
    AdminLogin,
    AdminOverview,
    AdminPasswordUpdate,
    AdminResetRequest,
    AdminResetResponse,
    BookMetadataUpdate,
    ChapterSplitUpdate,
    ShelfBookVisibilityUpdate,
    ShelfCreate,
    ShelfUpdate,
    ShelfView,
    StorageLocationCreate,
    StorageLocationView,
    StorageRootAction,
    StorageRootView,
)
from ..config import Settings, get_settings
from ..db import get_db
from ..models import Book, Chapter, ReadingProgress, Shelf, StorageLocation
from ..schemas import ScanResponse
from ..services.admin_auth import (
    ADMIN_COOKIE,
    create_session_token,
    password_change_required,
    password_is_configured,
    require_admin,
    require_admin_session,
    update_password,
    valid_session,
    verify_password,
)
from ..services.login_limiter import clear_login_failures, enforce_login_limit, record_login_failure
from ..services.library_scanner import automatic_book_title
from ..services.shelf_scanner import (
    ShelfScanBusyError,
    resolved_shelf_path,
    resplit_book,
    scan_all_shelves,
    scan_shelf,
)
from ..services.shelf_access import hash_shelf_pin
from ..services.storage_paths import StoragePathError, allowed_storage_path, paths_overlap, shelf_directory
from ..services.storage_roots import active_storage_roots, refresh_storage_roots, remove_storage_root


router = APIRouter(prefix="/admin", tags=["admin"])
admin_directory = Path(__file__).parents[1] / "admin"

# 会话不设有效时限：内网部署下管理 Cookie 长期有效，仅修改管理密码后全部失效。
SESSION_COOKIE_MAX_AGE = 10 * 365 * 24 * 3600

SCAN_INTERVAL_MULTIPLIERS = {"minutes": 1, "hours": 60, "days": 1440, "weeks": 10_080}
MAX_SCAN_INTERVAL_MINUTES = 525_600


def _scan_interval_minutes(value: int, unit: str) -> int:
    multiplier = SCAN_INTERVAL_MULTIPLIERS.get(unit)
    if multiplier is None:
        raise ValueError("扫描间隔单位无效")
    minutes = value * multiplier
    if not 1 <= minutes <= MAX_SCAN_INTERVAL_MINUTES:
        raise ValueError("扫描间隔必须在 1 分钟到 1 年之间")
    return minutes


def admin_page() -> FileResponse:
    response = FileResponse(admin_directory / "index.html", media_type="text/html")
    response.headers["Cache-Control"] = "no-store"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    )
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    return response


@router.get("/status")
def admin_status(request: Request, settings: Settings = Depends(get_settings)) -> dict[str, bool]:
    return {
        "password_configured": password_is_configured(settings),
        "authenticated": valid_session(request.cookies.get(ADMIN_COOKIE), settings, "admin"),
        "password_change_required": password_change_required(settings),
    }


@router.post("/session")
def login(payload: AdminLogin, request: Request, settings: Settings = Depends(get_settings)) -> Response:
    if not password_is_configured(settings):
        raise HTTPException(
            status_code=503,
            detail="请先配置 ADMIN_PASSWORD 和至少 16 位的 ADMIN_SESSION_SECRET",
        )
    enforce_login_limit(request)
    if not verify_password(payload.password, settings):
        record_login_failure(request)
        raise HTTPException(status_code=401, detail="管理密码错误")
    clear_login_failures(request)
    response = Response(status_code=status.HTTP_204_NO_CONTENT)
    response.set_cookie(
        ADMIN_COOKIE,
        create_session_token(settings, "admin"),
        max_age=SESSION_COOKIE_MAX_AGE,
        httponly=True,
        secure=request.url.scheme == "https",
        samesite="strict",
        path="/",
    )
    return response


@router.put("/password", status_code=204, dependencies=[Depends(require_admin_session)])
def change_password(
    payload: AdminPasswordUpdate,
    request: Request,
    settings: Settings = Depends(get_settings),
) -> Response:
    if payload.current_password == payload.new_password:
        raise HTTPException(status_code=400, detail="新密码不能与当前密码相同")
    try:
        changed = update_password(payload.current_password, payload.new_password, settings)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if not changed:
        raise HTTPException(status_code=400, detail="当前管理密码错误")
    response = Response(status_code=status.HTTP_204_NO_CONTENT)
    response.set_cookie(
        ADMIN_COOKIE,
        create_session_token(settings, "admin"),
        max_age=SESSION_COOKIE_MAX_AGE,
        httponly=True,
        secure=request.url.scheme == "https",
        samesite="strict",
        path="/",
    )
    return response


@router.delete("/session", dependencies=[Depends(require_admin_session)])
def logout() -> Response:
    response = Response(status_code=status.HTTP_204_NO_CONTENT)
    response.delete_cookie(ADMIN_COOKIE, path="/", samesite="strict")
    return response


def _storage_root_view(root: Path) -> StorageRootView:
    exists = root.is_dir()
    usage = shutil.disk_usage(root) if exists else None
    return StorageRootView(
        path=str(root),
        exists=exists,
        writable=exists and os.access(root, os.R_OK | os.W_OK | os.X_OK),
        free_bytes=usage.free if usage else None,
        total_bytes=usage.total if usage else None,
    )


def _shelf_view(
    session: Session,
    shelf: Shelf,
    settings: Settings,
    statistics: tuple[int, int] | None = None,
) -> ShelfView:
    book_count, total_bytes = statistics or session.execute(
        select(func.count(Book.id), func.coalesce(func.sum(Book.file_size), 0)).where(Book.shelf_id == shelf.id)
    ).one()
    try:
        path = str(resolved_shelf_path(shelf, settings))
    except Exception:
        path = str(Path(shelf.storage_location.path) / shelf.relative_path)
    return ShelfView(
        id=shelf.id,
        name=shelf.name,
        storage_location_id=shelf.storage_location_id,
        storage_location_name=shelf.storage_location.name,
        relative_path=shelf.relative_path,
        resolved_path=path,
        pin_configured=shelf.access_pin_hash is not None,
        auto_scan_enabled=shelf.auto_scan_enabled,
        scan_interval_value=_scan_interval_display(shelf),
        scan_interval_unit=_scan_interval_unit(shelf),
        scan_interval_minutes=shelf.scan_interval_minutes,
        scan_status=shelf.scan_status,
        book_count=int(book_count),
        total_bytes=int(total_bytes),
        last_scan_started_at=shelf.last_scan_started_at,
        last_scan_completed_at=shelf.last_scan_completed_at,
        last_scan_summary=shelf.last_scan_summary_json,
        last_scan_error=shelf.last_scan_error,
        created_at=shelf.created_at,
    )


def _scan_interval_unit(shelf: Shelf) -> str:
    return shelf.scan_interval_unit if shelf.scan_interval_unit in SCAN_INTERVAL_MULTIPLIERS else "minutes"


def _scan_interval_display(shelf: Shelf) -> int:
    unit = _scan_interval_unit(shelf)
    multiplier = SCAN_INTERVAL_MULTIPLIERS[unit]
    minutes = shelf.scan_interval_minutes
    if minutes % multiplier:
        return minutes
    return minutes // multiplier


def _book_view(book: Book) -> AdminBookView:
    file_path = Path(book.file_path)
    metadata = dict(book.metadata_overrides_json or {})
    cover_source = metadata.get("cover_source") or (
        "manual" if metadata.get("manual_cover_path") else metadata.get("automatic_cover_source")
    )
    if cover_source is None and book.cover_status == "ready":
        cover_source = "pdf" if book.format == "pdf" else "automatic"
    return AdminBookView(
        id=book.id,
        shelf_id=book.shelf_id or "",
        shelf_visible=book.shelf_visible,
        title=book.title,
        author=book.author,
        format=book.format,
        filename=file_path.name,
        directory=str(file_path.parent),
        file_path=book.file_path,
        file_size=book.file_size,
        chapter_count=book.chapter_count,
        page_count=book.page_count,
        parse_status=book.parse_status,
        parse_warnings=book.parse_warnings_json or [],
        cover_status=book.cover_status,
        cover_source=str(cover_source) if cover_source else None,
        cover_url=f"/api/v1/admin/books/{book.id}/cover",
        chapter_split_mode=book.chapter_split_mode or "auto",
        chapter_split_config=book.chapter_split_config_json or {},
        chapter_split_revision=book.chapter_split_revision or 0,
        last_chapter_split_at=book.last_chapter_split_at,
        updated_at=book.updated_at,
    )


def _safe_cover_file(path_value: str | None, cover_root: Path) -> Path | None:
    if not path_value:
        return None
    try:
        path = Path(path_value).resolve(strict=True)
        root = cover_root.resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    return path if path.is_file() and (path == root or root in path.parents) else None


def _remember_automatic_metadata(book: Book) -> dict:
    metadata = dict(book.metadata_overrides_json or {})
    source = Path(book.file_path)
    if "automatic_title" not in metadata:
        internal_title = (
            book.title
            if book.format in {"epub", "mobi", "pdf"} and book.title != source.stem
            else None
        )
        metadata["automatic_title"] = automatic_book_title(source, internal_title)
    if "automatic_author" not in metadata:
        metadata["automatic_author"] = book.author
    book.metadata_overrides_json = metadata
    return metadata


def _refresh_automatic_cover(book: Book, settings: Settings) -> Path | None:
    from ..services.cover_service import automatic_cover

    result = automatic_cover(
        Path(book.file_path),
        book.title,
        settings.cover_path.resolve(),
        book.fingerprint,
    )
    book.cover_status = result.status
    book.cover_path = str(Path(result.path).resolve()) if result.path is not None else None
    metadata = dict(book.metadata_overrides_json or {})
    metadata["automatic_cover_source"] = result.source
    if not metadata.get("manual_cover_path"):
        metadata["cover_source"] = result.source
    book.metadata_overrides_json = metadata
    warnings = list(book.parse_warnings_json or [])
    for warning in result.warnings:
        if warning not in warnings:
            warnings.append(warning)
    book.parse_warnings_json = warnings
    return _safe_cover_file(book.cover_path, settings.cover_path)


def _decode_cover(payload: str) -> bytes:
    encoded = payload.strip()
    if encoded.startswith("data:"):
        header, separator, encoded = encoded.partition(",")
        if not separator or ";base64" not in header.lower():
            raise HTTPException(status_code=422, detail="封面数据格式无效")
    if len(encoded) > 11_184_812:
        raise HTTPException(status_code=413, detail="封面原图不能超过 8 MB")
    try:
        decoded = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise HTTPException(status_code=422, detail="封面不是有效的 Base64 数据") from exc
    if not decoded:
        raise HTTPException(status_code=422, detail="封面内容不能为空")
    if len(decoded) > 8 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="封面原图不能超过 8 MB")
    return decoded


@router.get("/overview", response_model=AdminOverview, dependencies=[Depends(require_admin)])
def overview(
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> AdminOverview:
    locations = list(session.scalars(select(StorageLocation).order_by(StorageLocation.created_at)))
    shelves = list(session.scalars(select(Shelf).order_by(Shelf.created_at)))
    shelf_statistics = {
        shelf_id: (int(book_count), int(total_bytes))
        for shelf_id, book_count, total_bytes in session.execute(
            select(Book.shelf_id, func.count(Book.id), func.coalesce(func.sum(Book.file_size), 0))
            .where(Book.shelf_id.is_not(None))
            .group_by(Book.shelf_id)
        )
    }
    shelf_views = [
        _shelf_view(session, shelf, settings, shelf_statistics.get(shelf.id, (0, 0)))
        for shelf in shelves
    ]
    total_books, total_bytes = session.execute(
        select(func.count(Book.id), func.coalesce(func.sum(Book.file_size), 0))
    ).one()
    return AdminOverview(
        storage_roots=[_storage_root_view(root) for root in active_storage_roots(settings)],
        storage_locations=[
            StorageLocationView(
                id=location.id,
                name=location.name,
                path=location.path,
                shelf_count=sum(shelf.storage_location_id == location.id for shelf in shelves),
                created_at=location.created_at,
            )
            for location in locations
        ],
        shelves=shelf_views,
        total_books=int(total_books),
        total_bytes=int(total_bytes),
        scanning_count=sum(shelf.scan_status == "scanning" for shelf in shelves),
    )


@router.delete("/storage-roots", status_code=204, dependencies=[Depends(require_admin)])
def delete_storage_root(
    payload: StorageRootAction,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Response:
    root = Path(payload.path).resolve()
    for location in session.scalars(select(StorageLocation)):
        location_path = Path(location.path).resolve()
        if location_path == root or root in location_path.parents:
            raise HTTPException(status_code=409, detail="请先移除该授权路径下的书架和存储位置登记")
    try:
        remove_storage_root(payload.path, settings)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return Response(status_code=204)


@router.post(
    "/storage-roots/refresh",
    response_model=list[StorageRootView],
    dependencies=[Depends(require_admin)],
)
def reload_storage_roots() -> list[StorageRootView]:
    settings = refresh_storage_roots()
    return [_storage_root_view(root) for root in active_storage_roots(settings)]


def _clear_cover_cache(path: Path) -> int:
    root = path.resolve()
    if root == Path(root.anchor):
        raise RuntimeError("封面缓存目录配置不安全，拒绝执行重置")
    root.mkdir(parents=True, exist_ok=True)
    deleted = 0
    for child in root.iterdir():
        if child.is_symlink() or child.is_file():
            child.unlink()
            deleted += 1
        elif child.is_dir():
            shutil.rmtree(child)
            deleted += 1
    return deleted


@router.post("/reset", response_model=AdminResetResponse, dependencies=[Depends(require_admin)])
def reset_backend(
    _payload: AdminResetRequest,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> AdminResetResponse:
    counts = {
        "books_deleted": int(session.scalar(select(func.count(Book.id))) or 0),
        "shelves_deleted": int(session.scalar(select(func.count(Shelf.id))) or 0),
        "storage_locations_deleted": int(session.scalar(select(func.count(StorageLocation.id))) or 0),
        "progress_deleted": int(session.scalar(select(func.count(ReadingProgress.id))) or 0),
    }
    session.execute(delete(ReadingProgress))
    session.execute(delete(Chapter))
    session.execute(delete(Book))
    session.execute(delete(Shelf))
    session.execute(delete(StorageLocation))
    session.commit()
    covers_deleted = _clear_cover_cache(settings.cover_path)
    refresh_storage_roots()
    return AdminResetResponse(**counts, covers_deleted=covers_deleted)


@router.post(
    "/storage-locations",
    response_model=StorageLocationView,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_admin)],
)
def create_storage_location(
    payload: StorageLocationCreate,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> StorageLocationView:
    try:
        path = allowed_storage_path(payload.path, settings, create=payload.create_directory)
        location = StorageLocation(name=payload.name, path=str(path))
        session.add(location)
        session.commit()
    except StoragePathError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except IntegrityError as exc:
        session.rollback()
        raise HTTPException(status_code=409, detail="存储位置名称或路径已存在") from exc
    return StorageLocationView(
        id=location.id,
        name=location.name,
        path=location.path,
        shelf_count=0,
        created_at=location.created_at,
    )


@router.delete("/storage-locations/{location_id}", status_code=204, dependencies=[Depends(require_admin)])
def delete_storage_location(location_id: str, session: Session = Depends(get_db)) -> Response:
    location = session.get(StorageLocation, location_id)
    if location is None:
        raise HTTPException(status_code=404, detail="存储位置不存在")
    if session.scalar(select(func.count(Shelf.id)).where(Shelf.storage_location_id == location.id)):
        raise HTTPException(status_code=409, detail="请先删除该存储位置下的书架")
    session.delete(location)
    session.commit()
    return Response(status_code=204)


@router.post(
    "/shelves",
    response_model=ShelfView,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_admin)],
)
def create_shelf(
    payload: ShelfCreate,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> ShelfView:
    location = session.get(StorageLocation, payload.storage_location_id)
    if location is None:
        raise HTTPException(status_code=404, detail="存储位置不存在")
    if payload.scan_interval_value is not None:
        try:
            interval_minutes = _scan_interval_minutes(payload.scan_interval_value, payload.scan_interval_unit)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        interval_unit = payload.scan_interval_unit
    else:
        interval_minutes = payload.scan_interval_minutes or 5
        interval_unit = "minutes"
    try:
        allowed_storage_path(location.path, settings, create=False)
        path = shelf_directory(location.path, payload.relative_path, create=True)
        for existing in session.scalars(select(Shelf)):
            if paths_overlap(path, resolved_shelf_path(existing, settings)):
                raise HTTPException(status_code=409, detail=f"书架目录与“{existing.name}”重叠")
        shelf = Shelf(
            name=payload.name,
            storage_location_id=location.id,
            relative_path=payload.relative_path.strip().replace("\\", "/").strip("/"),
            access_pin_hash=hash_shelf_pin(payload.access_pin) if payload.access_pin else None,
            auto_scan_enabled=payload.auto_scan_enabled,
            scan_interval_minutes=interval_minutes,
            scan_interval_unit=interval_unit,
        )
        session.add(shelf)
        session.commit()
        if payload.scan_after_create:
            scan_shelf(session, shelf, settings)
    except StoragePathError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except IntegrityError as exc:
        session.rollback()
        raise HTTPException(status_code=409, detail="书架名称或目录已存在") from exc
    return _shelf_view(session, shelf, settings)


@router.patch("/shelves/{shelf_id}", response_model=ShelfView, dependencies=[Depends(require_admin)])
def update_shelf(
    shelf_id: str,
    payload: ShelfUpdate,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> ShelfView:
    shelf = session.get(Shelf, shelf_id)
    if shelf is None:
        raise HTTPException(status_code=404, detail="书架不存在")
    values = payload.model_dump(exclude_unset=True)
    access_pin = values.pop("access_pin", None)
    interval_value = values.pop("scan_interval_value", None)
    interval_unit = values.pop("scan_interval_unit", None)
    interval_minutes = values.pop("scan_interval_minutes", None)
    if interval_value is not None or interval_unit is not None or interval_minutes is not None:
        if interval_value is None:
            if interval_minutes is not None:
                interval_value = interval_minutes
                interval_unit = interval_unit or "minutes"
            else:
                current_unit = _scan_interval_unit(shelf)
                multiplier = SCAN_INTERVAL_MULTIPLIERS[current_unit]
                if shelf.scan_interval_minutes % multiplier:
                    interval_value = shelf.scan_interval_minutes
                    interval_unit = "minutes"
                else:
                    interval_value = shelf.scan_interval_minutes // multiplier
                    interval_unit = interval_unit or current_unit
        if interval_unit is None:
            interval_unit = "minutes"
        try:
            shelf.scan_interval_minutes = _scan_interval_minutes(interval_value, interval_unit)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        shelf.scan_interval_unit = interval_unit
    for field, value in values.items():
        setattr(shelf, field, value)
    if "access_pin" in payload.model_fields_set:
        shelf.access_pin_hash = hash_shelf_pin(access_pin) if access_pin else None
    try:
        session.commit()
    except IntegrityError as exc:
        session.rollback()
        raise HTTPException(status_code=409, detail="书架名称已存在") from exc
    return _shelf_view(session, shelf, settings)


@router.patch(
    "/books/{book_id}/shelf-visibility",
    response_model=AdminBookView,
    dependencies=[Depends(require_admin)],
)
def update_book_shelf_visibility(
    book_id: str,
    payload: ShelfBookVisibilityUpdate,
    session: Session = Depends(get_db),
) -> AdminBookView:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="书籍不存在")
    book.shelf_visible = payload.shelf_visible
    session.commit()
    session.refresh(book)
    return _book_view(book)


@router.get(
    "/shelves/{shelf_id}/books",
    response_model=list[AdminBookView],
    dependencies=[Depends(require_admin)],
)
def shelf_books(shelf_id: str, session: Session = Depends(get_db)) -> list[AdminBookView]:
    if session.get(Shelf, shelf_id) is None:
        raise HTTPException(status_code=404, detail="书架不存在")
    books = session.scalars(
        select(Book).where(Book.shelf_id == shelf_id).order_by(Book.title, Book.id)
    )
    return [_book_view(book) for book in books]


@router.get("/books/{book_id}/cover", dependencies=[Depends(require_admin)])
def admin_book_cover(
    book_id: str,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Response:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="书籍不存在")
    cover = _safe_cover_file(book.cover_path, settings.cover_path)
    if book.cover_status != "ready" or cover is None:
        metadata = dict(book.metadata_overrides_json or {})
        if metadata.get("manual_cover_path"):
            raise HTTPException(status_code=404, detail="手工封面文件不存在，请重新上传或清除")
        try:
            cover = _refresh_automatic_cover(book, settings)
            session.commit()
        except Exception as exc:
            session.rollback()
            raise HTTPException(status_code=404, detail=f"封面不可用：{exc}") from exc
    if cover is None or book.cover_status != "ready":
        raise HTTPException(status_code=404, detail="封面不可用")
    media_type = mimetypes.guess_type(cover.name)[0] or "image/jpeg"
    return FileResponse(cover, media_type=media_type)


@router.patch(
    "/books/{book_id}/metadata",
    response_model=AdminBookView,
    dependencies=[Depends(require_admin)],
)
def update_book_metadata(
    book_id: str,
    payload: BookMetadataUpdate,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> AdminBookView:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="书籍不存在")
    fields = payload.model_fields_set
    if payload.reset_title and "title" in fields:
        raise HTTPException(status_code=422, detail="不能同时修改并恢复自动书名")
    if payload.reset_author and "author" in fields:
        raise HTTPException(status_code=422, detail="不能同时修改并恢复自动作者")
    if payload.remove_cover and payload.cover_base64 is not None:
        raise HTTPException(status_code=422, detail="不能同时上传并清除封面")
    if payload.cover_filename is not None and payload.cover_base64 is None:
        raise HTTPException(status_code=422, detail="提供封面文件名时必须同时提供封面内容")

    metadata = _remember_automatic_metadata(book)
    if payload.reset_title:
        metadata.pop("title", None)
    elif "title" in fields:
        if not payload.title:
            raise HTTPException(status_code=422, detail="书名不能为空")
        metadata["title"] = payload.title
    if payload.reset_author:
        metadata.pop("author", None)
    elif "author" in fields:
        metadata["author"] = payload.author or None

    book.title = str(metadata.get("title", metadata["automatic_title"]))
    book.author = metadata["author"] if "author" in metadata else metadata.get("automatic_author")

    old_manual = _safe_cover_file(
        str(metadata.get("manual_cover_path")) if metadata.get("manual_cover_path") else None,
        settings.cover_path,
    )
    if payload.cover_base64 is not None:
        from ..services.cover_service import store_uploaded_cover

        cover_bytes = _decode_cover(payload.cover_base64)
        try:
            stored = Path(
                store_uploaded_cover(cover_bytes, settings.cover_path.resolve(), book.id)
            ).resolve(strict=True)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        cover_root = settings.cover_path.resolve(strict=True)
        if stored != cover_root and cover_root not in stored.parents:
            raise HTTPException(status_code=500, detail="封面服务返回了不安全的存储路径")
        metadata["manual_cover_path"] = str(stored)
        metadata["cover_source"] = "manual"
        book.cover_path = str(stored)
        book.cover_status = "ready"
    elif payload.remove_cover:
        from ..services.cover_service import remove_uploaded_cover

        metadata.pop("manual_cover_path", None)
        metadata.pop("cover_source", None)
        remove_uploaded_cover(settings.cover_path.resolve(), book.id)
        if old_manual is not None and old_manual.name != f"manual-{book.id}.jpg":
            old_manual.unlink(missing_ok=True)
        book.metadata_overrides_json = metadata
        try:
            _refresh_automatic_cover(book, settings)
            metadata = dict(book.metadata_overrides_json or {})
        except Exception as exc:
            book.cover_status = "failed"
            book.cover_path = None
            warnings = list(book.parse_warnings_json or [])
            warnings.append(f"恢复自动封面失败：{exc}")
            book.parse_warnings_json = warnings

    metadata_changed = bool({"title", "author", "reset_title", "reset_author"} & fields)
    if (
        metadata_changed
        and not metadata.get("manual_cover_path")
        and metadata.get("automatic_cover_source") == "generated"
        and not payload.remove_cover
    ):
        book.metadata_overrides_json = metadata
        try:
            _refresh_automatic_cover(book, settings)
            metadata = dict(book.metadata_overrides_json or {})
        except Exception as exc:
            warnings = list(book.parse_warnings_json or [])
            warnings.append(f"书名封面刷新失败：{exc}")
            book.parse_warnings_json = warnings

    book.metadata_overrides_json = metadata
    session.commit()
    session.refresh(book)
    return _book_view(book)


@router.patch(
    "/books/{book_id}/chapter-split",
    response_model=AdminBookView,
    dependencies=[Depends(require_admin)],
)
def update_book_chapter_split(
    book_id: str,
    payload: ChapterSplitUpdate,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> AdminBookView:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="书籍不存在")
    try:
        return _book_view(
            resplit_book(
                session,
                book,
                settings,
                mode=payload.mode,
                segment_size=payload.segment_size,
            )
        )
    except ShelfScanBusyError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.delete("/shelves/{shelf_id}", status_code=204, dependencies=[Depends(require_admin)])
def delete_shelf(shelf_id: str, session: Session = Depends(get_db)) -> Response:
    shelf = session.get(Shelf, shelf_id)
    if shelf is None:
        raise HTTPException(status_code=404, detail="书架不存在")
    for book in list(session.scalars(select(Book).where(Book.shelf_id == shelf.id))):
        session.delete(book)
    session.delete(shelf)
    session.commit()
    return Response(status_code=204)


def _scan_response(result) -> ScanResponse:  # type: ignore[no-untyped-def]
    return ScanResponse(
        discovered=result.discovered,
        imported=result.imported,
        updated=result.updated,
        unchanged=result.unchanged,
        removed=result.removed,
        failed=len(result.failures),
        failures=result.failures,
    )


@router.post("/shelves/{shelf_id}/scan", response_model=ScanResponse, dependencies=[Depends(require_admin)])
def scan_one_shelf(
    shelf_id: str,
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> ScanResponse:
    shelf = session.get(Shelf, shelf_id)
    if shelf is None:
        raise HTTPException(status_code=404, detail="书架不存在")
    try:
        return _scan_response(scan_shelf(session, shelf, settings))
    except ShelfScanBusyError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/scan-all", response_model=ScanResponse, dependencies=[Depends(require_admin)])
def scan_every_shelf(
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> ScanResponse:
    return _scan_response(scan_all_shelves(session, settings))
