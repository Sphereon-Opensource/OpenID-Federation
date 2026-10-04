"""Embedded Web Dashboard for local build coordinator.

Uses standard library HTTP server; zero external dependencies.
"""
import fnmatch
import hashlib
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path, PureWindowsPath, PurePosixPath
import re
import socket
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit

from processes import (available_memory_gb, get_memory_status, _inspect_processes, _candidate,
                       build_activity, reap_idle_gradle_daemons)
from registry import Registry, readonly_connect, registry_path
from store import Store, classify_task_tier, compute_dynamic_slots, validate_config


HTML_PATH = Path(__file__).with_name("dashboard.html")
_WORKTREE_CACHE = {}
_WORKTREE_CACHE_LOCK = threading.Lock()
_WORKTREE_CACHE_SECONDS = 30
_WORKTREE_OUTPUT_BYTES = 1024 * 1024
_WORKTREE_LIMIT = 256


def _discovery_path(value):
    from cli import safe_path
    # Check lexical ancestors before safe_path canonicalizes them, so a symlink
    # cannot conceal itself by resolving to an otherwise ordinary directory.
    path = Path(value).absolute()
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Symlink/reparse paths are not allowed')
    return safe_path(path)


def _git_common_directory(source):
    """Read only Git's known metadata pointers, never search the filesystem."""
    dotgit = _discovery_path(source / '.git')
    if dotgit.is_dir():
        directory = dotgit
    elif dotgit.is_file() and dotgit.stat().st_size <= 8192:
        pointer = dotgit.read_text(encoding='utf-8').strip()
        if not pointer.startswith('gitdir: '):
            return None
        directory = _discovery_path(source / pointer[8:])
    else:
        return None
    common = _discovery_path(directory / 'commondir')
    if common.is_file():
        if common.stat().st_size > 8192:
            return None
        directory = _discovery_path(directory / common.read_text(encoding='utf-8').strip())
    return directory if directory.is_dir() else None


