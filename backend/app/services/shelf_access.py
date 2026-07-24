from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import os

from fastapi import HTTPException, status

from ..models import Book, Shelf


PIN_ITERATIONS = 210_000


def hash_shelf_pin(pin: str) -> str:
    if len(pin) != 4 or not pin.isdigit():
        raise ValueError("书架密码必须是四位数字")
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, PIN_ITERATIONS)
    return "$".join(
        (
            "pbkdf2_sha256",
            str(PIN_ITERATIONS),
            base64.urlsafe_b64encode(salt).decode(),
            base64.urlsafe_b64encode(digest).decode(),
        )
    )


def verify_shelf_pin(pin: str | None, encoded: str | None) -> bool:
    if pin is None or encoded is None:
        return False
    try:
        algorithm, iterations, salt_text, digest_text = encoded.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        salt = base64.urlsafe_b64decode(salt_text.encode())
        expected = base64.urlsafe_b64decode(digest_text.encode())
        rounds = int(iterations)
        if rounds < 100_000 or rounds > 1_000_000 or len(salt) != 16 or len(expected) != 32:
            return False
        actual = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, rounds)
        return hmac.compare_digest(actual, expected)
    except (TypeError, ValueError, binascii.Error):
        return False


def require_shelf_pin(shelf: Shelf, pin: str | None) -> None:
    if shelf.access_pin_hash is None:
        return
    if not verify_shelf_pin(pin, shelf.access_pin_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="书架密码错误",
        )


def require_book_access(book: Book, pin: str | None) -> None:
    if book.shelf is not None:
        require_shelf_pin(book.shelf, pin)
