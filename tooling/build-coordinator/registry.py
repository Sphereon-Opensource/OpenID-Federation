"""Machine-wide coordinator registry and admission reservations."""
from __future__ import annotations

import contextlib
import json
import math
import os
from pathlib import Path
import sqlite3
import stat
import re
import time

from processes import ExclusiveLock

HEARTBEAT_TTL = 30
EXTERNAL_ADMISSION_VERSION = 1
# Store.validate_config bounds fixed policies at eight. A single native compiler
# holds that entire supported host-slot space without changing any host policy.
NATIVE_HOST_SLOT_CEILING = 8
# Workspace priorities as fair-share weights: over time a High workspace
# starts about three builds for every one a Low workspace starts.
PRIORITY_WEIGHTS = {'low': 1, 'normal': 2, 'high': 3}
FAIR_SHARE_WINDOW = 3600


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc, tb):
        try:
            return super().__exit__(exc_type, exc, tb)
        finally:
            self.close()


def registry_path():
    override = os.environ.get('VDX_BUILD_REGISTRY')
    if override:
        raw = Path(override).expanduser()
    elif os.name == 'nt':
        # Not %LOCALAPPDATA%: Windows redirects AppData writes from packaged
        # (MSIX) apps such as agent desktops into a per-package copy, which
        # splits workers into groups that cannot see each other's builds.
        raw = Path.home() / '.vdx-build-registry' / 'registry.sqlite3'
    else:
        base = os.environ.get('XDG_STATE_HOME')
        raw = (Path(base).expanduser() if base else Path.home() / '.local' / 'state') / 'vdx-build' / 'registry.sqlite3'
    path = Path(os.path.abspath(raw))
    # Reject links/reparse points in every existing component before resolving.
    for part in reversed((path, *path.parents)):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Symlink/reparse paths are not allowed for the coordinator registry')
    resolved = path.resolve()
    if resolved == Path(resolved.anchor) or any((part / '.git').exists() for part in (resolved.parent, *resolved.parent.parents)):
        raise ValueError('Coordinator registry must be outside all repositories')
    return resolved


def checked_path(value):
    path = Path(os.path.abspath(Path(value).expanduser()))
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Symlink/reparse paths are not allowed for coordinator registrations')
    return path.resolve()


def _inside_repository(path):
    return any((part / '.git').exists() for part in (path, *path.parents))