def _registered_worktrees(source, seen_common, deadline):
    """Bound and cache one read-only Git listing per common repository."""
    try:
        common = _git_common_directory(source)
        if common is None:
            return ()
        key = os.path.normcase(str(common))
        if key in seen_common:
            return ()
        seen_common.add(key)
        topology = _discovery_path(common / 'worktrees')
        stamp = topology.stat().st_mtime_ns if topology.exists() else 0
        with _WORKTREE_CACHE_LOCK:
            now = time.monotonic()
            cached = _WORKTREE_CACHE.get(key)
            if cached and now - cached[0] < _WORKTREE_CACHE_SECONDS and cached[1] == stamp:
                return cached[2]
            remaining = deadline - now
            if remaining <= 0:
                return ()
            env = {key: value for key, value in os.environ.items()
                   if key.upper() not in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR', 'GIT_INDEX_FILE')}
            env['GIT_OPTIONAL_LOCKS'] = '0'
            paths = ()
            try:
                with tempfile.TemporaryFile() as output:
                    subprocess.run(['git', '-c', f'safe.directory={source.as_posix()}', '-C', str(source),
                                    'worktree', 'list', '--porcelain', '-z'],
                                   stdout=output, stderr=subprocess.DEVNULL, env=env,
                                   timeout=min(1.5, remaining), check=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                    if output.tell() <= _WORKTREE_OUTPUT_BYTES:
                        output.seek(0)
                        values = [os.fsdecode(record[9:]) for record in output.read().split(b'\0')
                                  if record.startswith(b'worktree ')]
                        paths = tuple(dict.fromkeys(values[:_WORKTREE_LIMIT]))
            except (OSError, subprocess.SubprocessError):
                pass
            if len(_WORKTREE_CACHE) >= 128:
                oldest = min(_WORKTREE_CACHE, key=lambda candidate: _WORKTREE_CACHE[candidate][0])
                del _WORKTREE_CACHE[oldest]
            _WORKTREE_CACHE[key] = (time.monotonic(), stamp, paths)
            return paths
    except (OSError, ValueError, UnicodeError):
        return ()


def workspace_id(workspace):
    identity = os.path.normcase(str(workspace['state'].resolve())) + '\n' + os.path.normcase(str(workspace['source'].resolve()))
    return hashlib.sha256(identity.encode('utf-8')).hexdigest()[:24]


def _small_text(path, limit=4096):
    try:
        if path.is_file() and path.stat().st_size <= limit:
            return path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        pass
    return None


def checkout_identity(source):
    """Classify a source as the main checkout or a linked worktree, with its branch.

    Reads only .git and HEAD; never runs git, so a slow or locked repository
    cannot stall the status endpoint.
    """
    dot_git = Path(source) / ".git"
    if dot_git.is_dir():
        kind, git_dir = "checkout", dot_git
    else:
        pointer = _small_text(dot_git)
        if not pointer or not pointer.startswith("gitdir:"):
            return {"checkout_kind": "other", "branch": None}
        git_dir = Path(pointer[len("gitdir:"):].strip())
        if not git_dir.is_absolute():
            git_dir = (Path(source) / git_dir).resolve()
        # A linked worktree's gitdir lives under <repo>/.git/worktrees/<name>;
        # a submodule's under <repo>/.git/modules and counts as a checkout.
        kind = "worktree" if git_dir.parent.name == "worktrees" else "checkout"
    head = _small_text(git_dir / "HEAD") or ""
    branch = head[len("ref: refs/heads/"):] if head.startswith("ref: refs/heads/") else (
        "detached " + head[:10] if head else None)
    return {"checkout_kind": kind, "branch": branch}


def workspace_label(workspace, workspaces):
    """A short name that tells workspaces apart.

    Tool-made worktrees often end in the repository folder name (for example
    ...\\worktrees\\<task>\\VDX-infra), so a shared leaf name falls back to the
    nearest distinguishing parent folder.
    """
    source = Path(workspace["source"])
    leaf = source.name
    if sum(1 for ws in workspaces if Path(ws["source"]).name.lower() == leaf.lower()) < 2 or \
            workspace.get("checkout_kind") == "checkout" and workspace.get("is_primary"):
        return leaf
    parts = source.parts[:-1]
    for part in reversed(parts):
        if part.lower() not in ("worktrees", ".worktrees", "git", "source") and not part.endswith(("\\", "/")):
            return f"{part}/{leaf}"
    return str(source)


def last_activity(store):
    """Most recent sign that someone uses this workspace: a job queued, started or
    finished, a snapshot captured, or an edit lease taken. Worker heartbeats do not count."""
    try:
        with store.connect() as db:
            values = [db.execute("SELECT MAX(MAX(COALESCE(created,0),COALESCE(started,0),COALESCE(finished,0))) FROM jobs").fetchone()[0],
                      db.execute("SELECT MAX(created) FROM snapshots").fetchone()[0],
                      db.execute("SELECT MAX(created) FROM writers").fetchone()[0]]
    except (sqlite3.Error, OSError):
        return None
    values = [v for v in values if isinstance(v, (int, float)) and v > 0]
    return max(values) if values else None


def task_estimate(store, spec):
    """Typical duration, peak memory and recent health of this job's task set."""
    try:
        return store.task_stats(spec)
    except (sqlite3.Error, OSError, ValueError, TypeError):
        return None


def merged_task_stats(workspaces):
    """History per task key across workspaces; the workspace with the most runs wins a key."""
    merged = {}
    for ws in workspaces:
        try:
            stats = ws["store"].task_stats()
        except (sqlite3.Error, OSError, ValueError):
            continue
        for key, value in stats.items():
            if value and (key not in merged or value["runs"] > merged[key]["runs"]):
                merged[key] = {**value, "task_key": key, "workspace": ws["name"]}
    return sorted(merged.values(), key=lambda item: -(item.get("last_finished") or 0))


def subscriber_view(row):
    """Who asked for a build, whether a `wait` is following it, and whether its result was read."""
    from cli import pid_alive
    heartbeat, pid = row.get("waiter_heartbeat"), row.get("waiter_pid")
    return {"id": row["id"], "agent": row.get("agent"), "job_id": row["job_id"], "active": row.get("active"),
            "tracked": "collected" in row, "collected": row.get("collected"),
            "waiter_live": bool(pid and heartbeat and time.time() - heartbeat <= 30 and pid_alive(pid))}


def start_workspace_worker(workspace):
    """Start a workspace's worker with that checkout's own coordinator code.

    Another worktree may carry a different coordinator version, so its worker
    is launched through its own cli.py rather than this dashboard's modules.
    """
    if workspace["is_primary"]:
        from cli import start
        if start(workspace["store"], workspace["source"]) != 0:
            raise RuntimeError("Worker did not start; inspect coordinator status")
        return
    cli = Path(workspace["source"]) / "tooling" / "build-coordinator" / "cli.py"
    if not cli.is_file():
        raise RuntimeError(f"Coordinator code is missing in {workspace['source']}")
    kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    completed = subprocess.run([sys.executable, str(cli), "--source", str(workspace["source"]),
                                "--state", str(workspace["state"]), "start"],
                               cwd=workspace["source"], stdin=subprocess.DEVNULL, capture_output=True,
                               text=True, timeout=30, **kwargs)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip().splitlines()
        raise RuntimeError(detail[-1] if detail else "Worker did not start; inspect coordinator status")


def queue_reorder_capability(worker_data, live, workspace):
    version = worker_data.get('queue_order_version')
    if type(version) is int and version == 1:
        return True, None
    if workspace['is_primary'] and not live:
        from cli import lock_held
        if not lock_held(workspace['state'] / 'worker.lock'):
            return True, None
    return False, ('Start or restart this workspace\'s worker with the updated coordinator '
                   'to enable queue reordering.')


def snapshot_provenance(store, snapshot_id, source):
    """Small capture-time metadata, including for source that has expired.

    Older snapshots do not carry branches. Do not inspect today's checkout or
    repeatedly parse a multi-megabyte historical manifest to invent one.
    """
    with store.connect() as db:
        columns = {row['name'] for row in db.execute('PRAGMA table_info(snapshots)')}
        expiry = 'expired_at' if 'expired_at' in columns else 'NULL AS expired_at'
        row = db.execute(f'SELECT data,{expiry} FROM snapshots WHERE id=?', (snapshot_id,)).fetchone()
    data = json.loads(row['data']) if row else {}
    return {'snapshot': snapshot_id, 'source': data.get('source') or str(source),
            'repositories': data.get('repositories', []),
            'source_expired': bool(row and row['expired_at'] is not None)}


def compress_path(path_str: str, max_segments: int = 3) -> str:
    """Compress a filesystem path to .../<last_N_segments> for compact display."""
    if not path_str or path_str == "(unknown)":
        return "(unknown)"
    try:
        p = PureWindowsPath(path_str) if "\\" in path_str else PurePosixPath(path_str)
        parts = [part for part in p.parts if part and part not in ("/", "\\")]
        if len(parts) <= max_segments:
            return str(p)
        return ".../" + "/".join(parts[-max_segments:])
    except Exception:
        return path_str


def extract_meaningful_command(command: str) -> str:
    """Strip verbose JVM parameters to reveal the main class/JAR and arguments."""
    if not command:
        return "(unavailable)"
    try:
        import shlex
        tokens = shlex.split(command)
    except Exception:
        tokens = command.split()

    if not tokens:
        return command

    exe = tokens[0].lower()
    if "java" in exe:
        idx = 1
        while idx < len(tokens):
            tok = tokens[idx]
            if tok == "-jar" and idx + 1 < len(tokens):
                jar_name = Path(tokens[idx + 1]).name
                rest_str = " ".join(tokens[idx + 2:])
                return f"-jar {jar_name} {rest_str}".strip()
            elif tok in ("-cp", "-classpath", "--class-path", "-javaagent"):
                idx += 2
            elif tok.startswith(("-D", "-X", "-XX:", "--add-opens", "--add-exports", "--module-path", "--patch-module", "-agentlib", "-agentpath", "-javaagent:")):
                idx += 1
            elif tok.startswith("-"):
                idx += 1
            else:
                return " ".join(tokens[idx:])
        return " ".join(tokens[1:])
    else:
        tokens[0] = Path(tokens[0]).name
        return " ".join(tokens)


def parse_candidate_details(pid: int, name: str, command: str) -> dict:
    """Extract human-readable target (class/JAR), working directory, and extra task info."""
    cwd = ""
    # Try reading /proc/<pid>/cwd on Linux
    try:
        cwd_link = Path(f"/proc/{pid}/cwd")
        if cwd_link.is_symlink():
            cwd = str(cwd_link.resolve())
    except Exception:
        pass

    # Fallbacks for cwd from command line arguments
    if not cwd or cwd in ("/", "/root", str(Path.home())):
        dir_match = re.search(r'(?:-Duser\.dir=|--project-dir\s+|=)(/[^\s"\']+)', command)
        if dir_match:
            cwd = dir_match.group(1)

    if not cwd:
        from gradle_registry import _command_arguments
        args = _command_arguments(command, windows=bool(re.search(r'[A-Za-z]:[\\/]', command)))
        for index, arg in enumerate(args):
            if arg.startswith(('-Duser.dir=', '--project-dir=')):
                cwd = arg.split('=', 1)[1]
                break
            if arg in ('-p', '--project-dir') and index + 1 < len(args):
                cwd = args[index + 1]
                break

    compressed_cwd = compress_path(cwd, max_segments=3) if cwd else "(unknown)"

    # Identify target / main class / JAR
    target = ""
    jar_match = re.search(r'-jar\s+([^\s]+)', command)
    if jar_match:
        jar_name = Path(jar_match.group(1).strip('"\'')).name
        target = f"JAR: {jar_name}"
    elif "GradleDaemon" in command or "org.gradle.launcher.daemon" in command:
        target = "GradleDaemon"
    elif "GradleWrapperMain" in command or "gradle-wrapper.jar" in command:
        target = "GradleWrapperMain"
    elif "KotlinCompileDaemon" in command:
        target = "KotlinCompileDaemon"
    elif "com.intellij" in command or "idea" in command.lower():
        target = "IntelliJ IDEA"
    else:
        # Check for class after classpath
        cp_match = re.search(r'(?:-cp|-classpath|--class-path)\s+(?:"[^"]+"|\'[^\']+\'|[^\s]+)\s+([a-zA-Z0-9_$.]+)', command)
        if cp_match:
            cls = cp_match.group(1)
            target = cls.split(".")[-1]
        else:
            # Check for any Java FQCN
            fqcns = re.findall(r'\b([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*){2,})\b', command)
            clean_fqcns = [c for c in fqcns if not c.startswith(('java.', 'javax.', 'jdk.', 'sun.', 'org.xml', 'org.apache'))]
            if clean_fqcns:
                target = clean_fqcns[-1].split(".")[-1]

    if not target:
        target = name

    # Extract extra details like Gradle tasks or arguments
    extra = ""
    task_matches = re.findall(r'(?:\s|^)(:[a-zA-Z0-9_:-]+|(?:compile|test|jvmTest|check|build|assemble|publish|clean)[a-zA-Z0-9_:-]*)(?=\s|$)', command)
    if task_matches:
        extra = " ".join(task_matches[:3])
    elif "--status" in command:
        extra = "--status"
    elif "--stop" in command:
        extra = "--stop"

    return {
        "target": target,
        "cwd": cwd,
        "compressed_cwd": compressed_cwd,
        "extra": extra,
        "command_preview": extract_meaningful_command(command),
        "full_command": command,
    }


def discover_workspaces(source: Path, primary_store: Store, *, state_roots=None) -> list[dict]:
    """Discover registered queues, preferring each checkout's current marker.

    Old state directories often retain the same source after a migration. They
    must not hide its current queue merely because their name sorts first.
    """
    primary_source = _discovery_path(source)
    workspaces = {str(primary_source): {
        "name": primary_source.name, "source": primary_source,
        "state": primary_store.state, "store": primary_store, "is_primary": True,
    }}
    seen_states = {_discovery_path(primary_store.state)}
    checking_states = set()

    def registration(src):
        marker = _discovery_path(src / '.vdx-build-required.json')
        if not marker.is_file():
            return None
        if marker.stat().st_size > 16384:
            raise ValueError('Coordinator marker is too large')
        value = json.loads(marker.read_text(encoding='utf-8'))
        if _discovery_path(value['source']) != src:
            raise ValueError('Coordinator marker belongs to another worktree')
        return _discovery_path(value['state'])

    def add_state(state, expected_source=None):
        try:
            state = _discovery_path(state)
            if (state in seen_states or state in checking_states or
                    not _discovery_path(state / 'queue.sqlite3').is_file()):
                return
            checking_states.add(state)
            store = Store.open_existing(state)
            recorded_source = store.metadata("source")
            if not recorded_source:
                return
            src = _discovery_path(recorded_source)
            if (not src.is_dir() or str(src) in workspaces or
                    expected_source is not None and src != expected_source):
                return
            registered_state = registration(src)
            if registered_state is not None and registered_state != state:
                add_state(registered_state, expected_source=src)
                return
            workspaces[str(src)] = {"name": src.name, "source": src, "state": state,
                                    "store": store, "is_primary": False}
            seen_states.add(state)
        except (OSError, ValueError, KeyError, TypeError, RuntimeError, sqlite3.Error):
            return
        finally:
            checking_states.discard(state)

    def add_marker(path):
        try:
            src = _discovery_path(path)
            if not src.is_dir():
                return
            registered = registration(src)
            if registered is not None:
                add_state(registered, expected_source=src)
        except (OSError, ValueError, KeyError, TypeError):
            pass
    tmp_state = Path('/tmp/vdx-build-state')
    if tmp_state.is_dir():
        add_state(tmp_state)

    try:
        for sibling in source.parent.iterdir():
            add_marker(sibling)
    except OSError:
        pass
    if state_roots is None:
        state_roots = (primary_store.state.parent, source.parent / ".vdx-build", Path.home() / ".vdx-build")
    for base in state_roots:
        try:
            for state in _discovery_path(base).iterdir():
                if state.is_dir():
                    add_state(state)
        except (OSError, ValueError):
            pass
    add_state(Path("/tmp/vdx-build-state"))
    # The machine-wide registry covers queues whose state roots and source
    # checkouts are outside sibling scans. add_state still validates queue
    # metadata and honors the checkout's current marker before displaying it.
    try:
        registry_file = registry_path()
        if registry_file.is_file():
            with readonly_connect(registry_file) as db:
                registered = db.execute('SELECT source,state FROM workspaces ORDER BY source,state').fetchall()
            for entry in registered:
                add_state(entry["state"], expected_source=_discovery_path(entry["source"]))
    except (OSError, ValueError, sqlite3.Error):
        pass
    # Sources learned from sibling state roots can lead back to the primary
    # repository and then to worktrees whose queues live in unrelated directories.
    # Git supplies that finite registry; do not recursively scan checkout trees.
    seen_common, inspected_sources = set(), set()
    deadline = time.monotonic() + 3.0
    while len(inspected_sources) < 64:
        pending = [ws['source'] for ws in workspaces.values() if ws['source'] not in inspected_sources]
        if not pending:
            break
        for src in pending[:64 - len(inspected_sources)]:
            inspected_sources.add(src)
            for worktree in _registered_worktrees(src, seen_common, deadline):
                add_marker(worktree)
    spaces = list(workspaces.values())
    for ws in spaces:
        ws.update(checkout_identity(ws["source"]))
    for ws in spaces:
        ws["name"] = workspace_label(ws, spaces)
    return spaces


class DashboardHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Silence routine request logging to keep console clean
        pass

    @property
    def store(self) -> Store:
        return self.server.store

    @property
    def source(self) -> Path:
        return self.server.source

    def reply_json(self, status, payload):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps(payload).encode("utf-8"))

    def resolve_workspace(self, identity):
        if not isinstance(identity, str) or not re.fullmatch(r'[a-f0-9]{24}', identity):
            raise ValueError('A valid workspace identity is required; filesystem paths are not accepted')
        primary_source = _discovery_path(self.source)
        primary_state = _discovery_path(self.store.state)
        _discovery_path(primary_state / 'queue.sqlite3')
        primary = {'name': primary_source.name, 'source': primary_source,
                   'state': primary_state, 'store': self.store, 'is_primary': True}
        if workspace_id(primary) == identity:
            return primary
        for ws in discover_workspaces(self.source, self.store):
            if workspace_id(ws) == identity:
                return ws
        raise LookupError('Workspace is no longer registered')

    def maintenance(self, workspace):
        from maintenance import MaintenanceService
        # The server owns lifecycle; HTTP handlers only enqueue background work.
        with self.server.maintenance_lock:
            key = workspace_id(workspace)
            if key not in self.server.maintenance_services:
                service = MaintenanceService(workspace['store'])
                self.server.maintenance_services[key] = service
                service.start()
            return self.server.maintenance_services[key]

    def workspace_summary(self, ws):
        return {'id': workspace_id(ws), 'name': ws['name'], 'source': str(ws['source'])}

    def console_GET(self, parsed):
        from build_logs import CursorError
        try:
            values = parse_qs(parsed.query, keep_blank_values=True, max_num_fields=12)
            if any(len(value) != 1 for value in values.values()):
                raise ValueError('Duplicate query parameters are not supported')
            query = {key: value[0] for key, value in values.items()}
            allowed = {'workspace', 'job', 'cursor', 'before', 'limit', 'tail'} if parsed.path == '/api/console' else {'workspace', 'job'}
            if set(query) - allowed:
                raise ValueError('Unknown query parameters')
            ws = self.resolve_workspace(query.get('workspace'))
            if parsed.path == '/api/console/preview':
                from build_logs import error_preview
                self.reply_json(200, error_preview(ws['store'], query.get('job')))
                return
            if parsed.path == '/api/console/download':
                from build_logs import download
                started = False
                try:
                    with download(ws['store'], query.get('job')) as (meta, chunks):
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/plain; charset=utf-8')
                        self.send_header('Content-Disposition', f'attachment; filename="{meta["job_id"]}.log"')
                        self.send_header('Content-Length', str(meta['size']))
                        self.send_header('Cache-Control', 'no-store')
                        started = True
                        self.end_headers()
                        for chunk in chunks:
                            self.wfile.write(chunk)
                except Exception:
                    if not started:
                        raise
                    # Once headers are sent, an error response would become log
                    # bytes. Closing with a short body makes corruption visible
                    # to clients while the context releases reader protection.
                    self.close_connection = True
                return
            from build_logs import read_chunk
            if query.get('tail', 'false') not in ('true', 'false'):
                raise ValueError('tail must be true or false')
            payload = read_chunk(ws['store'], query.get('job'), cursor=query.get('cursor'),
                                 before=query.get('before'), limit=int(query.get('limit', '65536')),
                                 tail=query.get('tail') == 'true')
            payload['workspace'] = self.workspace_summary(ws)
            payload['provenance'] = snapshot_provenance(ws['store'], payload.get('snapshot'), ws['source'])
            self.reply_json(200, payload)
        except (BrokenPipeError, ConnectionResetError):
            return
        except CursorError as error:
            self.reply_json(409, {'status': 'error', 'error': str(error), 'reset': True})
        except (BlockingIOError, RuntimeError) as error:
            self.reply_json(423, {'status': 'error', 'error': str(error), 'retry': True})
        except LookupError as error:
            self.reply_json(404, {'status': 'error', 'error': str(error)})
        except (ValueError, TypeError) as error:
            self.reply_json(400, {'status': 'error', 'error': str(error)})
        except OSError as error:
            self.reply_json(503, {'status': 'error', 'error': str(error), 'retry': True})

    def storage_GET(self, parsed):
        try:
            values = parse_qs(parsed.query, max_num_fields=2)
            if set(values) != {'workspace'} or len(values['workspace']) != 1:
                raise ValueError('A single workspace identity is required')
            ws = self.resolve_workspace(values['workspace'][0])
            service = self.maintenance(ws)
            current = service.status()
            if parsed.path == '/api/cleanup':
                self.reply_json(200, current)
                return
            report = current.get('report') or {}
            self.reply_json(200, {'workspace': self.workspace_summary(ws), 'usage': report.get('usage', {}),
                                  'cleanup': current, 'policy': ws['store'].all_config()})
        except LookupError as error:
            self.reply_json(404, {'status': 'error', 'error': str(error)})
        except (ValueError, TypeError) as error:
            self.reply_json(400, {'status': 'error', 'error': str(error)})

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path in ('/api/console', '/api/console/download', '/api/console/preview'):
            return self.console_GET(parsed)
        if parsed.path in ('/api/storage', '/api/cleanup'):
            return self.storage_GET(parsed)
        if self.path == "/" or self.path == "/index.html":
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_PATH.read_bytes())
            return
        if parsed.path == "/api/task-stats":
            self.reply_json(200, {"tasks": merged_task_stats(discover_workspaces(self.source, self.store))})
            return

        if self.path == "/api/status":
            workspaces = discover_workspaces(self.source, self.store)
            cfg = self.store.all_config()
            mem = get_memory_status()
            include_swap = cfg.get("include_swap", True)
            swap_weight = float(cfg.get("swap_weight", 0.75))
            effective = available_memory_gb(include_swap=include_swap, swap_weight=swap_weight)

            # Aggregate jobs and writers across all discovered workspaces
            all_jobs = []
            all_writers = []
            recent_jobs = []
            workspace_errors = []

            for ws in workspaces:
                st = ws["store"]
                ws_name = ws["name"]
                ws_id = workspace_id(ws)
                ws_src = str(ws["source"])
                ws_state = str(ws["state"])
                ws.update(worker_live=False, worker_pid=None, drain=False,
                          config_matches=False, admission_blocked={}, queue_reorder_supported=False,
                          queue_reorder_reason='Workspace state is unavailable; retry before reordering.')

                try:
                    with st.connect() as db:
                        # SELECT * tolerates queues whose worker predates collection tracking.
                        subscriber_rows = db.execute(
                            "SELECT * FROM requests WHERE active=1 AND job_id IN (SELECT id FROM jobs WHERE "
                            "status IN ('queued','running','cancelling') OR finished>?)", (time.time() - 86400,)).fetchall()
                        writers = [dict(r) for r in db.execute("SELECT * FROM writers ORDER BY created")]
                    subscribers = [subscriber_view(dict(r)) for r in subscriber_rows]
                    for w in writers:
                        all_writers.append({
                            **w,
                            "workspace": ws_name,
                            "workspace_source": ws_src,
                            "workspace_state": ws_state,
                        })

                    ws_blocked = st.metadata("blocked") or {}
                    from cli import worker_live
                    worker_data = st.metadata("worker")
                    worker_data = {} if worker_data is None else worker_data
                    if not isinstance(worker_data, dict):
                        raise ValueError('Invalid worker metadata')
                    worker_pid = worker_data.get('pid')
                    if worker_pid is not None and (type(worker_pid) is not int or worker_pid <= 0):
                        raise ValueError('Invalid worker PID')
                    ws["worker_live"] = worker_live(st, worker_data=worker_data)
                    ws["worker_pid"] = worker_pid if ws["worker_live"] else None
                    ws['queue_reorder_supported'], ws['queue_reorder_reason'] = queue_reorder_capability(
                        worker_data, ws['worker_live'], ws)
                    ws["drain"] = bool(st.metadata("drain"))
                    ws["last_activity"] = last_activity(st)
                    ws["config_matches"] = st.all_config() == cfg
                    if ws['drain']:
                        ws_blocked = {'reason': 'Admission paused'}
                    elif not ws['worker_live']:
                        ws_blocked = {'reason': 'Worker offline; admission is paused'}
                    elif ws_blocked.get('reason') == 'Discarded queued job with no active subscribers':
                        ws_blocked = {}
                    ws['admission_blocked'] = ws_blocked
                    with st.connect() as db:
                        recent_rows = db.execute("SELECT id,status,created,started,finished,phase,lane,spec FROM jobs WHERE status IN ('passed','failed','interrupted','superseded') ORDER BY finished DESC LIMIT 8").fetchall()
                    # Enrichment can inspect processes or open another queue
                    # connection. Release this reader before a pending writer
                    # blocks those nested reads and waits for this same cursor.
                    for row in recent_rows:
                        recent = dict(row)
                        spec = json.loads(recent.pop('spec'))
                        recent_jobs.append({**recent, "estimate": task_estimate(st, spec),
                                            "subscribers": [r for r in subscribers if r["job_id"] == recent["id"]],
                                            "workspace": ws_name, "workspace_id": ws_id, "workspace_source": ws_src,
                                            "provenance": snapshot_provenance(st, spec.get('snapshot'), ws['source']),
                                            "spec": {k: spec.get(k) for k in ('root', 'tasks', 'memory_gb', 'memory_auto')},
                                            "build_root": str(st.state / 'lanes' / str(recent['lane']) / 'source' / spec.get('root', '.')) if recent.get('lane') is not None else None,
                                            "root": spec.get('root', '.')})
                    for j in st.scheduling_jobs(("queued", "running", "cancelling")):
                        tier = classify_task_tier(j["spec"])
                        job_dict = {
                            **{k: v for k, v in j.items() if k != "spec"},
                            "spec": {k: j["spec"].get(k) for k in ("root", "tasks", "memory_gb", "memory_auto")},
                            "workspace": ws_name,
                            "workspace_id": ws_id,
                            "workspace_source": ws_src,
                            "workspace_state": ws_state,
                            "queue_reorder_supported": ws['queue_reorder_supported'],
                            "queue_reorder_reason": ws['queue_reorder_reason'],
                            "tier": tier,
                            "provenance": snapshot_provenance(st, j['spec'].get('snapshot'), ws['source']),
                            "build_root": str(st.state / 'lanes' / str(j['lane']) / 'source' / j['spec'].get('root', '.')) if j.get('lane') is not None else None,
                            "blocked_reason": ws_blocked.get("reason") if j["status"] == "queued" else None,
                            "subscribers": [r for r in subscribers if r["job_id"] == j["id"] and r["active"]],
                            "estimate": task_estimate(st, j["spec"]),
                            "peak_gb": j.get("peak_gb"),
                        }
                        all_jobs.append(job_dict)
                except Exception as error:
                    # A partially gathered workspace cannot establish process
                    # ownership; keep its error visible and its PIDs unexempted.
                    ws.update(worker_live=False, worker_pid=None, queue_reorder_supported=False,
                              queue_reorder_reason='Workspace state is unavailable; retry before reordering.')
                    for job in all_jobs:
                        if job['workspace_id'] == ws_id:
                            job.update(queue_reorder_supported=False, queue_reorder_reason=ws['queue_reorder_reason'])
                    workspace_errors.append({"source": ws_src, "error": str(error)})

            primary_queued = any(j["status"] == "queued" and j["workspace_source"] == str(self.source) for j in all_jobs)
            primary_ws = next((ws for ws in workspaces if ws['is_primary']), {})
            try:
                priorities = Registry().priorities()
            except (sqlite3.Error, OSError, ValueError):
                priorities = {}
            blocked = primary_ws.get('admission_blocked', {}) if primary_queued else {}
            if blocked.get("reason") == "Discarded queued job with no active subscribers":
                blocked = {}

            if not blocked.get("reason"):
                for ws in workspaces:
                    try:
                        # Only workspaces with queued jobs can be blocked
                        has_queued = any(j["status"] == "queued" for j in all_jobs if j.get("workspace_source") == str(ws["source"]))
                        if has_queued:
                            b = ws.get('admission_blocked', {})
                            r = b.get("reason")
                            if r and r != "Discarded queued job with no active subscribers":
                                blocked = {**b, "reason": f"[{ws['name']}] {r}"}
                                break
                    except Exception:
                        pass

            candidates = []
            inspection_error = None
            temp_pids = self.store.temp_whitelist_pids()
            owned_jobs = {}
            owned_workspaces = {}
            for ws in workspaces:
                worker_pid = ws.get("worker_pid")
                if ws.get("worker_live") and worker_pid:
                    owned_workspaces[worker_pid] = ws
            try:
                raw_candidates = _inspect_processes()
                # A persisted job PID can be reused after its process exits.
                # Ownership requires ancestry under a currently live worker.
                changed = True
                while changed:
                    changed = False
                    for row in raw_candidates:
                        if row["pid"] not in owned_workspaces and row["parent"] in owned_workspaces:
                            owned_workspaces[row["pid"]] = owned_workspaces[row["parent"]]
                            changed = True
                for job in all_jobs:
                    owner_ws = owned_workspaces.get(job.get("pid"))
                    if (job["status"] in ("running", "cancelling") and owner_ws
                            and str(owner_ws["source"]) == job["workspace_source"]):
                        owned_jobs[job["pid"]] = job
                changed = True
                while changed:
                    changed = False
                    for row in raw_candidates:
                        if row["pid"] not in owned_jobs and row["parent"] in owned_jobs:
                            owned_jobs[row["pid"]] = owned_jobs[row["parent"]]
                            changed = True
                blocking_by_pid = {row["pid"]: row for row in build_activity(
                    owned_pids=list(owned_workspaces),
                    whitelist=list(cfg.get("whitelist_patterns", [])) + temp_pids,
                    rows=raw_candidates)}
                for row in raw_candidates:
                    if not _candidate(row["name"]):
                        continue
                    details = parse_candidate_details(row["pid"], row["name"], row.get("command", ""))
                    classified = blocking_by_pid.get(row["pid"], {})
                    owner = owned_jobs.get(row["pid"])
                    owner_ws = owned_workspaces.get(row["pid"])
                    blocking = bool(classified.get("blocking"))
                    candidates.append({
                        "pid": row["pid"], "parent": row["parent"], "name": row["name"],
                        **details, "blocking": blocking,
                        "kind": classified.get("kind"), "state": classified.get("state"),
                        "idle_seconds": classified.get("idle_seconds"),
                        "is_temp_whitelisted": row["pid"] in temp_pids,
                        "owner": {"job": owner["id"] if owner else None, "workspace": owner_ws["name"],
                                  "source": str(owner_ws["source"])} if owner_ws else None,
                        "reason": ("Managed by " + owner_ws["name"] if owner_ws else
                                   "Gradle state unavailable; admission waits" if blocking and classified.get("state") == "unknown" else
                                   "External build is active" if blocking else
                                   "Idle daemon" if classified.get("state") == "Idle" else
                                   "Exempt or unrelated process"),
                    })
            except Exception as error:
                inspection_error = str(error)

            worker_live = primary_ws.get('worker_live', False)

            # Dynamic slots evaluation
            active_jobs = [j for j in all_jobs if j.get("status") in ("running", "cancelling")]
            pending_jobs = [j for j in all_jobs if j.get("status") == "queued"]
            dyn_slots = compute_dynamic_slots(cfg, active_jobs=active_jobs, pending_jobs=pending_jobs, physical_memory_gb=mem["ram_available_gb"])

            payload = {
                "source": str(self.source),
                "workspaces": [{"id": workspace_id(ws), "workspace_id": workspace_id(ws), "name": ws["name"], "priority": priorities.get(str(Path(ws["state"]).resolve()), "normal"), "checkout_kind": ws.get("checkout_kind"), "branch": ws.get("branch"), "source": str(ws["source"]), "state": str(ws["state"]), "is_primary": ws["is_primary"], "worker_live": ws.get("worker_live", False), "drain": ws.get("drain", False), "last_activity": ws.get("last_activity"), "config_matches": ws.get("config_matches", False), "queue_reorder_supported": ws.get("queue_reorder_supported", False), "queue_reorder_reason": ws.get("queue_reorder_reason")} for ws in workspaces],
                "worker_live": worker_live,
                "drain": primary_ws.get('drain', False),
                "workspace_admission": True,
                "observed_at": time.time(),
                "workspace_errors": workspace_errors,
                "inspection_error": inspection_error,
                "recent_jobs": sorted(recent_jobs, key=lambda j: j.get("finished") or 0, reverse=True)[:12],
                "blocked": blocked,
                "memory": mem,
                "effective_memory_gb": effective,
                "config": cfg,
                "dynamic_slots": dyn_slots,
                "global": Registry().global_view(),
                "temp_whitelist_pids": temp_pids,
                "jobs": all_jobs,
                "writers": all_writers,
                "candidates": candidates
            }

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(payload).encode("utf-8"))
            return

        self.send_response(HTTPStatus.NOT_FOUND)
        self.end_headers()

    def do_POST(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            if not 0 <= content_length <= 65536:
                raise ValueError("Request is too large")
            body = self.rfile.read(content_length)
            data = json.loads(body.decode("utf-8")) if body else {}
            if not isinstance(data, dict):
                raise ValueError("Expected a JSON object")
        except (ValueError, UnicodeError) as error:
            self.reply_json(400, {"status": "error", "error": str(error)})
            return
        if self.path == '/api/queue/move':
            try:
                if set(data) != {'workspace', 'job', 'direction'}:
                    raise ValueError('Expected workspace, job and direction')
                ws = self.resolve_workspace(data['workspace'])
                from cli import worker_live
                worker_data = ws['store'].metadata('worker')
                worker_data = {} if worker_data is None else worker_data
                if not isinstance(worker_data, dict):
                    raise RuntimeError('Worker capabilities are unavailable; restart with the updated coordinator')
                worker_pid = worker_data.get('pid')
                if worker_pid is not None and (type(worker_pid) is not int or worker_pid <= 0):
                    raise RuntimeError('Worker capabilities are unavailable; restart with the updated coordinator')
                supported, reason = queue_reorder_capability(
                    worker_data, worker_live(ws['store'], worker_data=worker_data), ws)
                if not supported:
                    raise RuntimeError(reason)
                self.reply_json(200, ws['store'].move_queued(data['job'], data['direction']))
            except LookupError as error:
                self.reply_json(404, {'status': 'error', 'error': str(error)})
            except (ValueError, TypeError) as error:
                self.reply_json(400, {'status': 'error', 'error': str(error)})
            except (OSError, RuntimeError, sqlite3.OperationalError) as error:
                self.reply_json(409, {'status': 'error', 'error': str(error), 'retry': True})
            return

        workspaces = discover_workspaces(self.source, self.store)

        if self.path in ('/api/console/pin', '/api/cleanup'):
            try:
                allowed = {'workspace', 'job', 'pinned'} if self.path == '/api/console/pin' else {'workspace', 'apply'}
                if set(data) - allowed:
                    raise ValueError('Unknown request fields')
                ws = self.resolve_workspace(data.get('workspace'))
                if self.path == '/api/console/pin':
                    from build_logs import set_pinned
                    if type(data.get('pinned')) is not bool:
                        raise ValueError('pinned must be boolean')
                    self.reply_json(200, set_pinned(ws['store'], data.get('job'), data['pinned']))
                else:
                    if type(data.get('apply', False)) is not bool:
                        raise ValueError('apply must be boolean')
                    self.reply_json(202, self.maintenance(ws).request(apply=data.get('apply', False)))
            except LookupError as error:
                self.reply_json(404, {'status': 'error', 'error': str(error)})
            except (ValueError, TypeError) as error:
                self.reply_json(400, {'status': 'error', 'error': str(error)})
            except (OSError, RuntimeError) as error:
                self.reply_json(409, {'status': 'error', 'error': str(error), 'retry': True})
            return

        if self.path == "/api/config":
            try:
                updates = validate_config(data)
            except ValueError as error:
                self.reply_json(400, {"status": "error", "error": str(error)})
                return
            saved, failed = [], []
            for ws in workspaces:
                try:
                    ws["store"].update_config(updates)
                    saved.append(str(ws["source"]))
                except Exception as error:
                    failed.append({"source": str(ws["source"]), "error": str(error)})
            self.reply_json(500 if failed else 200, {
                "status": "partial" if failed and saved else "error" if failed else "ok",
                "config": self.store.all_config(), "saved": saved, "failed": failed,
                "error": "Settings could not be saved to every workspace" if failed else None,
            })
            return

        if self.path == "/api/reap":
            # Manual counterpart to the worker's automatic pass. Same eligibility rules:
            # only daemons Gradle reports idle, never a coordinator-owned process.
            owned = []
            for ws in workspaces:
                try:
                    owned += [j["pid"] for j in ws["store"].job_headers(("running",)) if j.get("pid")]
                except Exception:
                    pass
            try:
                cfg = self.store.all_config()
                reaped = reap_idle_gradle_daemons(
                    owned,
                    whitelist=list(cfg.get("whitelist_patterns", [])) + self.store.temp_whitelist_pids(),
                    min_idle_seconds=float(data.get("min_idle_seconds", cfg.get("reap_min_idle_seconds", 120))))
                for daemon in reaped:
                    try:
                        self.store.record_event("daemon-reaped", **daemon)
                    except Exception:
                        pass
                body = json.dumps({"status": "ok", "reaped": reaped}).encode()
            except RuntimeError as error:
                body = json.dumps({"status": "error", "error": str(error), "reaped": []}).encode()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/api/whitelist/temp_pid":
            pid = data.get("pid")
            action = data.get("action", "add")
            if pid is not None:
                for ws in workspaces:
                    try:
                        if action == "add":
                            ws["store"].add_temp_whitelist_pid(int(pid))
                        elif action == "remove":
                            ws["store"].remove_temp_whitelist_pid(int(pid))
                    except Exception:
                        pass
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        if self.path == "/api/whitelist/add":
            pattern = str(data.get("pattern", "")).strip().lower()
            if pattern:
                # If numeric PID was entered into pattern box, treat as temporary PID exemption
                if pattern.isdigit():
                    for ws in workspaces:
                        try:
                            ws["store"].add_temp_whitelist_pid(int(pattern))
                        except Exception:
                            pass
                else:
                    if "*" not in pattern and "?" not in pattern:
                        pattern = f"*{pattern}*"
                    for ws in workspaces:
                        try:
                            target_store = ws["store"]
                            cfg = target_store.all_config()
                            pats = list(cfg.get("whitelist_patterns", []))
                            if pattern not in pats:
                                pats.append(pattern)
                                target_store.config("whitelist_patterns", pats)
                        except Exception:
                            pass
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        if self.path == "/api/whitelist/remove":
            pattern = str(data.get("pattern", "")).strip()
            if pattern:
                for ws in workspaces:
                    try:
                        target_store = ws["store"]
                        cfg = target_store.all_config()
                        pats = [p for p in cfg.get("whitelist_patterns", []) if str(p) != pattern]
                        target_store.config("whitelist_patterns", pats)
                    except Exception:
                        pass
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        if self.path == "/api/action":
            action = data.get("action")
            failures = []
            if action in ("drain", "resume"):
                # Admission is per queue: "all" covers every discovered workspace,
                # a workspace_id one of them, and neither this checkout only.
                if data.get("scope") == "all":
                    # Resuming everything must not wake long-idle worktrees:
                    # only paused queues are resumed.
                    targets = [ws for ws in workspaces
                               if action == "drain" or ws["store"].metadata("drain")]
                elif data.get("workspace_id"):
                    targets = [ws for ws in workspaces if workspace_id(ws) == data.get("workspace_id")]
                    if not targets:
                        self.reply_json(404, {"status": "error", "error": "Unknown workspace"})
                        return
                else:
                    targets = [ws for ws in workspaces if ws["is_primary"]]
                for ws in targets:
                    try:
                        if action == "drain":
                            ws["store"].metadata("drain", True)
                        else:
                            ws["store"].metadata("drain", False)
                            # A paused worker exits once idle; restart it only when work
                            # is waiting or this workspace was named explicitly.
                            if data.get("scope") != "all" or ws["store"].job_headers(("queued",)):
                                start_workspace_worker(ws)
                    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                        failures.append({"source": str(ws["source"]), "error": str(error)})
            elif action == "priority":
                targets = [ws for ws in workspaces if workspace_id(ws) == data.get("workspace_id")]
                if not targets:
                    self.reply_json(404, {"status": "error", "error": "Unknown workspace"})
                    return
                try:
                    registry = Registry()
                    registry.register(targets[0]["source"], targets[0]["state"])
                    registry.set_priority(targets[0]["state"], str(data.get("priority", "")))
                except (sqlite3.Error, OSError, ValueError) as error:
                    self.reply_json(400, {"status": "error", "error": str(error)})
                    return
            elif action == "release_writer":
                writer_id = data.get("writer_id")
                if writer_id:
                    for ws in workspaces:
                        try:
                            with ws["store"].transaction() as db:
                                db.execute("DELETE FROM writers WHERE id=?", (writer_id,))
                        except Exception as error:
                            failures.append({"source": str(ws["source"]), "error": str(error)})
            elif action == "cancel_job":
                job_id = data.get("job_id")
                if job_id:
                    from processes import started_before_boot
                    from worker import finalize_orphan
                    for ws in workspaces:
                        try:
                            try:
                                job = ws["store"].job(job_id)
                            except ValueError:
                                job = None
                            if job and job["status"] in ("running", "cancelling") and started_before_boot(job):
                                # No worker loop owns a job from before the last boot, so
                                # "cancelling" would never resolve. Its processes are gone.
                                finalize_orphan(ws["store"], job_id, "Cancelled after host restart; execution never completed")
                                with ws["store"].transaction() as db:
                                    db.execute("UPDATE requests SET active=0 WHERE job_id=?", (job_id,))
                                continue
                            with ws["store"].transaction() as db:
                                db.execute("UPDATE jobs SET status='superseded',finished=? WHERE id=? AND status='queued'",
                                           (time.time(), job_id))
                                db.execute("UPDATE jobs SET status='cancelling' WHERE id=? AND status='running'",
                                           (job_id,))
                                db.execute("UPDATE requests SET active=0 WHERE job_id=?",
                                           (job_id,))
                        except Exception as error:
                            failures.append({"source": str(ws["source"]), "error": str(error)})

            else:
                self.reply_json(400, {"status": "error", "error": "Unknown action"})
                return
            if failures:
                self.reply_json(500, {"status": "error", "error": "Action failed in one or more workspaces", "failed": failures})
                return

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        self.send_response(HTTPStatus.NOT_FOUND)
        self.end_headers()


def serve_dashboard(store, source, host="127.0.0.1", port=11122):
    """Start dashboard HTTP server."""
    server = ThreadingHTTPServer((host, port), DashboardHandler)
    server.store = store
    server.source = source
    from maintenance import MaintenanceService
    server.maintenance_lock = threading.Lock()
    primary = {'source': source, 'state': store.state}
    service = MaintenanceService(store)
    server.maintenance_services = {workspace_id(primary): service}
    service.start()
    print(f"VDX Build Coordinator Dashboard running at http://{host}:{port}/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nDashboard stopped.")
    finally:
        for service in server.maintenance_services.values():
            service.close()
        server.server_close()
