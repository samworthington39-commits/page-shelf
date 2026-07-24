from __future__ import annotations

from pathlib import Path


SYSTEM_MOUNT_PREFIXES = ("/proc", "/sys", "/dev", "/etc", "/run")
SYSTEM_FILESYSTEMS = {
    "proc",
    "sysfs",
    "tmpfs",
    "devtmpfs",
    "devpts",
    "cgroup",
    "cgroup2",
    "mqueue",
    "overlay",
}


def _decode_mount_field(value: str) -> str:
    return (
        value.replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
    )


def parse_container_mounts(mountinfo: str) -> list[Path]:
    """Return user directory mount points from Linux /proc/self/mountinfo."""
    mounts: list[Path] = []
    for line in mountinfo.splitlines():
        fields = line.split()
        if len(fields) < 10 or "-" not in fields:
            continue
        separator = fields.index("-")
        if separator + 2 >= len(fields):
            continue
        mount_value = _decode_mount_field(fields[4])
        filesystem = fields[separator + 1]
        if mount_value == "/" or filesystem in SYSTEM_FILESYSTEMS:
            continue
        if any(mount_value == prefix or mount_value.startswith(f"{prefix}/") for prefix in SYSTEM_MOUNT_PREFIXES):
            continue
        mount = Path(mount_value)
        if mount not in mounts:
            mounts.append(mount)
    return mounts


def discover_container_mounts(
    mountinfo_path: Path = Path("/proc/self/mountinfo"),
) -> list[Path]:
    if not (Path("/.dockerenv").exists() or Path("/run/.containerenv").exists()):
        return []
    try:
        candidates = parse_container_mounts(mountinfo_path.read_text(encoding="utf-8"))
    except OSError:
        return []
    return [path.resolve(strict=True) for path in candidates if path.is_dir()]
