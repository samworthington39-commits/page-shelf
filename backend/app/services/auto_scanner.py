from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timezone

from sqlalchemy import select, update

from ..config import Settings
from ..db import SessionLocal
from ..models import Shelf
from .shelf_scanner import ShelfScanBusyError, scan_shelf_by_id


logger = logging.getLogger(__name__)


def _is_due(shelf: Shelf, now: datetime) -> bool:
    if not shelf.auto_scan_enabled or shelf.scan_status == "scanning":
        return False
    completed = shelf.last_scan_completed_at
    if completed is None:
        return True
    if completed.tzinfo is None:
        completed = completed.replace(tzinfo=timezone.utc)
    return (now - completed).total_seconds() >= shelf.scan_interval_minutes * 60


def _scan_due_shelves(settings: Settings) -> None:
    with SessionLocal() as session:
        now = datetime.now(timezone.utc)
        shelf_ids = [
            shelf.id
            for shelf in session.scalars(select(Shelf).order_by(Shelf.created_at, Shelf.id))
            if _is_due(shelf, now)
        ]
    for shelf_id in shelf_ids:
        with SessionLocal() as session:
            try:
                scan_shelf_by_id(session, shelf_id, settings)
            except (ShelfScanBusyError, LookupError):
                continue
            except Exception:
                # scan_shelf records the actionable error for the management page
                logger.exception("书架 %s 自动扫描失败", shelf_id)


async def _auto_scan_loop(settings: Settings) -> None:
    await asyncio.sleep(3)
    while True:
        await asyncio.to_thread(_scan_due_shelves, settings)
        await asyncio.sleep(max(settings.auto_scan_poll_seconds, 5))


def _recover_interrupted_scans() -> None:
    with SessionLocal() as session:
        session.execute(
            update(Shelf)
            .where(Shelf.scan_status == "scanning")
            .values(scan_status="error", last_scan_error="扫描因后端重启而中断，请重新扫描")
        )
        session.commit()


@asynccontextmanager
async def auto_scan_lifespan(settings: Settings):  # type: ignore[no-untyped-def]
    _recover_interrupted_scans()
    task = asyncio.create_task(_auto_scan_loop(settings), name="page-shelf-auto-scan")
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
