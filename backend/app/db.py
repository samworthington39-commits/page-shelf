from collections.abc import Generator
from pathlib import Path

from sqlalchemy import create_engine, event, inspect
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    pass


def _ensure_sqlite_parent(database_url: str) -> None:
    prefix = "sqlite:///"
    if database_url.startswith(prefix) and database_url != "sqlite:///:memory:":
        Path(database_url.removeprefix(prefix)).resolve().parent.mkdir(parents=True, exist_ok=True)


settings = get_settings()
_ensure_sqlite_parent(settings.database_url)
engine = create_engine(
    settings.database_url,
    connect_args={"check_same_thread": False} if settings.database_url.startswith("sqlite") else {},
)


@event.listens_for(Engine, "connect")
def _enable_sqlite_foreign_keys(dbapi_connection, _connection_record) -> None:  # type: ignore[no-untyped-def]
    if dbapi_connection.__class__.__module__.startswith("sqlite3"):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def initialize_database() -> None:
    """Create the schema and apply the in-place migrations used by older databases."""
    Base.metadata.create_all(bind=engine)
    inspector = inspect(engine)
    if "books" not in inspector.get_table_names():
        return
    columns = {column["name"] for column in inspector.get_columns("books")}
    if "shelf_id" not in columns:
        with engine.begin() as connection:
            connection.exec_driver_sql("ALTER TABLE books ADD COLUMN shelf_id VARCHAR(36)")
            connection.exec_driver_sql("CREATE INDEX IF NOT EXISTS ix_books_shelf_id ON books (shelf_id)")
    book_shelf_columns = {column["name"] for column in inspect(engine).get_columns("books")}
    if "shelf_visible" not in book_shelf_columns:
        with engine.begin() as connection:
            connection.exec_driver_sql(
                "ALTER TABLE books ADD COLUMN shelf_visible BOOLEAN NOT NULL DEFAULT 1"
            )
    if "shelves" in inspector.get_table_names():
        shelf_columns = {column["name"] for column in inspector.get_columns("shelves")}
        with engine.begin() as connection:
            if "access_pin_hash" not in shelf_columns:
                connection.exec_driver_sql(
                    "ALTER TABLE shelves ADD COLUMN access_pin_hash VARCHAR(255)"
                )
            if "scan_interval_unit" not in shelf_columns:
                connection.exec_driver_sql(
                    "ALTER TABLE shelves ADD COLUMN scan_interval_unit VARCHAR(8) NOT NULL DEFAULT 'minutes'"
                )
            # Older releases stored whole-shelf visibility in a required
            # ``is_hidden`` column. Visibility is now controlled per book, so
            # leaving the legacy column in place makes every new insert fail:
            # the ORM no longer supplies a value and the old column has no
            # database default.
            if "is_hidden" in shelf_columns:
                connection.exec_driver_sql("ALTER TABLE shelves DROP COLUMN is_hidden")
    columns = {column["name"] for column in inspect(engine).get_columns("books")}
    book_additions = {
        "chapter_split_mode": "VARCHAR(24) NOT NULL DEFAULT 'auto'",
        "chapter_split_config_json": "JSON NOT NULL DEFAULT '{}'",
        "chapter_split_revision": "INTEGER NOT NULL DEFAULT 0",
        "last_chapter_split_at": "DATETIME",
        "metadata_overrides_json": "JSON NOT NULL DEFAULT '{}'",
    }
    with engine.begin() as connection:
        for name, definition in book_additions.items():
            if name not in columns:
                connection.exec_driver_sql(f"ALTER TABLE books ADD COLUMN {name} {definition}")

    if "chapters" in inspect(engine).get_table_names():
        chapter_columns = {column["name"] for column in inspect(engine).get_columns("chapters")}
        chapter_additions = {
            "original_title": "VARCHAR(500)",
            "normalized_title": "VARCHAR(500)",
            "volume_index": "INTEGER",
            "chapter_index": "INTEGER",
            "secondary_index": "INTEGER",
            "suffix_order": "INTEGER NOT NULL DEFAULT 0",
            "level": "VARCHAR(24) NOT NULL DEFAULT 'chapter'",
            "special_type": "VARCHAR(50)",
            "start_offset": "INTEGER",
            "end_offset": "INTEGER",
            "source_position": "INTEGER",
        }
        with engine.begin() as connection:
            for name, definition in chapter_additions.items():
                if name not in chapter_columns:
                    connection.exec_driver_sql(f"ALTER TABLE chapters ADD COLUMN {name} {definition}")
            connection.exec_driver_sql(
                "UPDATE chapters SET original_title = title WHERE original_title IS NULL"
            )
            connection.exec_driver_sql(
                "UPDATE chapters SET normalized_title = title WHERE normalized_title IS NULL"
            )


def get_db() -> Generator[Session, None, None]:
    with SessionLocal() as session:
        yield session
