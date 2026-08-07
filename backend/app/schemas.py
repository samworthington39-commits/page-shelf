from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, computed_field, field_validator, model_validator

from .capabilities import capabilities_for


class BookSummary(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    shelf_id: str | None
    format: str
    title: str
    author: str | None
    subject: str | None
    page_count: int | None
    chapter_count: int | None
    file_size: int
    fingerprint: str
    chapter_split_revision: int = Field(default=0, exclude=True)
    mime_type: str
    cover_status: str
    parse_status: str
    parse_warnings_json: list[str]
    password_required: bool
    can_open: bool
    updated_at: datetime
    pdf_navigation_json: list[dict[str, Any]] | None = Field(default=None, exclude=True)

    @computed_field
    @property
    def capabilities(self) -> dict[str, bool]:
        return capabilities_for(self.format)

    @computed_field
    @property
    def has_pdf_navigation(self) -> bool:
        return bool(self.pdf_navigation_json)

    @computed_field
    @property
    def content_version(self) -> str:
        return f"{self.fingerprint}:{self.chapter_split_revision}"


class ShelfUnlockRequest(BaseModel):
    pin: str = Field(pattern=r"^\d{4}$")


class RecentReadingView(BaseModel):
    book: BookSummary
    progression: float = Field(ge=0, le=1)
    locator_json: dict[str, Any] | None
    updated_at: datetime


class PublicShelfView(BaseModel):
    id: str
    name: str
    locked: bool
    book_count: int
    total_bytes: int
    books: list[BookSummary]
    recent_reading: list[RecentReadingView] = Field(default_factory=list)


class BookDetail(BookSummary):
    keywords: str | None
    document_created_at: datetime | None
    document_modified_at: datetime | None
    imported_at: datetime


class TocItem(BaseModel):
    id: str
    title: str
    position: int


class TocResponse(BaseModel):
    book_id: str
    format: str
    chapter_supported: bool
    items: list[TocItem]


class ChapterResponse(BaseModel):
    id: str
    book_id: str
    title: str
    position: int
    body: str


class PdfNavigationItem(BaseModel):
    title: str
    page: int = Field(ge=1, description="One-based page number for clients")
    children: list[PdfNavigationItem] = Field(default_factory=list)


class PdfNavigationResponse(BaseModel):
    book_id: str
    page_count: int | None
    items: list[PdfNavigationItem]


class ProgressUpsert(BaseModel):
    page_index: int | None = Field(default=None, ge=0, description="Zero-based PDF page index")
    page_count: int | None = Field(default=None, gt=0)
    progression: float | None = Field(default=None, ge=0, le=1)
    locator_json: dict[str, Any] | None = None

    @model_validator(mode="after")
    def page_fields_are_a_pair(self) -> ProgressUpsert:
        if (self.page_index is None) != (self.page_count is None):
            raise ValueError("page_index and page_count must be provided together")
        return self

    @field_validator("progression")
    @classmethod
    def reject_nan(cls, value: float | None) -> float | None:
        if value is not None and value != value:
            raise ValueError("progression must be a finite number")
        return value

    @field_validator("locator_json")
    @classmethod
    def limit_locator_size(cls, value: dict[str, Any] | None) -> dict[str, Any] | None:
        if value is None:
            return None
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > 16 * 1024:
            raise ValueError("阅读定位信息不能超过 16 KB")
        pending: list[tuple[Any, int]] = [(value, 1)]
        while pending:
            item, depth = pending.pop()
            if depth > 8:
                raise ValueError("阅读定位信息嵌套过深")
            if isinstance(item, dict):
                pending.extend((child, depth + 1) for child in item.values())
            elif isinstance(item, list):
                pending.extend((child, depth + 1) for child in item)
        return value


class ProgressResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    book_id: str
    device_id: str
    page_index: int | None
    page_count: int | None
    progression: float
    locator_json: dict[str, Any] | None
    updated_at: datetime


class ScanFailure(BaseModel):
    file: str
    warning: str


class ScanResponse(BaseModel):
    discovered: int
    imported: int
    updated: int
    unchanged: int
    removed: int = 0
    failed: int
    failures: list[ScanFailure]
