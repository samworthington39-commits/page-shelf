from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    event,
)
from sqlalchemy.orm import Mapped, Session, mapped_column, relationship

from .db import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class StorageLocation(Base):
    __tablename__ = "storage_locations"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(200), unique=True)
    path: Mapped[str] = mapped_column(Text, unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    shelves: Mapped[list[Shelf]] = relationship(back_populates="storage_location")


class Shelf(Base):
    __tablename__ = "shelves"
    __table_args__ = (
        UniqueConstraint("storage_location_id", "relative_path", name="uq_shelf_storage_path"),
        CheckConstraint("scan_interval_minutes >= 1", name="ck_shelf_scan_interval"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(200), unique=True)
    storage_location_id: Mapped[str] = mapped_column(
        ForeignKey("storage_locations.id", ondelete="RESTRICT"), index=True
    )
    relative_path: Mapped[str] = mapped_column(Text)
    access_pin_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    auto_scan_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    scan_interval_minutes: Mapped[int] = mapped_column(Integer, default=5)
    scan_interval_unit: Mapped[str] = mapped_column(String(8), default="minutes")
    scan_status: Mapped[str] = mapped_column(String(24), default="idle")
    last_scan_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_scan_completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_scan_summary_json: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    last_scan_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    storage_location: Mapped[StorageLocation] = relationship(back_populates="shelves")
    books: Mapped[list[Book]] = relationship(back_populates="shelf")


class Book(Base):
    __tablename__ = "books"
    __table_args__ = (
        CheckConstraint(
            "format != 'pdf' OR chapter_count IS NULL OR chapter_count = 0",
            name="ck_pdf_has_no_chapters",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    shelf_id: Mapped[str | None] = mapped_column(
        ForeignKey("shelves.id", ondelete="SET NULL"), nullable=True, index=True
    )
    format: Mapped[str] = mapped_column(String(12), index=True)
    shelf_visible: Mapped[bool] = mapped_column(Boolean, default=True)
    title: Mapped[str] = mapped_column(String(500))
    author: Mapped[str | None] = mapped_column(String(500), nullable=True)
    subject: Mapped[str | None] = mapped_column(Text, nullable=True)
    keywords: Mapped[str | None] = mapped_column(Text, nullable=True)
    document_created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    document_modified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    page_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    chapter_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    chapter_split_mode: Mapped[str] = mapped_column(String(24), default="auto")
    chapter_split_config_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    chapter_split_revision: Mapped[int] = mapped_column(Integer, default=0)
    last_chapter_split_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    metadata_overrides_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    password_required: Mapped[bool] = mapped_column(Boolean, default=False)
    can_open: Mapped[bool] = mapped_column(Boolean, default=True)
    pdf_navigation_json: Mapped[list[dict[str, Any]] | None] = mapped_column(JSON, nullable=True)
    cover_status: Mapped[str] = mapped_column(String(24), default="unavailable")
    cover_path: Mapped[str | None] = mapped_column(Text, nullable=True)
    parse_status: Mapped[str] = mapped_column(String(24), default="pending")
    parse_warnings_json: Mapped[list[str]] = mapped_column(JSON, default=list)
    file_size: Mapped[int] = mapped_column(BigInteger)
    fingerprint: Mapped[str] = mapped_column(String(64), index=True)
    mime_type: Mapped[str] = mapped_column(String(100))
    file_path: Mapped[str] = mapped_column(Text, unique=True)
    source_modified_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    imported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    chapters: Mapped[list[Chapter]] = relationship(
        back_populates="book", cascade="all, delete-orphan", order_by="Chapter.position"
    )
    reading_progress: Mapped[list[ReadingProgress]] = relationship(
        back_populates="book", cascade="all, delete-orphan"
    )
    shelf: Mapped[Shelf | None] = relationship(back_populates="books")


class Chapter(Base):
    __tablename__ = "chapters"
    __table_args__ = (UniqueConstraint("book_id", "position", name="uq_chapter_position"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    book_id: Mapped[str] = mapped_column(ForeignKey("books.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(500))
    original_title: Mapped[str | None] = mapped_column(String(500), nullable=True)
    normalized_title: Mapped[str | None] = mapped_column(String(500), nullable=True)
    position: Mapped[int] = mapped_column(Integer)
    body: Mapped[str] = mapped_column(Text)
    source_href: Mapped[str | None] = mapped_column(Text, nullable=True)
    volume_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    chapter_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    secondary_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    suffix_order: Mapped[int] = mapped_column(Integer, default=0)
    level: Mapped[str] = mapped_column(String(24), default="chapter")
    special_type: Mapped[str | None] = mapped_column(String(50), nullable=True)
    start_offset: Mapped[int | None] = mapped_column(Integer, nullable=True)
    end_offset: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source_position: Mapped[int | None] = mapped_column(Integer, nullable=True)

    book: Mapped[Book] = relationship(back_populates="chapters")


class ReadingProgress(Base):
    __tablename__ = "reading_progress"
    __table_args__ = (
        UniqueConstraint("book_id", "device_id", name="uq_progress_book_device"),
        CheckConstraint("progression >= 0 AND progression <= 1", name="ck_progression_range"),
        CheckConstraint("page_index IS NULL OR page_index >= 0", name="ck_page_index_non_negative"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    book_id: Mapped[str] = mapped_column(ForeignKey("books.id", ondelete="CASCADE"), index=True)
    device_id: Mapped[str] = mapped_column(String(200), index=True)
    page_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    page_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    progression: Mapped[float] = mapped_column(Float, default=0.0)
    locator_json: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    book: Mapped[Book] = relationship(back_populates="reading_progress")


@event.listens_for(Session, "before_flush")
def _prevent_pdf_chapters(session: Session, _flush_context, _instances) -> None:  # type: ignore[no-untyped-def]
    for value in session.new:
        if not isinstance(value, Chapter):
            continue
        book = value.book or (session.get(Book, value.book_id) if value.book_id else None)
        if book is not None and book.format == "pdf":
            raise ValueError("PDF cannot be written to the chapters table")