def native_fixed_slots(db, default):
    """Match Store config > valid metadata > default; reject unsupported policy."""
    configured = db.execute("SELECT value FROM config WHERE key='slots'").fetchone()
    baseline = db.execute("SELECT value FROM metadata WHERE key='slots'").fetchone()
    try:
        baseline = json.loads(baseline['value']) if baseline else None
        value = json.loads(configured['value']) if configured else (
            baseline if type(baseline) is int and 1 <= baseline <= NATIVE_HOST_SLOT_CEILING else default)
    except (ValueError, TypeError) as error:
        raise RuntimeError('External native requires registered fixed slot policies 1 through 8') from error
    if type(value) is not int or not 1 <= value <= NATIVE_HOST_SLOT_CEILING:
        raise RuntimeError('External native requires registered fixed slot policies 1 through 8')
    return value


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name != 'nt':
        try:
            os.kill(pid, 0)
            return True
        except (OSError, ProcessLookupError):
            return False
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    try:
        code = wintypes.DWORD()
        return bool(kernel.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259
    finally:
        kernel.CloseHandle(handle)


class Registry:
    def __init__(self, path=None):
        self.path = Path(path) if path else registry_path()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # Revalidate after mkdir, including any newly created path components.
        if path is None:
            expected = registry_path()
            if self.path.resolve() != expected:
                raise ValueError('Coordinator registry path changed while opening')
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS workspaces(
                    source TEXT NOT NULL, state TEXT PRIMARY KEY,
                    worker_pid INTEGER, last_seen REAL, updated REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS reservations(
                    job_id TEXT PRIMARY KEY, state TEXT NOT NULL, lane INTEGER NOT NULL,
                    memory_gb REAL NOT NULL, tier TEXT NOT NULL, pid INTEGER NOT NULL,
                    heartbeat REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS admission(
                    id INTEGER PRIMARY KEY CHECK(id=1), free_gb REAL, reserved_gb REAL,
                    decision_time REAL, state TEXT, job_id TEXT);
                INSERT OR IGNORE INTO admission(id) VALUES(1);
                CREATE TABLE IF NOT EXISTS starts(state TEXT NOT NULL, time REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS starts_state_time ON starts(state,time);
                CREATE TABLE IF NOT EXISTS external_builds(
                    run_id TEXT PRIMARY KEY, kind TEXT NOT NULL, source TEXT NOT NULL, state TEXT NOT NULL,
                    owner_pid INTEGER NOT NULL, heartbeat REAL NOT NULL, created REAL NOT NULL,
                    deadline REAL NOT NULL, status TEXT NOT NULL, container_id TEXT,
                    spec TEXT NOT NULL, result TEXT);
            ''')
            columns = {row['name'] for row in db.execute('PRAGMA table_info(workspaces)')}
            if 'priority' not in columns:
                db.execute("ALTER TABLE workspaces ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
            if 'admission_version' not in columns:
                db.execute('ALTER TABLE workspaces ADD COLUMN admission_version INTEGER NOT NULL DEFAULT 0')
            if 'workspace_kind' not in columns:
                db.execute("ALTER TABLE workspaces ADD COLUMN workspace_kind TEXT NOT NULL DEFAULT 'gradle'")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=30, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA busy_timeout=30000')
        return db

    @property
    def lock_path(self):
        return self.path.with_name(self.path.name + '.lock')

    def lock(self):
        return ExclusiveLock(self.lock_path)

    @contextlib.contextmanager
    def wait_for_lock(self, timeout=10):
        """Bound terminal-result collection through brief admission lock contention."""
        if timeout < 0:
            raise ValueError('Lock timeout must be nonnegative')
        deadline = time.monotonic() + timeout
        lock = self.lock()
        while True:
            try:
                lock.__enter__()
                break
            except BlockingIOError:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise
                time.sleep(min(0.05, remaining))
        try:
            yield lock
        finally:
            lock.close()

    def register(self, source, state, worker_pid=None, heartbeat=None, legacy=False, admission_version=0):
        source_path, state_path = checked_path(source), checked_path(state)
        if _inside_repository(state_path):
            raise ValueError('Coordinator state must be outside all repositories')
        if state_path == Path(state_path.anchor) or state_path == source_path or source_path in state_path.parents or state_path in source_path.parents:
            raise ValueError('Coordinator state cannot be the source, a source ancestor, or a source descendant')
        source, state = str(source_path), str(state_path)
        now = time.time()
        with self.connect() as db:
            if legacy:
                db.execute('''INSERT INTO workspaces(source,state,worker_pid,last_seen,updated) VALUES(?,?,NULL,NULL,?)
                              ON CONFLICT(state) DO UPDATE SET source=excluded.source,worker_pid=NULL,last_seen=NULL,updated=excluded.updated''',
                           (source, state, now))
            elif worker_pid is not None:
                db.execute('''INSERT INTO workspaces(source,state,worker_pid,last_seen,updated,admission_version) VALUES(?,?,?,?,?,?)
                              ON CONFLICT(state) DO UPDATE SET source=excluded.source,worker_pid=excluded.worker_pid,
                              last_seen=excluded.last_seen,updated=excluded.updated,admission_version=excluded.admission_version''',
                           (source, state, int(worker_pid), heartbeat or now, now, admission_version))
            else:
                db.execute('''INSERT INTO workspaces(source,state,updated) VALUES(?,?,?)
                              ON CONFLICT(state) DO UPDATE SET source=excluded.source,updated=excluded.updated''',
                           (source, state, now))

    def heartbeat(self, source, state, pid, running=()):
        """Refresh this worker's registration and re-assert its running builds' reservations.

        Another worker's claim prunes reservations whose heartbeat looks stale, for
        example while this worker's loop is briefly stalled on the registry lock.
        Re-creating them here keeps those builds counted by every worker's slot
        and memory checks instead of disappearing for the rest of their run.
        """
        now = time.time()
        self.register(source, state, worker_pid=pid, heartbeat=now,admission_version=EXTERNAL_ADMISSION_VERSION)
        state_key = str(Path(state).resolve())
        with self.connect() as db:
            db.execute('UPDATE reservations SET heartbeat=? WHERE state=?', (now, state_key))
            for job in running:
                db.execute('''INSERT OR IGNORE INTO reservations(job_id,state,lane,memory_gb,tier,pid,heartbeat)
                              VALUES(?,?,?,?,?,?,?)''',
                           (job['id'], state_key, job['lane'] if job.get('lane') is not None else 0,
                            float(job['memory_gb']), job['tier'], int(pid), now))

    def prune(self):
        with self.connect() as db:
            rows = db.execute('SELECT state FROM workspaces').fetchall()
            external_states = {r['state'] for r in db.execute("SELECT state FROM external_builds WHERE status IN ('reserved','running')")}
            missing = [r['state'] for r in rows if not Path(r['state']).is_dir() and r['state'] not in external_states]
            db.executemany('DELETE FROM workspaces WHERE state=?', [(p,) for p in missing])
            db.executemany('DELETE FROM reservations WHERE state=?', [(p,) for p in missing])
        return missing

    def workspaces(self):
        self.prune()
        with self.connect() as db:
            rows = [dict(r) for r in db.execute('SELECT * FROM workspaces ORDER BY source,state')]
        for row in rows:
            row['source'] = str(checked_path(row['source']))
            row['state'] = str(checked_path(row['state']))
            if _inside_repository(Path(row['state'])):
                raise ValueError('Registered coordinator state is inside a repository')
        return rows

    def set_priority(self, state, priority):
        if priority not in PRIORITY_WEIGHTS:
            raise ValueError('Priority must be low, normal or high')
        with self.connect() as db:
            if db.execute('UPDATE workspaces SET priority=? WHERE state=?',
                          (priority, str(checked_path(state)))).rowcount == 0:
                raise ValueError('Workspace is not registered')

    def priorities(self):
        with self.connect() as db:
            return {r['state']: r['priority'] for r in db.execute('SELECT state,priority FROM workspaces')}

    def record_start(self, state):
        now = time.time()
        with self.connect() as db:
            db.execute('INSERT INTO starts(state,time) VALUES(?,?)', (str(checked_path(state)), now))
            db.execute('DELETE FROM starts WHERE time<?', (now - FAIR_SHARE_WINDOW,))

    def fair_share_order(self):
        """Workspaces that are waiting for a slot, in the order they should get one.

        A workspace competes when its worker is live, admission is not paused and
        it has queued work. Recent starts divided by the priority weight is its
        share used; the lowest goes first, ties to the higher priority and then
        to the longest-waiting job.
        """
        now = time.time()
        with self.connect() as db:
            spaces = [dict(r) for r in db.execute('SELECT * FROM workspaces')]
            starts = {r['state']: r['n'] for r in db.execute(
                'SELECT state,COUNT(*) AS n FROM starts WHERE time>=? GROUP BY state', (now - FAIR_SHARE_WINDOW,))}
        contenders = []
        for ws in spaces:
            if not (ws.get('worker_pid') and ws.get('last_seen') and 0 <= now - ws['last_seen'] <= HEARTBEAT_TTL
                    and pid_alive(ws['worker_pid'])):
                continue
            queue = Path(ws['state']) / 'queue.sqlite3'
            try:
                with readonly_connect(queue) as qdb:
                    drain = qdb.execute("SELECT value FROM metadata WHERE key='drain'").fetchone()
                    if drain and json.loads(drain['value']):
                        continue
                    oldest = qdb.execute("SELECT MIN(created) AS t FROM jobs WHERE status='queued'").fetchone()['t']
            except (sqlite3.Error, OSError, ValueError):
                continue
            if oldest is None:
                continue
            weight = PRIORITY_WEIGHTS.get(ws.get('priority') or 'normal', 2)
            contenders.append({'state': ws['state'], 'source': ws['source'], 'priority': ws.get('priority') or 'normal',
                               'used': starts.get(ws['state'], 0) / weight, 'weight': weight, 'oldest': oldest})
        return sorted(contenders, key=lambda c: (c['used'], -c['weight'], c['oldest']))

    def reservations(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM reservations ORDER BY state,job_id')]

    def put_reservation(self, job_id, state, lane, memory_gb, tier, pid, heartbeat):
        with self.connect() as db:
            db.execute('''INSERT OR REPLACE INTO reservations(job_id,state,lane,memory_gb,tier,pid,heartbeat)
                          VALUES(?,?,?,?,?,?,?)''',
                       (job_id, str(Path(state).resolve()), lane, float(memory_gb), tier, int(pid), heartbeat))

    def remove_reservation(self, job_id):
        with self.connect() as db:
            db.execute('DELETE FROM reservations WHERE job_id=?', (job_id,))

    def record_admission(self, free_gb, reserved_gb, state=None, job_id=None):
        with self.connect() as db:
            db.execute('UPDATE admission SET free_gb=?,reserved_gb=?,decision_time=?,state=?,job_id=? WHERE id=1',
                       (free_gb, reserved_gb, time.time(), str(state) if state else None, job_id))

    def last_admission(self):
        with self.connect() as db:
            row = db.execute('SELECT free_gb,reserved_gb,decision_time,state,job_id FROM admission WHERE id=1').fetchone()
            return dict(row) if row else {}

    def external_runs(self):
        with self.connect() as db:
            rows = [dict(row) for row in db.execute('SELECT * FROM external_builds ORDER BY created,run_id')]
        for row in rows:
            row['spec'] = json.loads(row['spec'])
            row['result'] = json.loads(row['result']) if row['result'] else None
        return rows

    def external_run(self, run_id):
        rows = [row for row in self.external_runs() if row['run_id'] == run_id]
        return rows[0] if rows else None

    def _external_projection(self, source, state, run_id, spec, *, terminal=False):
        """Reserve typed host-slot holds for the pre-protocol Registry reader.

        One authoritative N owns the compiler/result and all eight slot holds.
        Only its primary hold carries memory; spare holds reserve exclusivity,
        so old readers sum exactly one compiler charge. There are no Gradle B
        jobs, requests, workers, tasks or outcomes. Current readers skip this
        complete projection and count only the authoritative N registration.
        Migration/publication/retirement are atomic under shared admission lock.
        """
        projection = checked_path(Path(state) / 'external-native-admission')
        projection.mkdir(exist_ok=True)
        self.register(source,projection,legacy=True)
        with self.connect() as db:
            db.execute("UPDATE workspaces SET workspace_kind='external-native-compatibility' WHERE state=?",(str(projection),))
        path = checked_path(projection / 'queue.sqlite3')
        with sqlite3.connect(path,factory=ClosingConnection) as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('''CREATE TABLE IF NOT EXISTS native_builders(
                id TEXT PRIMARY KEY,status TEXT NOT NULL,spec TEXT NOT NULL,created REAL NOT NULL)''')
            db.execute('''CREATE TABLE IF NOT EXISTS native_host_slots(
                run_id TEXT NOT NULL,slot_index INTEGER NOT NULL,spec TEXT NOT NULL,
                PRIMARY KEY(run_id,slot_index))''')
            db.execute('CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT)')
            db.execute("INSERT OR IGNORE INTO metadata VALUES('drain','true')")
            db.execute("INSERT OR IGNORE INTO metadata VALUES('builder_kind','\"local-docker-native-image\"')")
            if terminal:
                db.execute("UPDATE native_builders SET status='finished' WHERE id=?",(run_id,))
            else:
                compatible = dict(spec,snapshot=spec['sourceSnapshot'],tasks=[],root='external-native',memory_auto=False)
                db.execute('INSERT INTO native_builders VALUES(?,?,?,?)',
                           (run_id,'running',json.dumps(compatible,sort_keys=True,separators=(',',':')),time.time()))
            # Upgrade existing one-row projections in the same transaction.
            for identity, body in db.execute('SELECT id,spec FROM native_builders').fetchall():
                value = json.loads(body)
                for index in range(NATIVE_HOST_SLOT_CEILING):
                    hold = dict(value,memory_gb=value['memory_gb'] if index == 0 else 0,
                        nativeHostSlot=dict(runId=identity,index=index,ceiling=NATIVE_HOST_SLOT_CEILING))
                    db.execute('INSERT OR REPLACE INTO native_host_slots VALUES(?,?,?)',
                        (identity,index,json.dumps(hold,sort_keys=True,separators=(',',':'))))
            db.execute('DROP VIEW IF EXISTS jobs')
            db.execute('''CREATE VIEW jobs AS SELECT
                CASE WHEN h.slot_index=0 THEN b.id ELSE b.id || ':host-slot:' || h.slot_index END AS id,
                b.status,h.spec,b.created,NULL AS lane,NULL AS pid,'external-native-host-slot' AS phase
                FROM native_builders b JOIN native_host_slots h ON h.run_id=b.id''')

    def claim_external(self, run_id, source, state, spec, *, owner_pid, free_gb,
                       docker_memory_gb, docker_free_gb, docker_cpus, disk_free_bytes):
        """Atomically reserve exclusive host slots for one finite Docker compiler.

        Native execution remains outside the Gradle queue. Orphaned containers
        retain their charge until the producer verifies its own terminal outcome.
        Registered policies must use supported fixed slots 1 through 8. The
        eight typed holds also exclude late pre-protocol fixed policies through
        eight (and the known legacy auto cap of three). Unknown/corrupt late
        policies beyond validated bounds are outside the supported contract.
        No foreign policy or worker is changed here.
        """
        required = {'kind','workspaceId','sourceSnapshot','nativeInputId','compilerPolicyId',
                    'memory_gb','cpus','disk_bytes','timeout_seconds','resources','inputManifestSha256'}
        if (not isinstance(run_id,str) or not re.fullmatch(r'N[a-f0-9]{32}',run_id) or
                not isinstance(spec,dict) or set(spec) != required or spec['kind'] != 'local-docker-native-image'):
            raise ValueError('Invalid typed external native reservation')
        for field in ('workspaceId','sourceSnapshot','nativeInputId','compilerPolicyId','inputManifestSha256'):
            if not isinstance(spec[field],str) or not re.fullmatch(r'[a-f0-9]{64}',spec[field]):
                raise ValueError('Invalid external native input identity')
        for field,maximum in [('memory_gb',96),('cpus',256),('disk_bytes',1024**4),('timeout_seconds',3600)]:
            value = spec[field]
            if type(value) not in (int,float) or not math.isfinite(value) or not 0 < value <= maximum:
                raise ValueError('External native resources require finite explicit bounds')
            if field != 'memory_gb' and type(value) is not int:
                raise ValueError('External native CPU/disk/timeout must be integers')
        if spec['resources'] != ['docker-linux-native'] or owner_pid != os.getpid() or not pid_alive(owner_pid):
            raise ValueError('External reservation needs the live actual owner and exact resource')
        source,state = str(checked_path(source)),str(checked_path(state))
        for value in (free_gb,docker_memory_gb,docker_free_gb,docker_cpus,disk_free_bytes):
            if type(value) not in (int,float) or not math.isfinite(value) or value < 0:
                raise RuntimeError('External native resource telemetry is unavailable')
        from store import DEFAULT_CONFIG, reservation_headroom
        with self.wait_for_lock():
            view = self.global_active(prune_reservations=True)
            own = next((ws for ws in view['workspaces'] if ws['state'] == state and ws['source'] == source),None)
            if own is None:
                raise ValueError('External native workspace is not registered')
            now = time.time()
            policies = {}
            for ws in view['workspaces']:
                if ws.get('workspace_kind') == 'external-native-compatibility':
                    continue
                queue = Path(ws['state']) / 'queue.sqlite3'
                if not queue.exists():
                    raise RuntimeError('Registered coordinator policy is unavailable for native admission')
                with readonly_connect(queue) as db:
                    policies[ws['state']] = native_fixed_slots(db,DEFAULT_CONFIG['slots'])
            # Use the existing workspace config without modifying slot or headroom policy.
            with readonly_connect(Path(state) / 'queue.sqlite3') as db:
                cfg = dict(DEFAULT_CONFIG)
                cfg.update({row['key']:json.loads(row['value']) for row in db.execute('SELECT key,value FROM config')})
                cfg['slots'] = policies[state]
            if view['active']:
                raise RuntimeError('Waiting for exclusive shared native host slots; another build is active')
            headroom = reservation_headroom({'memory_gb':spec['memory_gb'],'memory_auto':False},cfg,free_gb)
            if free_gb < spec['memory_gb'] + headroom:
                raise RuntimeError('Insufficient shared host memory for explicit native reservation')
            if docker_memory_gb < spec['memory_gb'] or docker_free_gb < spec['memory_gb'] or docker_cpus < spec['cpus']:
                raise RuntimeError('Docker Linux resource limits cannot satisfy explicit native reservation')
            if disk_free_bytes < spec['disk_bytes']:
                raise RuntimeError('Insufficient disk for explicit finite native output reservation')
            with self.connect() as db:
                db.execute('''INSERT INTO external_builds(run_id,kind,source,state,owner_pid,heartbeat,created,deadline,status,spec)
                              VALUES(?,?,?,?,?,?,?,?,?,?)''',
                           (run_id,spec['kind'],source,state,owner_pid,now,now,now + spec['timeout_seconds'],
                            'reserved',json.dumps(spec,sort_keys=True,separators=(',',':'))))
            self._external_projection(source,state,run_id,spec)
            self.record_admission(free_gb,0,state,run_id)
            self.record_start(state)
        return self.external_run(run_id)

    def external_heartbeat(self, run_id, owner_pid, *, container_id=None):
        with self.connect() as db:
            row = db.execute('SELECT * FROM external_builds WHERE run_id=?',(run_id,)).fetchone()
            if not row or row['owner_pid'] != owner_pid or owner_pid != os.getpid() or row['status'] not in ('reserved','running'):
                raise ValueError('External heartbeat does not belong to this live producer')
            if container_id is not None and (not re.fullmatch(r'[a-f0-9]{64}',container_id) or
                    row['container_id'] not in (None,container_id)):
                raise ValueError('External compiler container identity changed')
            db.execute('UPDATE external_builds SET heartbeat=?,container_id=COALESCE(?,container_id),status=? WHERE run_id=?',
                       (time.time(),container_id,'running' if container_id or row['container_id'] else 'reserved',run_id))

    def finish_external(self, run_id, owner_pid, result):
        """Retain producer evidence, releasing only after the exact container ended."""
        with self.wait_for_lock():
            with self.connect() as db:
                row = db.execute('SELECT * FROM external_builds WHERE run_id=?',(run_id,)).fetchone()
                if (not row or row['owner_pid'] != owner_pid or owner_pid != os.getpid() or
                        row['status'] not in ('reserved','running') or not isinstance(result,dict) or
                        result.get('containerId') != row['container_id'] or result.get('finished') is not True or
                        type(result.get('exitCode')) is not int):
                    raise ValueError('External terminal outcome lacks matching owned container proof')
                success = result['exitCode'] == 0 and result.get('outputManifestSha256') is not None
                if success and (row['status'] != 'running' or not isinstance(row['container_id'],str) or
                        not re.fullmatch(r'[a-f0-9]{64}',row['container_id']) or
                        not isinstance(result['outputManifestSha256'],str) or
                        not re.fullmatch(r'[a-f0-9]{64}',result['outputManifestSha256'])):
                    raise ValueError('Successful external native result requires bound container and valid output checksum')
                db.execute('UPDATE external_builds SET status=?,result=?,heartbeat=? WHERE run_id=?',
                           ('passed' if success else 'failed',
                            json.dumps(result,sort_keys=True,separators=(',',':')),time.time(),run_id))
            self._external_projection(row['source'],row['state'],run_id,json.loads(row['spec']),terminal=True)

    def release_orphaned_external(self, run_id, source, state, result):
        """Release a dead supervisor only after its adapter proves terminal/absent container.

        Recovery never promotes orphaned output to an accepted native result.
        The adapter checks actual Docker labels, argv, mounts and limits first;
        this boundary checks registered ownership and conservative terminal shape.
        """
        with self.wait_for_lock():
            row = self.external_run(run_id)
            if (not row or row['status'] not in ('reserved','running') or pid_alive(row['owner_pid']) or
                    row['source'] != str(checked_path(source)) or row['state'] != str(checked_path(state)) or
                    not isinstance(result,dict) or result.get('finished') is not True or result.get('exitCode') != 1 or
                    result.get('failure') != 'orphan-terminal-recovery' or result.get('outputManifestSha256') is not None):
                raise ValueError('Orphan recovery lacks dead-owner and exact workspace terminal proof')
            container = result.get('containerId')
            if container is None:
                if row['container_id'] is not None or result.get('containerAbsent') is not True:
                    raise ValueError('Orphan native container absence was not proven')
            elif (not isinstance(container,str) or not re.fullmatch(r'[a-f0-9]{64}',container) or
                    row['container_id'] not in (None,container) or result.get('containerRunning') is not False):
                raise ValueError('Orphan terminal container differs from registered owner')
            with self.connect() as db:
                db.execute('UPDATE external_builds SET status=?,result=?,heartbeat=? WHERE run_id=?',
                    ('failed',json.dumps(result,sort_keys=True,separators=(',',':')),time.time(),run_id))
            self._external_projection(row['source'],row['state'],run_id,row['spec'],terminal=True)

    def reconcile_external_terminal(self, run_id, source, state):
        """Idempotently retire an old-reader projection after a terminal DB commit.

        A crash between two SQLite files cannot create an uncharged running
        compiler: the retained terminal record proves that producer ended. This
        operation changes only its compatibility view, never accepts new output.
        """
        with self.wait_for_lock():
            row = self.external_run(run_id)
            result = row.get('result') if row else None
            if (not row or row['status'] not in ('passed','failed') or row['source'] != str(checked_path(source)) or
                    row['state'] != str(checked_path(state)) or not isinstance(result,dict) or
                    result.get('finished') is not True or type(result.get('exitCode')) is not int):
                raise ValueError('External projection has no registered terminal outcome')
            if row['status'] == 'passed' and (result.get('containerId') != row['container_id'] or
                    not isinstance(row['container_id'],str) or not re.fullmatch(r'[a-f0-9]{64}',row['container_id']) or
                    not isinstance(result.get('outputManifestSha256'),str) or
                    not re.fullmatch(r'[a-f0-9]{64}',result['outputManifestSha256'])):
                raise ValueError('External projection has no bound successful container/output proof')
            self._external_projection(row['source'],row['state'],run_id,row['spec'],terminal=True)

    def global_active(self, prune_reservations=False):
        """Return active jobs, live reservation rows, and reservation memory total."""
        from store import Store
        now = time.time()
        spaces = self.workspaces()
        space_by_state = {w['state']: w for w in spaces}
        active = []
        active_keys = set()
        live_reservations = []
        with self.connect() as db:
            reservation_rows = [dict(r) for r in db.execute('SELECT * FROM reservations')]
        for ws in spaces:
            if ws.get('workspace_kind') == 'external-native-compatibility':
                continue
            state = Path(ws['state'])
            queue = state / 'queue.sqlite3'
            if not queue.is_file():
                continue
            try:
                with readonly_connect(queue) as db:
                    rows = db.execute("SELECT id,status,lane,pid,spec FROM jobs WHERE status IN ('running','cancelling')").fetchall()
                    active.extend({'id': r['id'], 'state': str(state), 'lane': r['lane'], 'pid': r['pid'],
                                   'spec': json.loads(r['spec']), 'status': r['status']} for r in rows)
                    active_keys.update((str(state), r['id']) for r in rows)
            except (sqlite3.Error, OSError, ValueError):
                continue
        live_total = 0.0
        for reservation in reservation_rows:
            ws = space_by_state.get(reservation['state'])
            key = (reservation['state'], reservation['job_id'])
            live = (ws is not None and key in active_keys and pid_alive(reservation['pid'])
                    and 0 <= now - reservation['heartbeat'] <= HEARTBEAT_TTL
                    and ws.get('worker_pid') == reservation['pid'] and ws.get('last_seen') is not None
                    and 0 <= now - ws['last_seen'] <= HEARTBEAT_TTL)
            if live:
                live_reservations.append(reservation)
                live_total += float(reservation['memory_gb'])
        # Expire stale and completed reservations. An explicitly registered old worker has no
        # worker PID; its active job specs are accounted below by admission/status callers.
        if prune_reservations:
            live_ids = {(r['state'], r['job_id']) for r in live_reservations}
            with self.connect() as db:
                for reservation in reservation_rows:
                    if (reservation['state'], reservation['job_id']) not in live_ids:
                        db.execute('DELETE FROM reservations WHERE job_id=?', (reservation['job_id'],))
        legacy = [j for j in active if (space_by_state.get(j['state']) or {}).get('worker_pid') is None]
        # A Docker process survives its host client. Staleness marks an orphan,
        # never removes its reservation or treats its global lane as free.
        external = [row for row in self.external_runs() if row['status'] in ('reserved','running')]
        for row in external:
            orphan = not pid_alive(row['owner_pid']) or not 0 <= now - row['heartbeat'] <= HEARTBEAT_TTL
            active.append(dict(id=row['run_id'],kind=row['kind'],state=row['state'],lane=None,
                pid=row['owner_pid'],spec=dict(row['spec'],snapshot=row['spec']['sourceSnapshot'],tasks=[]),
                status='orphaned' if orphan else row['status']))
            live_reservations.append(dict(job_id=row['run_id'],kind=row['kind'],state=row['state'],lane=None,
                memory_gb=row['spec']['memory_gb'],tier='native',pid=row['owner_pid'],heartbeat=row['heartbeat']))
            live_total += float(row['spec']['memory_gb'])
        return {'active': active, 'reservations': live_reservations, 'reserved_gb': live_total,
                'legacy': legacy, 'workspaces': spaces,'external':external}

    def global_view(self):
        state = self.global_active()
        live_by_state = {}
        for row in state['reservations']:
            live_by_state.setdefault(row['state'], []).append(row)
        legacy_keys = {(j['state'], j['id']) for j in state['legacy']}
        from store import classify_task_tier
        for job in state['legacy']:
            live_by_state.setdefault(job['state'], []).append({
                'job_id': job['id'], 'state': job['state'], 'lane': job['lane'],
                'memory_gb': float(job['spec'].get('memory_gb', 0)),
                'tier': classify_task_tier(job['spec']), 'pid': job.get('pid'),
                'heartbeat': None, 'legacy': True,
            })
        queues = []
        active_memory = state['reserved_gb'] + sum(float(j['spec'].get('memory_gb', 0)) for j in state['legacy'])
        for ws in state['workspaces']:
            if ws.get('workspace_kind') == 'external-native-compatibility':
                continue
            queue = Path(ws['state']) / 'queue.sqlite3'
            if not queue.is_file():
                continue
            try:
                with readonly_connect(queue) as db:
                    rows = db.execute("SELECT id,status,lane,pid,spec FROM jobs WHERE status IN ('running','cancelling','queued') ORDER BY created").fetchall()
                active_jobs, queued = [], []
                for row in rows:
                    raw_spec = json.loads(row['spec'])
                    safe_spec = {key: raw_spec[key] for key in ('snapshot', 'root', 'tasks', 'memory_gb') if key in raw_spec}
                    job = {'id': row['id'], 'status': row['status'], 'lane': row['lane'], 'pid': row['pid'],
                           'spec': safe_spec}
                    if row['status'] == 'queued':
                        queued.append(job)
                    elif (ws['state'], row['id']) in legacy_keys or any(r['job_id'] == row['id'] for r in live_by_state.get(ws['state'], [])):
                        active_jobs.append(job)
                live = (ws.get('worker_pid') is not None and ws.get('last_seen') is not None
                        and 0 <= time.time() - ws['last_seen'] <= HEARTBEAT_TTL and pid_alive(ws['worker_pid']))
                queues.append({'source': ws['source'], 'state': ws['state'], 'worker_live': live,
                               'worker_pid': ws.get('worker_pid'), 'drain': False,
                               'active': active_jobs, 'queued': queued,
                               'reservations': live_by_state.get(ws['state'], [])})
                with readonly_connect(queue) as db:
                    row = db.execute("SELECT value FROM metadata WHERE key='drain'").fetchone()
                if row:
                    queues[-1]['drain'] = bool(json.loads(row['value']))
            except (sqlite3.Error, OSError, ValueError):
                continue
        return {'queues': queues, 'reserved_gb': round(active_memory, 3),
                'last_admission': self.last_admission(),'externalBuilders':state['external']}


def readonly_connect(path):
    uri = Path(path).resolve().as_uri() + '?mode=ro'
    db = sqlite3.connect(uri, uri=True, timeout=5, factory=ClosingConnection)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA query_only=ON')
    return db


class ReadOnlyStore:
    """Read-only queue facade used when inspecting registered peer states."""
    def __init__(self, state):
        self.state = Path(state).resolve()
        self.path = self.state / 'queue.sqlite3'

    def connect(self):
        return readonly_connect(self.path)

    def metadata(self, key, value=None):
        if value is not None:
            raise RuntimeError('Peer coordinator state is read-only')
        with self.connect() as db:
            row = db.execute('SELECT value FROM metadata WHERE key=?', (key,)).fetchone()
            return json.loads(row['value']) if row else None

    def jobs(self, statuses=None):
        from store import Store
        with self.connect() as db:
            if statuses:
                slots = ','.join('?' for _ in statuses)
                rows = db.execute(f'SELECT * FROM jobs WHERE status IN ({slots}) ORDER BY created DESC', tuple(statuses))
            else:
                rows = db.execute('SELECT * FROM jobs ORDER BY created DESC')
            return [Store.decode_job(row) for row in rows]

    def job(self, job_id):
        from store import Store
        with self.connect() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (job_id,)).fetchone()
            return Store.decode_job(row)

    def job_headers(self, statuses):
        with self.connect() as db:
            slots = ','.join('?' for _ in statuses)
            return [dict(row) for row in db.execute(
                f'SELECT id,status,lane,pid FROM jobs WHERE status IN ({slots}) ORDER BY created DESC', tuple(statuses))]
