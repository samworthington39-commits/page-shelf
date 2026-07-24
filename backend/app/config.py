from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "sqlite:///./data/bookshelf.db"
    library_path: Path = Path("./library")
    cover_path: Path = Path("./data/covers")
    cors_origins: str = ""
    storage_allowed_roots: str = ""
    storage_auto_discover_mounts: bool = True
    storage_excluded_roots: str = "/data"
    admin_password: str = "112233"
    admin_session_secret: str = ""
    admin_credentials_path: Path = Path("./data/admin_credentials.json")
    admin_session_hours: int = 24
    auto_scan_poll_seconds: int = 30
    enable_api_docs: bool = False

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def cors_origin_list(self) -> list[str]:
        return [
            item.strip().rstrip("/")
            for item in self.cors_origins.split(",")
            if item.strip() and item.strip() != "*"
        ]

    @property
    def storage_root_paths(self) -> list[Path]:
        configured = [Path(item.strip()).resolve() for item in self.storage_allowed_roots.split(",") if item.strip()]
        discovered: list[Path] = []
        if self.storage_auto_discover_mounts:
            from .services.mount_discovery import discover_container_mounts

            discovered = discover_container_mounts()
        excluded = [Path(item.strip()).resolve() for item in self.storage_excluded_roots.split(",") if item.strip()]
        roots: list[Path] = []
        for root in [*configured, *discovered]:
            if any(root == blocked or blocked in root.parents for blocked in excluded):
                continue
            if root not in roots:
                roots.append(root)
        return roots or [self.library_path.resolve()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
