from __future__ import annotations

import os
import shutil
import tempfile
from collections.abc import Generator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import delete


TEST_ROOT = Path(tempfile.mkdtemp(prefix="page-shelf-tests-"))
TEST_LIBRARY = TEST_ROOT / "library"
TEST_COVERS = TEST_ROOT / "covers"
TEST_CREDENTIALS = TEST_ROOT / "admin_credentials.json"
TEST_LIBRARY.mkdir(parents=True)
TEST_COVERS.mkdir(parents=True)
os.environ["DATABASE_URL"] = f"sqlite:///{(TEST_ROOT / 'test.db').as_posix()}"
os.environ["LIBRARY_PATH"] = str(TEST_LIBRARY)
os.environ["COVER_PATH"] = str(TEST_COVERS)
os.environ["STORAGE_ALLOWED_ROOTS"] = str(TEST_LIBRARY)
os.environ["ADMIN_PASSWORD"] = "test-admin-password"
os.environ["ADMIN_SESSION_SECRET"] = "test-session-secret-that-is-not-used-outside-tests"
os.environ["ADMIN_CREDENTIALS_PATH"] = str(TEST_CREDENTIALS)
os.environ["AUTO_SCAN_POLL_SECONDS"] = "3600"

from app.db import Base, SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Book, Chapter, ReadingProgress, Shelf, StorageLocation  # noqa: E402
from app.services.login_limiter import reset_login_limiter  # noqa: E402


@pytest.fixture(autouse=True)
def clean_state() -> Generator[None, None, None]:
    reset_login_limiter()
    TEST_CREDENTIALS.unlink(missing_ok=True)
    Base.metadata.create_all(engine)
    for child in TEST_LIBRARY.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    with SessionLocal() as session:
        session.execute(delete(ReadingProgress))
        session.execute(delete(Chapter))
        session.execute(delete(Book))
        session.execute(delete(Shelf))
        session.execute(delete(StorageLocation))
        session.commit()
    yield


@pytest.fixture
def client() -> Generator[TestClient, None, None]:
    with TestClient(app) as test_client:
        login = test_client.post(
            "/api/v1/auth/login",
            json={"password": "test-admin-password"},
        )
        assert login.status_code == 200
        test_client.headers["Authorization"] = f"Bearer {login.json()['access_token']}"
        yield test_client


@pytest.fixture
def db_session():
    with SessionLocal() as session:
        yield session


def pytest_sessionfinish(session, exitstatus):  # noqa: ARG001
    engine.dispose()
    shutil.rmtree(TEST_ROOT, ignore_errors=True)
