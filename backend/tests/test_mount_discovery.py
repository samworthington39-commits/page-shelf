from pathlib import Path

from app.config import Settings
from app.services import mount_discovery
from app.services.mount_discovery import parse_container_mounts


def test_parse_container_mounts_keeps_user_volumes_and_ignores_system_mounts():
    mountinfo = """
747 723 0:101 / / rw,relatime - overlay overlay rw
917 747 259:2 /host/library /library rw,relatime - ext4 /dev/nvme0n1p2 rw
918 747 259:2 /host/data /data rw,relatime - ext4 /dev/nvme0n1p2 rw
919 747 259:2 /vol1/小说 /小说 rw,relatime - ext4 /dev/nvme0n1p2 rw
920 747 259:2 /vol1/books /books\\040archive rw,relatime - ext4 /dev/nvme0n1p2 rw
21 747 0:22 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw
31 747 0:31 /hosts /etc/hosts rw,relatime - tmpfs tmpfs rw
"""

    assert parse_container_mounts(mountinfo) == [
        Path("/library"),
        Path("/data"),
        Path("/小说"),
        Path("/books archive"),
    ]


def test_settings_merge_discovered_and_explicit_roots_while_excluding_data(monkeypatch, tmp_path):
    explicit = tmp_path / "explicit"
    discovered = tmp_path / "mounted"
    internal_data = tmp_path / "data"
    for path in (explicit, discovered, internal_data):
        path.mkdir()
    monkeypatch.setattr(
        mount_discovery,
        "discover_container_mounts",
        lambda: [discovered.resolve(), internal_data.resolve()],
    )
    settings = Settings(
        storage_allowed_roots=str(explicit),
        storage_auto_discover_mounts=True,
        storage_excluded_roots=str(internal_data),
    )

    assert settings.storage_root_paths == [explicit.resolve(), discovered.resolve()]
