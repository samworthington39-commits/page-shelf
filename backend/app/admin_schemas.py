from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, field_validator


class AdminLogin(BaseModel):
    password: str = Field(min_length=1, max_length=500)


class AdminPasswordUpdate(BaseModel):
    current_password: str = Field(min_length=1, max_length=500)
    new_password: str = Field(min_length=8, max_length=500)

    @field_validator("new_password")
    @classmethod
    def require_meaningful_password(cls, value: str) -> str:
        if len(value.strip()) < 8:
            raise ValueError("新密码去除首尾空格后至少需要 8 位")
        return value


class StorageLocationCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    path: str = Field(min_length=1, max_length=2000)
    create_directory: bool = True

    @field_validator("name", "path")
    @classmethod
    def strip_text(cls, value: str) -> str:
        return value.strip()


class StorageRootAction(BaseModel):
    path: str = Field(min_length=1, max_length=2000)

    @field_validator("path")
    @classmethod
    def strip_path(cls, value: str) -> str:
        return value.strip()


class AdminResetRequest(BaseModel):
    confirmation: Literal["RESET"]


class AdminResetResponse(BaseModel):
    books_deleted: int
    shelves_deleted: int
    storage_locations_deleted: int
    progress_deleted: int
    covers_deleted: int


class ShelfCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    storage_location_id: str
    relative_path: str = Field(min_length=1, max_length=1000)
    auto_scan_enabled: bool = True
    scan_interval_minutes: int = Field(default=5, ge=1, le=10080)
    scan_after_create: bool = True
    is_hidden: bool = False
    access_pin: str | None = Field(default=None, pattern=r"^\d{4}$")

    @field_validator("name", "relative_path")
    @classmethod
    def strip_text(cls, value: str) -> str:
        return value.strip()


class ShelfUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    auto_scan_enabled: bool | None = None
    scan_interval_minutes: int | None = Field(default=None, ge=1, le=10080)
    is_hidden: bool | None = None
    access_pin: str | None = Field(default=None, pattern=r"^\d{4}$")

    @field_validator("name")
    @classmethod
    def strip_name(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None


class StorageRootView(BaseModel):
    path: str
    exists: bool
    writable: bool
    free_bytes: int | None
    total_bytes: int | None


class StorageLocationView(BaseModel):
    id: str
    name: str
    path: str
    shelf_count: int
    created_at: datetime


class ShelfView(BaseModel):
    id: str
    name: str
    storage_location_id: str
    storage_location_name: str
    relative_path: str
    resolved_path: str
    is_hidden: bool
    pin_configured: bool
    auto_scan_enabled: bool
    scan_interval_minutes: int
    scan_status: str
    book_count: int
    total_bytes: int
    last_scan_started_at: datetime | None
    last_scan_completed_at: datetime | None
    last_scan_summary: dict | None
    last_scan_error: str | None
    created_at: datetime


class AdminOverview(BaseModel):
    storage_roots: list[StorageRootView]
    storage_locations: list[StorageLocationView]
    shelves: list[ShelfView]
    total_books: int
    total_bytes: int
    scanning_count: int


class AdminBookView(BaseModel):
    id: str
    shelf_id: str
    title: str
    author: str | None
    format: str
    filename: str
    directory: str
    file_path: str
    file_size: int
    chapter_count: int | None
    page_count: int | None
    parse_status: str
    parse_warnings: list[str]
    cover_status: str
    cover_source: str | None
    cover_url: str | None
    chapter_split_mode: str
    chapter_split_config: dict
    chapter_split_revision: int
    last_chapter_split_at: datetime | None
    updated_at: datetime


class ChapterSplitUpdate(BaseModel):
    mode: Literal["auto", "source", "strict", "expanded", "fixed", "single"]
    segment_size: int = Field(default=12_000, ge=1_000, le=100_000)


class BookMetadataUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=500)
    author: str | None = Field(default=None, max_length=500)
    reset_title: bool = False
    reset_author: bool = False
    cover_base64: str | None = Field(default=None, max_length=11_200_000)
    cover_filename: str | None = Field(default=None, max_length=255)
    remove_cover: bool = False

    @field_validator("title", "author")
    @classmethod
    def strip_metadata(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None

    @field_validator("cover_filename")
    @classmethod
    def safe_cover_filename(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized or "/" in normalized or "\\" in normalized or "\x00" in normalized:
            raise ValueError("封面文件名无效")
        suffix = normalized.rsplit(".", 1)[-1].lower() if "." in normalized else ""
        if suffix not in {"jpg", "jpeg", "png"}:
            raise ValueError("封面仅支持 JPG 或 PNG")
        return normalized
