"""Content-addressed working-copy snapshots and guarded reusable source lanes.

The caller holds the cooperative writer barrier during capture and the lane
lease during materialization. Git is read-only here. No Git metadata is copied:
builds which invoke Git need an explicit integration before production use.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

MARKER = ".vdx-coordinator-sources.json"
PENDING = ".vdx-coordinator-pending.json"
GENERATED = {".git", ".gradle", ".kotlin", ".cache", "build", "node_modules", "__pycache__", ".next", ".nuxt", ".venv", "venv"}
# These generated artifacts are configuration-time inputs to the local GBS
# composite. Capture only these files, never the surrounding build/cache tree.
GBS_CATALOGS = frozenset(
    f"versions/{bom}/build/tomlCatalog/{catalog}{suffix}.toml"
    for bom, catalog in (("gradle-plugin-bom", "sphereonGradlePluginBom"),
                         ("library-bom", "sphereonLibraryBom"))
    for suffix in ("", ".versioned")
)
CRASH_ARTIFACT = re.compile(r"(?:replay|hs_err)_pid[0-9]+\.log\Z|java_pid[0-9]+\.hprof\Z")
RECOVERY_MTIME_TOLERANCE_SECONDS = 5.0

if os.name == "nt":
    import ctypes
    from ctypes import wintypes
    _GET_FILE_ATTRIBUTES = ctypes.WinDLL("kernel32", use_last_error=True).GetFileAttributesW
    _GET_FILE_ATTRIBUTES.argtypes = [wintypes.LPCWSTR]
    _GET_FILE_ATTRIBUTES.restype = wintypes.DWORD


def _windows_attributes(path):
    """Read reparse attributes without requesting a full stat record.

    GetFileAttributesW reports the link's attributes, including mounted folders:
    https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfileattributesw
    Every ancestor is still checked. No attribute result is cached across calls.
    """
    name = str(path)
    if not name.startswith("\\\\?\\"):
        name = "\\\\?\\UNC\\" + name[2:] if name.startswith("\\\\") else "\\\\?\\" + name
    attributes = _GET_FILE_ATTRIBUTES(name)
    if attributes != 0xFFFFFFFF:
        return attributes
    if ctypes.get_last_error() in (2, 3, 267):
        return None
    # A share root and some filesystem implementations cannot answer this API.
    # Retain the original strict check and error behavior rather than accepting it.
    try:
        info = path.lstat()
    except (FileNotFoundError, NotADirectoryError):
        return None
    attributes = getattr(info, "st_file_attributes", 0)
    return attributes | (0x400 if stat.S_ISLNK(info.st_mode) else 0)


def _json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def _digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _guard(path):
    """Reject symlinks and all Windows reparse points, including ancestors."""
    path = Path(os.path.abspath(path))
    for part in reversed((path, *path.parents)):
        if os.name == "nt":
            attributes = _windows_attributes(part)
            if attributes is not None and attributes & 0x400:
                raise ValueError(f"Symlink/reparse path is not allowed: {part}")
            continue
        try:
            info = part.lstat()
        except (FileNotFoundError, NotADirectoryError):
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError(f"Symlink/reparse path is not allowed: {part}")
    return path


def _is_link(path):
    """True for a symlink or any Windows reparse point, without following it."""
    try:
        info = path.lstat()
    except (FileNotFoundError, NotADirectoryError):
        return False
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


def _relative(name):
    if not isinstance(name, str) or not name or "\\" in name or ":" in name or "\x00" in name:
        raise ValueError(f"Unsafe source path: {name!r}")
    parts = name.split("/")
    if any(p in ("", ".", "..") or p.lower() == ".git" or p.endswith((".", " ")) for p in parts):
        raise ValueError(f"Unsafe source path: {name!r}")
    if any(p.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))} for p in parts):
        raise ValueError(f"Reserved source path: {name!r}")
    if any(name.lower() == reserved or name.lower().startswith(reserved + "/") for reserved in (MARKER, PENDING)):
        raise ValueError("Source collides with coordinator metadata")
    return PurePosixPath(name)


def _within(root, name):
    return _guard(root.joinpath(*_relative(name).parts))


def _overlap(left, right):
    return left == right or left in right.parents or right in left.parents


def _git(repo, *args, optional=False):
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env["GIT_OPTIONAL_LOCKS"] = "0"
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, env=env, timeout=30)
    if result.returncode and not optional:
        raise RuntimeError(f"Git inventory failed in {repo}: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout if not result.returncode else b""


def _is_gbs_catalog(name):
    parts = PurePosixPath(name).parts
    return ("/".join(parts[-5:]) in GBS_CATALOGS
            and (len(parts) == 5 or parts[-6] == "gradle-build-support"))


def _inventory(source):
    files, deleted, repositories, seen = {}, [], [], set()

    def visit(repo):
        repo = _guard(repo)
        prefix = repo.relative_to(source).as_posix()
        if prefix in seen:
            return
        seen.add(prefix)
        if not (repo / ".git").exists():
            raise ValueError(f"Not an initialized Git repository: {repo}")
        _guard(repo / ".git")
        entries = []
        tracked = {}
        for record in _git(repo, "ls-files", "--stage", "-z").split(b"\0"):
            if not record:
                continue
            meta, raw_name = record.split(b"\t", 1)
            mode, oid, stage = meta.decode("ascii").split()
            name = os.fsdecode(raw_name)
            _relative(name)
            if stage != "0":
                raise ValueError(f"Unmerged index entry: {repo / name}")
            entries.append({"path": name, "mode": mode, "oid": oid})
            tracked[name] = mode
        branch = _git(repo, "symbolic-ref", "--quiet", "--short", "HEAD", optional=True).decode().strip() or None
        repositories.append({"path": prefix, "head": _git(repo, "rev-parse", "--verify", "HEAD", optional=True).decode().strip() or None,
                             "branch": branch, "index": sorted(entries, key=lambda item: item["path"])})
        others = {os.fsdecode(name).rstrip("/") for name in _git(repo, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0") if name}
        catalogs = GBS_CATALOGS if repo.name == "gradle-build-support" and {
            "settings.gradle.kts", "versions/gradle-plugin-bom/build.gradle.kts",
            "versions/library-bom/build.gradle.kts",
        }.issubset(tracked) else frozenset()
        def inspect(name):
            _relative(name)
            path = _within(repo, name)
            full_name = path.relative_to(source).as_posix()
            is_tracked = name in tracked
            if not is_tracked and name not in catalogs and any(part.lower() in GENERATED for part in PurePosixPath(name).parts):
                return None
            if tracked.get(name) == "160000":
                if (path / ".git").exists():
                    return ("repository", path)
                elif path.exists() and any(path.iterdir()):
                    raise ValueError(f"Gitlink directory is not initialized: {path}")
                return None
            if not path.exists():
                if is_tracked:
                    return ("deleted", full_name)
                return None
            if name in catalogs and not path.is_file():
                raise ValueError(f"Nonregular source input: {path}")
            if path.is_dir() and (path / ".git").exists():
                return ("repository", path)
            if path.is_dir() and is_tracked:
                # A tracked file replaced by a directory is a deletion plus
                # separate untracked children, all listed by Git above.
                return ("deleted", full_name)
            info = path.stat()
            if not stat.S_ISREG(info.st_mode):
                raise ValueError(f"Nonregular source input: {path}")
            mode = stat.S_IMODE(info.st_mode)
            if os.name == "nt":
                mode = 0o755 if tracked.get(name) == "100755" else 0o644
            return ("file", {"path": full_name, "sha256": _digest(path), "size": info.st_size,
                             "mode": mode, "tracked": is_tracked, "repository": prefix})

        # Independent guarded reads may overlap; apply results and recurse in Git order.
        # The fixed bound avoids unbounded filesystem pressure across shared checkouts.
        records = list(readers.map(inspect, sorted(set(tracked) | others | catalogs)))
        for record in records:
            if record is None:
                continue
            kind, value = record
            if kind == "repository":
                visit(value)
            elif kind == "deleted":
                deleted.append(value)
            else:
                files[value["path"]] = value

    with ThreadPoolExecutor(max_workers=4, thread_name_prefix="snapshot-inventory") as readers:
        visit(source)
    return {"version": 1, "source": str(source), "repositories": sorted(repositories, key=lambda item: item["path"]), "deleted": sorted(deleted), "files": [files[name] for name in sorted(files)]}


def _verify(snapshot):
    snapshot = _guard(snapshot)
    manifest_path = _guard(snapshot / "manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identity = manifest.get("id")
    body = {key: value for key, value in manifest.items() if key != "id"}
    if not isinstance(identity, str) or hashlib.sha256(_json(body)).hexdigest() != identity or snapshot.name != identity:
        raise ValueError("Snapshot manifest identity mismatch")
    if manifest.get("version") != 1 or not Path(manifest["source"]).is_absolute():
        raise ValueError("Invalid snapshot manifest")
    names = set()
    for item in manifest["files"]:
        name = item["path"]
        _relative(name)
        folded = os.path.normcase(name)
        if folded in names:
            raise ValueError("Duplicate snapshot source path")
        names.add(folded)

    def verify_file(item):
        name = item["path"]
        path = _within(snapshot / "content", name)
        if not path.is_file() or path.stat().st_size != item["size"] or _digest(path) != item["sha256"]:
            raise ValueError(f"Snapshot content integrity failure: {name}")
        if type(item["mode"]) is not int or item["mode"] < 0 or item["mode"] > 0o777:
            raise ValueError("Invalid snapshot file mode")
    with ThreadPoolExecutor(max_workers=4, thread_name_prefix="snapshot-verify") as readers:
        # Exhaust map so any path, mode or checksum failure reaches the caller.
        list(readers.map(verify_file, manifest["files"]))
    return manifest


def _cleanup_staging(path):
    def writable_remove(func, target, _error):
        os.chmod(target, 0o700)
        func(target)
    shutil.rmtree(path, onerror=writable_remove)


def _blob_path(state, digest):
    """Return the coordinator-owned immutable content-cache path for a digest."""
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        raise ValueError("Invalid content digest")
    return _guard(state / "blobs" / digest[:2] / digest)


def _ensure_blob(state, source, digest, size):
    """Populate the state-local content cache and return a verified blob.

    Snapshots and their blobs live below the same state directory, so a hard
    link is possible even when the checkout is on another volume.  A failed
    hard-link operation falls back to a verified copy for filesystems that do
    not support links.  Existing cache entries are always checked before use;
    a damaged entry is replaced atomically without touching any snapshot that
    may already reference its previous inode.
    """
    blob = _blob_path(state, digest)
    # Validate the existing inode before linking it. A same-size corruption
    # must be repaired here; otherwise every later snapshot would inherit a
    # permanently bad cache entry. The destination install verifies again to
    # close the race between this check and linking.
    if blob.is_file() and blob.stat().st_size == size and _digest(blob) == digest:
        return blob
    blob.parent.mkdir(parents=True, exist_ok=True)
    temporary = blob.parent / (".vdx-blob-" + uuid.uuid4().hex)
    try:
        shutil.copyfile(source, temporary)
        if temporary.stat().st_size != size or _digest(temporary) != digest:
            raise RuntimeError(f"Source changed during capture: {source}")
        temporary.chmod(0o444)
        if os.name == "nt" and blob.exists():
            # Windows refuses to replace a read-only target. Clear the
            # attribute before replacing the cache path; existing snapshot
            # hardlinks continue to reference their old inode.
            blob.chmod(0o644)
        os.replace(temporary, blob)
    finally:
        if temporary.exists():
            temporary.unlink()
    return blob


def _link_blob(blob, destination, digest, size):
    """Install a verified blob in a staging snapshot."""
    try:
        os.link(blob, destination)
    except OSError:
        shutil.copyfile(blob, destination)
    if destination.stat().st_size != size or _digest(destination) != digest:
        raise RuntimeError(f"Snapshot content integrity failure: {destination}")


def _ownership(record, lane):
    """Authenticate claimed paths against the content-addressed manifest."""
    if not isinstance(record, dict) or Path(record.get("lane", "")).resolve() != Path(lane).resolve():
        raise ValueError("Lane ownership identity mismatch")
    manifest = _verify(Path(record["manifest"]).resolve())
    if record.get("snapshot") != manifest["id"] or record.get("files") != [item["path"] for item in manifest["files"]]:
        raise ValueError("Lane ownership manifest mismatch")
    return manifest


def _atomic_record(path, record):
    temp = path.parent / (".vdx-record-" + uuid.uuid4().hex)
    try:
        with temp.open("xb") as stream:
            stream.write(_json(record))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        if os.name != "nt":
            fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
    finally:
        if temp.exists():
            temp.unlink()


def _scan_unpinned_files(lane, allowed, collect_crash_artifacts=False):
    """Inspect input directories, pruning only explicit generated outputs."""
    if not lane.exists():
        return
    gradle_roots = {
        PurePosixPath(name).parent.as_posix()
        for name in allowed
        if PurePosixPath(name).name.lower() in {"gradlew", "gradlew.bat"}
    }
    # Pinned GBS catalogs share build/ with ordinary generated outputs. Keep
    # pruning those output trees; verify_lane checks each pinned file's bytes
    # and safe path independently. Other tracked build/ sources remain strict.
    strict_dirs = {parent.as_posix() for name in allowed if not _is_gbs_catalog(name)
                   for parent in PurePosixPath(name).parents}

    def walk_error(error):
        raise error

    crash_artifacts = []
    for base, directories, filenames in os.walk(lane, followlinks=False, onerror=walk_error):
        base = Path(base)
        base = _guard(Path(base))
        relative_base = base.relative_to(lane)
        for name in list(directories):
            relative = (relative_base / name).as_posix()
            if collect_crash_artifacts and CRASH_ARTIFACT.fullmatch(name):
                raise ValueError(f"Crash artifact is not a regular file: {relative}")
            if name.lower() == ".git":
                raise ValueError(f"Git metadata is not permitted in a lane: {relative}")
            if (name.lower() in GENERATED and relative not in strict_dirs
                    and "src" not in {part.lower() for part in relative_base.parts}):
                directories.remove(name)
            else:
                _guard(base / name)
        for name in filenames:
            path = _guard(base / name)
            relative = path.relative_to(lane).as_posix()
            if relative in allowed or (base == lane and name in (MARKER, PENDING)):
                continue
            if collect_crash_artifacts and CRASH_ARTIFACT.fullmatch(name):
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError(f"Crash artifact is not a regular file: {relative}")
                crash_artifacts.append(path)
                continue
            if (re.fullmatch(r"hs_err_pid[0-9]+\.log", name)
                    and relative_base.as_posix() in gradle_roots):
                continue
            # Only root metadata write temporaries are exempt. A source copy
            # temporary may be picked up as a resource, so it fails closed.
            suffix = name[len(".vdx-record-"):] if name.startswith(".vdx-record-") else ""
            if (base == lane and len(suffix) == 32
                    and all(char in "0123456789abcdef" for char in suffix)):
                continue
            raise ValueError(f"Unpinned file in lane source inputs: {relative}")
    return crash_artifacts


def _reject_unpinned_files(lane, allowed):
    """Reject all unpinned files except the existing nested Gradle hs_err allowance."""
    _scan_unpinned_files(lane, allowed)


def verify_lane(snapshot: Path, lane: Path) -> None:
    """Raise on altered or missing pinned inputs; never repair or delete files."""
    snapshot, lane = _guard(snapshot), _guard(lane)
    manifest = _verify(snapshot)
    if _guard(lane / PENDING).exists():
        raise ValueError("Lane has an incomplete source materialization")
    record = json.loads(_guard(lane / MARKER).read_text(encoding="utf-8"))
    _ownership(record, lane)
    if record["snapshot"] != manifest["id"]:
        raise ValueError("Lane is installed from a different snapshot")
    _reject_unpinned_files(lane, {item["path"] for item in manifest["files"]})
    for item in manifest["files"]:
        path = _within(lane, item["path"])
        if not path.is_file() or path.stat().st_size != item["size"] or _digest(path) != item["sha256"]:
            raise ValueError(f"Build changed pinned source input: {item['path']}")
        if os.name != "nt" and stat.S_IMODE(path.stat().st_mode) != (item["mode"] | stat.S_IWUSR):
            raise ValueError(f"Build changed pinned source mode: {item['path']}")


def _sync_directory(path):
    """Persist directory entries where the platform exposes directory fsync."""
    try:
        descriptor = os.open(path, os.O_RDONLY)
    except OSError:
        if os.name == "nt":
            return
        raise
    try:
        try:
            os.fsync(descriptor)
        except OSError:
            if os.name != "nt":
                raise
    finally:
        os.close(descriptor)


def _publish_archive_directory(staging, destination):
    """Atomically publish a flushed archive directory with platform durability."""
    if os.name == "nt":
        import ctypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        move = kernel.MoveFileExW
        move.argtypes = (ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32)
        move.restype = ctypes.c_int
        if not move(str(staging), str(destination), 0x00000008):  # MOVEFILE_WRITE_THROUGH
            raise ctypes.WinError(ctypes.get_last_error())
        return
    os.replace(staging, destination)
    _sync_directory(destination.parent)


def _job_provenance(path, mtime, lane_number, jobs, pid_alive, tolerance):
    matching = []
    for job in jobs:
        if (job.get("lane") != lane_number or job.get("status") not in {"passed", "failed", "interrupted", "superseded"}
                or not isinstance(job.get("started"), (int, float))
                or not isinstance(job.get("finished"), (int, float))):
            continue
        started, finished = float(job["started"]), float(job["finished"])
        if started > finished or not started - tolerance <= mtime <= finished + tolerance:
            continue
        if job.get("pid") is not None and pid_alive(job["pid"]):
            raise ValueError(f"A terminal job process is still alive: {job.get('id', '<unknown>')}")
        spec = job.get("spec") if isinstance(job.get("spec"), dict) else {}
        matching.append({
            "id": job.get("id"), "status": job.get("status"), "lane": lane_number,
            "started": started, "finished": finished, "pid": job.get("pid"),
            "snapshot": spec.get("snapshot"),
        })
    if len(matching) != 1:
        relative = path.as_posix()
        if not matching:
            raise ValueError(f"Crash artifact has no same-lane terminal-job provenance: {relative}")
        raise ValueError(f"Crash artifact has ambiguous terminal-job provenance: {relative}")
    return matching[0]


def _write_archive_file(source, destination, expected):
    source = _guard(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as input_stream, destination.open("xb") as output_stream:
        digest = hashlib.sha256()
        size = 0
        while True:
            chunk = input_stream.read(1024 * 1024)
            if not chunk:
                break
            output_stream.write(chunk)
            digest.update(chunk)
            size += len(chunk)
        output_stream.flush()
        os.fsync(output_stream.fileno())
    if size != expected["size"] or digest.hexdigest() != expected["sha256"]:
        raise RuntimeError(f"Crash artifact changed while being archived: {expected['relative_path']}")


def _restore_recovered_file(archive_file, record, lane):
    destination = _within(lane, record["relative_path"])
    if destination.exists() or destination.is_symlink():
        info = destination.lstat()
        if (stat.S_ISREG(info.st_mode) and info.st_size == record["size"]
                and _digest(destination) == record["sha256"]):
            return
        raise RuntimeError(f"Cannot restore recovered source file because its path is occupied: {record['relative_path']}")
    archive_file = _guard(archive_file)
    destination.parent.mkdir(parents=True, exist_ok=True)
    _guard(destination.parent)
    temporary = destination.with_name(".vdx-restore-" + uuid.uuid4().hex)
    try:
        with archive_file.open("rb") as source, temporary.open("xb") as target:
            shutil.copyfileobj(source, target)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary, record["mode"])
        os.utime(temporary, ns=(record["atime_ns"], record["mtime_ns"]))
        os.replace(temporary, destination)
        _sync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def recover_crash_artifacts(snapshot: Path, lane: Path, archive_root: Path, lane_number: int,
                            terminal_jobs, pid_alive, tolerance=RECOVERY_MTIME_TOLERANCE_SECONDS,
                            precondition_check=None) -> dict:
    """Archive precisely proven coordinator-lane JVM crash logs, then remove them.

    The caller must hold the coordinator's nonblocking lane lock and have verified
    the drain/no-active-job/process preconditions. On a post-archive removal or
    integrity failure, removed files are restored and the durable archive is kept.
    """
    snapshot, lane, archive_root = _guard(snapshot), _guard(lane), _guard(archive_root)
    if _overlap(lane, archive_root):
        raise ValueError("Crash artifact archive must be outside the lane")
    if not isinstance(lane_number, int) or lane_number < 0:
        raise ValueError("Lane number must be a nonnegative integer")
    if not isinstance(tolerance, (int, float)) or tolerance < 0 or tolerance > 60:
        raise ValueError("Invalid crash artifact timestamp tolerance")
    if precondition_check is not None:
        precondition_check()
    manifest = _verify(snapshot)
    if _guard(lane / PENDING).exists():
        raise ValueError("Lane has an incomplete source materialization")
    lane_record = json.loads(_guard(lane / MARKER).read_text(encoding="utf-8"))
    _ownership(lane_record, lane)
    if lane_record["snapshot"] != manifest["id"]:
        raise ValueError("Lane is installed from a different snapshot")
    artifacts = _scan_unpinned_files(lane, {item["path"] for item in manifest["files"]}, collect_crash_artifacts=True)
    if not artifacts:
        raise ValueError("Lane has no eligible JVM crash artifacts")

    records = []
    for path in artifacts:
        path = _guard(path)
        info = path.lstat()
        relative = path.relative_to(lane).as_posix()
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(f"Crash artifact is not a regular file: {relative}")
        job = _job_provenance(path, info.st_mtime, lane_number, terminal_jobs, pid_alive, float(tolerance))
        records.append({
            "relative_path": relative, "size": info.st_size, "sha256": _digest(path),
            "mtime": info.st_mtime, "mtime_ns": info.st_mtime_ns, "atime_ns": info.st_atime_ns,
            "mode": stat.S_IMODE(info.st_mode), "device": info.st_dev, "inode": info.st_ino,
            "archive_file": f"artifact-{len(records):04d}.bin",
            "job": job,
        })

    archive_root.mkdir(parents=True, exist_ok=True)
    _guard(archive_root)
    recovery_id = "lane-" + str(lane_number) + "-" + uuid.uuid4().hex
    staging = archive_root / (".staging-" + recovery_id)
    destination = archive_root / recovery_id
    staging.mkdir()
    archived = []
    removed = []
    try:
        for path, record in zip(artifacts, records):
            archive_file = staging / record["archive_file"]
            _write_archive_file(path, archive_file, record)
            archived.append({"source": path, "record": record, "archive_file": archive_file})
        archive_manifest = {
            "schema_version": 1, "recovery_id": recovery_id, "lane": lane_number,
            "created_at": time.time(), "timestamp_tolerance_seconds": float(tolerance),
            "artifacts": [{key: value for key, value in record.items() if key not in {"device", "inode", "mode", "atime_ns"}}
                          for record in records],
        }
        manifest_path = staging / "recovery.json"
        with manifest_path.open("xb") as stream:
            stream.write(_json(archive_manifest))
            stream.flush()
            os.fsync(stream.fileno())
        for directory, _, _ in os.walk(staging, topdown=False):
            _sync_directory(directory)
        _publish_archive_directory(staging, destination)

        for item in archived:
            archive_file = _guard(destination / item["record"]["archive_file"])
            if _digest(archive_file) != item["record"]["sha256"]:
                raise RuntimeError(f"Archived crash artifact failed SHA-256 verification: {item['record']['relative_path']}")
        if json.loads((destination / "recovery.json").read_text(encoding="utf-8")) != archive_manifest:
            raise RuntimeError("Crash artifact recovery metadata failed verification")

        if precondition_check is not None:
            precondition_check()
        for item in archived:
            path, record = item["source"], item["record"]
            path = _guard(path)
            info = path.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_dev != record["device"] or info.st_ino != record["inode"]
                    or info.st_size != record["size"] or info.st_mtime_ns != record["mtime_ns"]
                    or _digest(path) != record["sha256"]):
                raise RuntimeError(f"Crash artifact changed after archive creation: {record['relative_path']}")
            removed.append(item)
            path.unlink()
            _sync_directory(path.parent)
        verify_lane(snapshot, lane)
    except BaseException as error:
        for item in reversed(removed):
            try:
                _restore_recovered_file(destination / item["record"]["archive_file"], item["record"], lane)
            except Exception as rollback_error:
                raise RuntimeError(f"Recovery archive retained at {destination}; rollback failed: {rollback_error}") from error
        if destination.exists():
            raise RuntimeError(f"Recovery archive retained at {destination}; original files were restored where removed: {error}") from error
        raise
    finally:
        if staging.exists():
            shutil.rmtree(staging)

    return {"recovery_id": recovery_id, "archive": str(destination), "lane": lane_number,
            "artifacts": [{"path": record["relative_path"], "sha256": record["sha256"], "job": record["job"]}
                          for record in records]}


def _discover_job_crash_artifacts(root, prefix, started, finished):
    """Scan without repeated ancestor walks; keep links out and verify directories."""
    root = _guard(root)
    observed, found = [], []

    def directory_identity(path, info):
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError(f'Symlink/reparse path is not allowed: {path}')
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError(f'Crash scan directory changed: {path}')
        return (info.st_dev, info.st_ino, info.st_mode)

    def visit(folder, info):
        observed.append((folder, directory_identity(folder, info)))
        with os.scandir(folder) as entries:
            for entry in entries:
                path = Path(entry.path)
                if entry.is_dir(follow_symlinks=False):
                    info = path.lstat()
                    # Generated package junctions are walked at their real target,
                    # if that target is present under the source tree.
                    if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                        continue
                    visit(path, info)
                elif CRASH_ARTIFACT.fullmatch(entry.name):
                    path = _guard(path)
                    info = path.lstat()
                    if not stat.S_ISREG(info.st_mode):
                        raise ValueError(f'Crash artifact is not a regular file: {path}')
                    if started <= info.st_mtime <= finished:
                        found.append((path, prefix + '/' + path.relative_to(root).as_posix(), info))
    visit(root, root.lstat())
    for path, before in observed:
        if directory_identity(path, path.lstat()) != before:
            raise ValueError(f'Crash scan directory changed: {path}')
    _guard(root)
    return found


def archive_job_crash_artifacts(lane: Path, gradle_home: Path, archive_root: Path,
                                lane_number: int, job: dict) -> dict:
    """Durably archive crash artifacts created during one completed lane job."""
    lane, gradle_home, archive_root = _guard(lane), _guard(gradle_home), _guard(archive_root)
    started, finished = float(job['started']), float(job['finished'])
    if finished < started:
        raise ValueError('Invalid job crash-artifact time window')
    roots = [(lane, 'source'), (gradle_home / 'workers', 'gradle-workers')]
    found = []
    for root, prefix in roots:
        if not root.is_dir():
            continue
        found.extend(_discover_job_crash_artifacts(root, prefix, started, finished))
    if not found:
        return {'archive': None, 'artifacts': []}
    archive_root.mkdir(parents=True, exist_ok=True)
    _guard(archive_root)
    archive_id = f'lane-{lane_number}-{job["id"]}-{uuid.uuid4().hex}'
    staging, destination = archive_root / ('.staging-' + archive_id), archive_root / archive_id
    staging.mkdir()
    records, removed = [], []
    try:
        for index, (path, relative, info) in enumerate(found):
            record = {'relative_path': relative, 'size': info.st_size, 'sha256': _digest(path),
                      'mtime': info.st_mtime, 'mtime_ns': info.st_mtime_ns,
                      'archive_file': f'artifact-{index:04d}.bin',
                      'job': {'id': job['id'], 'status': job.get('status', 'failed'), 'lane': lane_number,
                              'started': started, 'finished': finished, 'snapshot': job.get('spec', {}).get('snapshot')}}
            _write_archive_file(path, staging / record['archive_file'], record)
            records.append((path, record, info))
        manifest = {'schema_version': 1, 'recovery_id': archive_id, 'lane': lane_number,
                    'created_at': time.time(), 'timestamp_tolerance_seconds': 0,
                    'artifacts': [{k: v for k, v in record.items() if k != 'mtime_ns'} for _, record, _ in records]}
        with (staging / 'recovery.json').open('xb') as stream:
            stream.write(_json(manifest)); stream.flush(); os.fsync(stream.fileno())
        for directory, _, _ in os.walk(staging, topdown=False):
            _sync_directory(directory)
        _publish_archive_directory(staging, destination)
        for path, record, _ in records:
            if _digest(destination / record['archive_file']) != record['sha256']:
                raise RuntimeError('Crash artifact archive SHA-256 verification failed')
        for path, record, original in records:
            path = _guard(path)
            current = path.lstat()
            if current.st_ino != original.st_ino or current.st_size != record['size'] or current.st_mtime_ns != record['mtime_ns']:
                raise RuntimeError(f'Crash artifact changed during archive: {record["relative_path"]}')
            removed.append((path, record, original))
            path.unlink()
            _sync_directory(path.parent)
    except BaseException:
        for path, record, original in reversed(removed):
            if not path.exists():
                temporary = path.with_name('.vdx-restore-' + uuid.uuid4().hex)
                with (destination / record['archive_file']).open('rb') as source, temporary.open('xb') as target:
                    shutil.copyfileobj(source, target); target.flush(); os.fsync(target.fileno())
                os.chmod(temporary, stat.S_IMODE(original.st_mode))
                os.utime(temporary, ns=(original.st_atime_ns, original.st_mtime_ns))
                os.replace(temporary, path)
        raise
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return {'archive': str(destination), 'artifacts': [{'path': r['relative_path'], 'sha256': r['sha256'], 'job': r['job']} for _, r, _ in records]}


def capture(source: Path, state: Path, *, inventory=None) -> dict:
    """Capture actual Git working-copy inputs; caller holds the writer barrier."""
    source, state = _guard(source), _guard(state)
    if _overlap(source, state):
        raise ValueError("Snapshot state must be outside the source repository")
    inventory = inventory or _inventory
    before = inventory(source)
    identity = hashlib.sha256(_json(before)).hexdigest()
    manifest = {**before, "id": identity}
    provenance = [{key: repo.get(key) for key in ("path", "head", "branch")} for repo in before["repositories"]]
    parent = _guard(state / "snapshots")
    parent.mkdir(parents=True, exist_ok=True)
    destination = _guard(parent / identity)
    # A byte-identical capture is already immutable. Still perform the second
    # inventory before reusing it so a source edit during the first inventory
    # cannot be silently accepted as a stable snapshot.
    if destination.exists():
        if before != inventory(source):
            raise RuntimeError("Source inventory or content changed during capture")
        _verify(destination)
        return {"id": identity, "path": str(destination), "files": len(before["files"]), "repositories": provenance}
    staging = Path(tempfile.mkdtemp(prefix=".capture-", dir=parent))
    try:
        (staging / "content").mkdir()
        # Prepare each content identity once: parallel replacements of the same
        # read-only Windows blob would race, and duplicates need no extra copy.
        unique = {item["sha256"]: item for item in before["files"]}
        def prepare_blob(item):
            src = _within(source, item["path"])
            return item["sha256"], _ensure_blob(state, src, item["sha256"], item["size"])

        def link_file(item):
            _within(source, item["path"])
            dst = _within(staging / "content", item["path"])
            dst.parent.mkdir(parents=True, exist_ok=True)
            _link_blob(blobs[item["sha256"]], dst, item["sha256"], item["size"])

        with ThreadPoolExecutor(max_workers=4, thread_name_prefix="snapshot-capture") as readers:
            blobs = dict(readers.map(prepare_blob, unique.values()))
            list(readers.map(link_file, before["files"]))
        # Workers have joined before drift checks, publication or staging cleanup.
        if before != inventory(source):
            raise RuntimeError("Source inventory or content changed during capture")
        (staging / "manifest.json").write_bytes(_json(manifest))
        if destination.exists():
            _verify(destination)
        else:
            for path in (staging / "content").rglob("*"):
                if path.is_file():
                    path.chmod(0o444)
            (staging / "manifest.json").chmod(0o444)
            staging.rename(destination)
        return {"id": identity, "path": str(destination), "files": len(before["files"]), "repositories": provenance}
    finally:
        if staging.exists():
            _cleanup_staging(staging)


def materialize(snapshot: Path, lane: Path) -> None:
    """Restore managed sources while preserving unrelated lane build outputs."""
    snapshot, lane = _guard(snapshot), _guard(lane)
    manifest = _verify(snapshot)  # No lane mutation until all bytes are verified.
    source = _guard(Path(manifest["source"]))
    if _overlap(source, lane) or _overlap(snapshot, lane):
        raise ValueError("Lane must be disjoint from source and snapshot directories")
    if any((ancestor / ".git").exists() for ancestor in (lane, *lane.parents)):
        raise ValueError("Lane must not be inside a Git checkout")
    marker = _guard(lane / MARKER)
    pending_path = _guard(lane / PENDING)
    previous = set()
    old_record = None
    if marker.exists():
        old_record = json.loads(marker.read_text(encoding="utf-8"))
        _ownership(old_record, lane)
        previous = set(old_record["files"])
    pending = None
    if pending_path.exists():
        pending = json.loads(pending_path.read_text(encoding="utf-8"))
        if not isinstance(pending, dict) or set(pending) != {"previous", "next"}:
            raise ValueError("Invalid pending lane ownership journal")
        _ownership(pending["next"], lane)
        if pending["previous"] is not None:
            _ownership(pending["previous"], lane)
        if old_record not in (pending["previous"], pending["next"]):
            raise ValueError("Pending journal does not match lane ownership")
        if pending["next"]["snapshot"] != manifest["id"]:
            # Finish the authenticated interrupted transition before starting
            # another one, keeping its partially installed files accounted for.
            materialize(Path(pending["next"]["manifest"]), lane)
            return materialize(snapshot, lane)
        previous.update(pending["next"]["files"])
        if pending["previous"] is not None:
            previous.update(pending["previous"]["files"])
    current = {item["path"] for item in manifest["files"]}
    _reject_unpinned_files(lane, previous | current)
    obsolete = previous - current
    managed_dirs = {parent.as_posix() for name in previous for parent in PurePosixPath(name).parents if parent.as_posix() != "."}
    current_dirs = {parent.as_posix() for name in current for parent in PurePosixPath(name).parents if parent.as_posix() != "."}
    remove_dirs = set()
    # Validate the entire operation before deleting or overwriting anything.
    for name in previous | current:
        path = _within(lane, name)
        if pending is not None and name in obsolete and any(
            parent.as_posix() in current and _within(lane, parent.as_posix()).is_file()
            for parent in PurePosixPath(name).parents if parent.as_posix() != "."
        ):
            # The interrupted directory-to-file transition already replaced
            # this old child's parent; that child no longer exists.
            continue
        if path.exists() and not path.is_file():
            if pending is not None and name in obsolete and name in current_dirs and path.is_dir():
                continue
            if name not in current or name not in managed_dirs or not path.is_dir():
                raise ValueError(f"Source collides with lane directory: {name}")
            for base, dirs, filenames in os.walk(path, followlinks=False):
                base = _guard(Path(base))
                relative_dir = base.relative_to(lane).as_posix()
                if relative_dir not in managed_dirs:
                    raise ValueError(f"Source collides with unmanaged lane directory: {relative_dir}")
                remove_dirs.add(relative_dir)
                for child in dirs + filenames:
                    child_path = _guard(base / child)
                    if child in filenames and child_path.relative_to(lane).as_posix() not in obsolete:
                        raise ValueError(f"Source collides with unmanaged lane output: {child_path}")
        if path.is_file() and name in current and name not in previous:
            raise ValueError(f"Source collides with unmanaged lane output: {name}")
        for parent in path.parents:
            if parent == lane:
                break
            if parent.exists() and not parent.is_dir() and parent.relative_to(lane).as_posix() not in obsolete:
                raise ValueError(f"Source parent collides with a lane file: {parent}")
    lane.mkdir(parents=True, exist_ok=True)
    record = {"lane": str(lane), "manifest": str(snapshot), "snapshot": manifest["id"], "files": [item["path"] for item in manifest["files"]]}
    if pending is None:
        _atomic_record(pending_path, {"previous": old_record, "next": record})
    for name in sorted(obsolete):
        path = _within(lane, name)
        if path.is_file():
            path.unlink()
    for name in sorted(remove_dirs, key=lambda name: len(PurePosixPath(name).parts), reverse=True):
        _within(lane, name).rmdir()
    for item in manifest["files"]:
        dst = _within(lane, item["path"])
        desired_mode = item["mode"] | stat.S_IWUSR
        if dst.is_file():
            existing = dst.stat()
            # Windows exposes read-only rather than POSIX executable/group
            # permission bits; compare only the mode it can represent.
            mode_matches = (
                bool(existing.st_mode & stat.S_IWUSR) == bool(desired_mode & stat.S_IWUSR)
                if os.name == "nt" else stat.S_IMODE(existing.st_mode) == desired_mode
            )
            if existing.st_size == item["size"] and mode_matches and _digest(dst) == item["sha256"]:
                continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        temp = dst.parent / (".vdx-source-" + uuid.uuid4().hex)
        try:
            shutil.copyfile(_within(snapshot / "content", item["path"]), temp)
            if _digest(temp) != item["sha256"]:
                raise ValueError("Snapshot changed during materialization")
            temp.chmod(desired_mode)
            os.replace(temp, dst)
        finally:
            if temp.exists():
                temp.unlink()
    _atomic_record(marker, record)
    pending_path.unlink()
