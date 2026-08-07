from sqlalchemy import create_engine, inspect

from app import db
from app.models import Shelf, StorageLocation


def test_legacy_required_is_hidden_column_is_removed(tmp_path, monkeypatch):
    legacy_engine = create_engine(f"sqlite:///{(tmp_path / 'legacy.db').as_posix()}")
    db.Base.metadata.create_all(legacy_engine)
    with legacy_engine.begin() as connection:
        connection.exec_driver_sql(
            "ALTER TABLE shelves ADD COLUMN is_hidden BOOLEAN NOT NULL"
        )
    monkeypatch.setattr(db, "engine", legacy_engine)

    db.initialize_database()

    columns = {column["name"] for column in inspect(legacy_engine).get_columns("shelves")}
    assert "is_hidden" not in columns
    with db.Session(legacy_engine) as session:
        location = StorageLocation(name="小说", path="/小说")
        session.add(location)
        session.flush()
        session.add(
            Shelf(
                name="新书架",
                storage_location_id=location.id,
                relative_path="新书架",
            )
        )
        session.commit()
