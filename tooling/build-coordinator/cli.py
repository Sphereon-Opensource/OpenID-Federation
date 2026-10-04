"""Local coordinator client. Only the worker launches or cancels build processes."""
from __future__ import annotations

import argparse
import errno
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import time
import traceback
import uuid

from processes import ExclusiveLock, build_activity, external_builds, pid_alive
from store import ACTIVE, Store

MARKER = '.vdx-build-required.json'
SOURCE = Path(__file__).resolve().parents[2]
WINDOWS = os.name == 'nt'
# Lanes copy the snapshot below <state>\lanes\N\source and Gradle launches tools from outputs below
# it. Win32 CreateProcess refuses an executable past 260 characters even when the file exists, and
# this checkout carries source paths of 240 characters, so the state root must stay short. A
# package-virtualised profile (AppData\Local\Packages\...\LocalCache) alone consumes the budget.
WINDOWS_STATE_ROOT_BUDGET = 64
VIRTUALIZED_PROFILE_SEGMENTS = ('appdata', 'local', 'packages')


def state_root_issue(state):
    """Explain why a state root cannot host Windows lanes, or return None when it can."""
    if not WINDOWS:
        return None
    text = str(state)
    if len(text) > WINDOWS_STATE_ROOT_BUDGET:
        return (f'Coordinator state root {text} is {len(text)} characters; Windows lanes need a root of at most '
                f'{WINDOWS_STATE_ROOT_BUDGET} characters so snapshot trees and tool launches below it stay within the '
                f'Win32 path limit. Drain the worker and register a short private directory with --state, '
                f'for example <drive>:\\.vdx-build\\<name>.')
    return None


def user_home():
    """The real profile directory, even when a packaged app virtualises the process home."""
    home = Path.home()
    parts = [part.strip('\\/').lower() for part in home.parts]
    virtualized = any(tuple(parts[index:index + 3]) == VIRTUALIZED_PROFILE_SEGMENTS for index in range(len(parts)))
    profile = os.environ.get('USERPROFILE')
    if virtualized and profile:
        return Path(profile)
    return home


def safe_path(value):
    path = Path(value).resolve()
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Symlink/reparse paths are not allowed')
    return path


def state_path(source, value=None):
    source = safe_path(source)
    if value is None:
        marker = safe_path(source / MARKER)
        if marker.exists():
            registered = json.loads(marker.read_text(encoding='utf-8'))
            if safe_path(registered['source']) != source:
                raise ValueError('Coordinator marker belongs to another source checkout')
            value = registered['state']
        else:
            key = hashlib.sha256(os.path.normcase(str(source)).encode()).hexdigest()[:20]
            for base in (source.parent / '.vdx-build', user_home() / '.vdx-build'):
                try:
                    return state_path(source, base / key)
                except ValueError:
                    continue
            raise ValueError('No safe default state location; supply --state outside all repositories')
    state = safe_path(value)
    if state == Path(state.anchor) or state == source or source in state.parents or state in source.parents:
        raise ValueError('Coordinator state must be outside the source and cannot be a drive root')
    if any((part / '.git').exists() for part in (state, *state.parents)):
        raise ValueError('Coordinator state cannot be inside a Git repository')
    return state


def emit(value):
    print(json.dumps(value, sort_keys=True), flush=True)


def lock_held(path):
    try:
        with ExclusiveLock(path):
            return False
    except BlockingIOError:
        return True


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name == 'nt':
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
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def recovery_pid_alive(pid):
    """Return liveness for crash provenance, failing closed when PID state is unknown."""
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            error = ctypes.get_last_error()
            if error in (87, 1168):  # ERROR_INVALID_PARAMETER / ERROR_NOT_FOUND
                return False
            raise RuntimeError('Terminal job process state is unavailable; recovery is refused')
        try:
            code = wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise RuntimeError('Terminal job process state is unavailable; recovery is refused')
            return code.value == 259  # STILL_ACTIVE
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        raise RuntimeError('Terminal job process state is unavailable; recovery is refused') from None
    except OSError as error:
        if error.errno == errno.ESRCH:
            return False
        raise RuntimeError('Terminal job process state is unavailable; recovery is refused') from None


def worker_live(store, worker_data=None):
    data = (store.metadata('worker') or {}) if worker_data is None else worker_data
    heartbeat = data.get('heartbeat', 0)
    return (isinstance(heartbeat, (int, float)) and 0 <= time.time() - heartbeat <= 30
            and pid_alive(data.get('pid')) and lock_held(store.state / 'worker.lock'))


def guard(root):
    root = safe_path(root)
    marker = next((part / MARKER for part in (root, *root.parents) if (part / MARKER).exists()), None)
    supplied = os.environ.get('VDX_BUILD_STATE')
    if marker is None and not supplied:
        return 0
    # Explicit local image development may use the current working copy while
    # unrelated agents are editing other areas. Release and pre-release flows
    # do not set this opt-in and retain immutable coordinator enforcement.
    if os.environ.get('VDX_BUILD_DEVELOPMENT') == '1':
        return 0
    if not supplied:
        raise ValueError('This checkout requires vdx-build; direct Gradle invocation is disabled')
    state = safe_path(supplied)
    if not (state / 'queue.sqlite3').is_file():
        raise ValueError('Managed build state is unavailable')
    safe_path(state / 'queue.sqlite3')
    store = Store(state)
    source_value = store.metadata('source')
    if not isinstance(source_value, str):
        raise ValueError('Managed build source is unavailable')
    source = safe_path(source_value)
    if state_path(source, supplied) != state:
        raise ValueError('Invalid managed state')
    if marker:
        safe_path(marker)
        required = json.loads(marker.read_text(encoding='utf-8'))
        if safe_path(required['state']) != state or safe_path(required['source']) != source:
            raise ValueError('Managed build marker does not match this worker')
    if not worker_live(store):
        raise ValueError('Managed build requires a live worker lease')
    job = store.job(os.environ.get('VDX_BUILD_JOB', ''))
    token = os.environ.get('VDX_BUILD_TOKEN', '')
    if job['status'] != 'running' or not job['token'] or not hmac.compare_digest(token, job['token']):
        raise ValueError('Managed build token is invalid or no longer active')
    lane = safe_path(state / 'lanes' / str(job['lane']) / 'source')
    snapshot = store.snapshot(job['spec']['snapshot'])
    if snapshot.get('execution', {}).get('mode') == 'owned-worktree':
        if safe_path(snapshot['source']) != source:
            raise ValueError('Owned managed source mismatch')
        lane = source
    if root != lane and lane not in root.parents:
        raise ValueError('Gradle must run within its assigned managed lane')
    return 0


def checked_snapshot(store, source, snapshot_id):
    snapshot = store.snapshot(snapshot_id)
    origin = snapshot.get('source')
    if origin is None:
        manifest = safe_path(Path(snapshot['path']) / 'manifest.json')
        origin = json.loads(manifest.read_text(encoding='utf-8'))['source']
    if safe_path(origin) != source:
        raise ValueError('Snapshot belongs to another source checkout')
    return snapshot


def job_summary(job):
    return {key: job[key] for key in ('id', 'status', 'created', 'started', 'finished', 'lane', 'phase', 'pid', 'replacement', 'predecessor')} | {
        'snapshot': job['spec']['snapshot'], 'root': job['spec']['root'], 'tasks': job['spec']['tasks']}


def result_summary(request):
    # The worker selects only requested task/test evidence from the shared build.
    from worker import request_result
    return request_result(request)


def result_code(request, summary=None):
    if not request['active']:
        return 3
    status = (summary or {}).get('status', request['job']['status'])
    if status == 'passed':
        return 0
    if status in ('failed', 'interrupted', 'superseded', 'rejected'):
        return 1
    return 2


def watch(store, request_id, after, timeout):
    deadline = time.monotonic() + timeout
    cursor = after
    while True:
        # Read events before the current subscription so a simultaneous replacement
        # cannot advance the cursor past events belonging to its new job.
        events = store.events(cursor)
        request = store.result(request_id)
        relevant = set()
        job = request['job']
        while job and job['id'] not in relevant:
            relevant.add(job['id'])
            job = store.job(job['predecessor']) if job['predecessor'] else None
        for event in events:
            cursor = event['seq']
            if event['request_id'] == request_id or (not event['request_id'] and event['job_id'] in relevant):
                # Event schemas are coordinator-owned; never expose free-form values.
                data = {k: v for k, v in event['data'].items()
                        if k in {'snapshot', 'lane', 'phase', 'replacement', 'reason'}}
                emit({**event, 'data': data})
        if len(events) == 1000:
            continue
        summary = result_summary(request)
        code = result_code(request, summary)
        if code != 2 or time.monotonic() >= deadline:
            if code in (0, 1):
                store.mark_collected(request_id)
            emit({'cursor': cursor, 'request': request_id, 'result': summary})
            return code
        time.sleep(min(0.25, max(0, deadline - time.monotonic())))


WAIT_WORKER_OFFLINE, WAIT_STALLED, WAIT_QUEUED_TOO_LONG = 4, 5, 6


def compact_result(request, summary, extra=None, store=None):
    """What an agent needs to report a build honestly: outcome, when, and why."""
    job = request['job']
    typical = None
    if store is not None:
        try:
            stats = store.task_stats(job['spec'])
            if stats and stats['runs'] >= 3:
                typical = {k: stats[k] for k in ('median_minutes', 'p90_minutes', 'peak_gb', 'runs', 'failures')}
        except Exception:
            typical = None
    finished = job.get('finished')
    tests = summary.get('tests') or {}
    return {
        'request': request['id'], 'job': job['id'], 'status': summary.get('status', job['status']),
        'tasks': [t['path'] for t in request['spec'].get('tasks', [])],
        'excluded_tasks': request['spec'].get('excluded_tasks', []),
        'finished': time.strftime('%Y-%m-%dT%H:%M:%S%z', time.localtime(finished)) if finished else None,
        'finished_minutes_ago': round((time.time() - finished) / 60, 1) if finished else None,
        'tests': {k: tests.get(k) for k in ('executed', 'failures', 'skipped')},
        'issues': (summary.get('issues') or [])[:10],
        'log': summary.get('log') or (job.get('result') or {}).get('log'),
        'typical': typical,
        **(extra or {}),
    }


def wait(store, request_id, max_minutes, stall_minutes, queued_minutes, poll=5.0, offline_grace=60):
    """Block until the request is terminal; fail fast when progress is impossible.

    Meant to run as a background command so the agent is woken by its exit.
    Exit codes: 0 passed, 1 failed, 2 still pending at max_minutes, 3 unsubscribed,
    4 worker offline, 5 build stalled (log unchanged), 6 queued past the limit.
    """
    deadline = time.monotonic() + max_minutes * 60
    offline_since = None
    store.set_waiter(request_id, os.getpid())
    try:
        while True:
            request = store.result(request_id)
            summary = result_summary(request)
            code = result_code(request, summary)
            if code != 2:
                store.mark_collected(request_id)
                emit(compact_result(request, summary, store=store))
                return code
            store.set_waiter(request_id, os.getpid())
            job, now = request['job'], time.time()
            if worker_live(store):
                offline_since = None
            else:
                offline_since = offline_since or now
                if now - offline_since >= offline_grace:
                    emit(compact_result(request, summary, store=store, extra={'status': 'worker-offline',
                         'reason': 'No live coordinator worker for 60s; start it with `vdx-build start`'}))
                    return WAIT_WORKER_OFFLINE
            if job['status'] == 'running' and job.get('started') and stall_minutes > 0:
                log = Path((job.get('result') or {}).get('log') or store.state / 'results' / job['id'] / 'build.log')
                try:
                    last = log.stat().st_mtime
                except OSError:
                    last = job['started']
                if now - max(last, job['started']) >= stall_minutes * 60:
                    emit(compact_result(request, summary, store=store, extra={'status': 'stalled',
                         'reason': f'Build log unchanged for {stall_minutes} minutes', 'phase': job.get('phase')}))
                    return WAIT_STALLED
            if job['status'] == 'queued' and queued_minutes > 0 and now - job['created'] >= queued_minutes * 60:
                blocked = store.metadata('blocked') or {}
                emit(compact_result(request, summary, store=store, extra={'status': 'queued-too-long',
                     'reason': blocked.get('reason') or 'Queued past the wait limit', 'drain': bool(store.metadata('drain'))}))
                return WAIT_QUEUED_TOO_LONG
            if time.monotonic() >= deadline:
                emit(compact_result(request, summary, store=store, extra={'status': 'pending',
                     'reason': f'Still {job["status"]} after {max_minutes} minutes; run wait again'}))
                return 2
            time.sleep(poll)
    finally:
        store.set_waiter(request_id, None)


def pending_requests(store, agent_match=None):
    """Unfinished or uncollected requests, with whether a live `wait` covers each."""
    now = time.time()
    rows = store.agent_requests(agent_match)
    for row in rows:
        row['waiter_live'] = bool(row['waiter_pid'] and row['waiter_heartbeat'] and now - row['waiter_heartbeat'] <= 30
                                  and pid_alive(row['waiter_pid']))
        row['finished_minutes_ago'] = round((now - row['finished']) / 60, 1) if row.get('finished') else None
        try:
            stats = store.task_stats({'tasks': row.pop('task_specs', [])})
            row['typical_minutes'] = stats['median_minutes'] if stats and stats['runs'] >= 3 else None
        except Exception:
            row['typical_minutes'] = None
    return rows


def start(store, source):
    # Serialize clients so only a new launch clears the drain flag.
    with ExclusiveLock(store.state / 'start.lock'):
        if lock_held(store.state / 'worker.lock'):
            if not worker_live(store):
                raise ValueError('Worker lock is held without a recent heartbeat; inspect worker.log')
            emit({'status': 'already-running', 'worker': store.metadata('worker')})
            return 0
        store.metadata('drain', False)
        log_path = store.state / 'worker.log'
        with log_path.open('ab', buffering=0) as log:
            kwargs = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {'start_new_session': True}
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--source', str(source),
                                        '--state', str(store.state), 'serve'], cwd=source, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, close_fds=True, **kwargs)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if worker_live(store):
                emit({'status': 'running', 'worker': store.metadata('worker'), 'log': str(log_path)})
                return 0
            if process.poll() is not None:
                raise ValueError('Worker exited during startup; inspect worker.log')
            time.sleep(0.1)
        raise ValueError('Worker startup is not yet confirmed; inspect status and worker.log')


def parser():
    p = argparse.ArgumentParser(description='Source-pinned local build queue. Clients never stop Gradle or unowned processes.')
    p.add_argument('--source', default=str(SOURCE), help='Source checkout (default: launcher repository)')
    p.add_argument('--state', help='Private state outside repositories (default: registered source marker, otherwise sibling .vdx-build/source-hash)')
    commands = p.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init', help='Enable checkout guard and initialize state; does not start builds')
    init.add_argument('--slots', type=int, choices=(1, 2), default=1)
    snapshot_cmd = commands.add_parser('snapshot', help='Capture immutable source; refuses while an edit lease exists')
    snapshot_cmd.add_argument('--workspace', help='Owned group receipt outside the source checkout')
    snapshot_cmd.add_argument('--selection', help='Bounded roots/scopes/files JSON for owned execution')
    snapshot_cmd.add_argument('--agent', help='Exact owned workspace agent/session identity')
    submit = commands.add_parser('submit', help='Subscribe an agent to tasks from a registered snapshot')
    submit.add_argument('--request', required=True, help='JSON request file; runtime identity is supplied automatically')
    submit.add_argument('--agent', required=True)
    commands.add_parser('status', help='Show queue and persistent edit leases without secrets')
    register_cmd = commands.add_parser('register', help='Register an existing queue for machine-wide admission and status')
    register_cmd.add_argument('--state', dest='register_state', required=True, help='Existing coordinator state directory')
    cleanup = commands.add_parser(
        'cleanup-results',
        help='Report or evict terminal result JSON already present in the archive',
    )
    cleanup.add_argument('--apply', action='store_true', help='Evict the SQLite copies after archive validation')
    maintenance = commands.add_parser('cleanup', help='Preview snapshot, blob, log and idle database retention')
    maintenance.add_argument('--apply', action='store_true', help='Apply the preview policy to coordinator-owned state')
    recover = commands.add_parser(
        'recover-crash-artifacts',
        help='Archive and remove only crash logs proven to belong to terminal jobs in one drained lane',
    )
    recover.add_argument('--lane', required=True, type=int, help='Lane number shown by status')
    watch_cmd = commands.add_parser('watch', help='Stream request events; exit 0 passed, 1 failed, 2 pending, 3 unsubscribed')
    watch_cmd.add_argument('request')
    watch_cmd.add_argument('--after', type=int, default=0, help='Last durable event cursor received')
    watch_cmd.add_argument('--timeout', type=float, default=60, help='Seconds to follow, from 0 through 60')
    wait_cmd = commands.add_parser('wait', help='Block until the request finishes; run it in the background so its exit wakes the agent. '
                                   'Exit 0 passed, 1 failed, 2 still pending, 3 unsubscribed, 4 worker offline, 5 stalled, 6 queued too long')
    wait_cmd.add_argument('request')
    wait_cmd.add_argument('--max-minutes', type=float, default=240, help='Give up and exit 2 after this long (default 240)')
    wait_cmd.add_argument('--stall-minutes', type=float, default=20, help='Exit 5 when a running build log is unchanged this long; 0 disables (default 20)')
    wait_cmd.add_argument('--queued-minutes', type=float, default=0, help='Exit 6 when still queued after this long; 0 disables (default)')
    pending_cmd = commands.add_parser('pending', help='List requests that are unfinished or finished without their result being collected')
    pending_cmd.add_argument('--agent', help='Only requests of this agent/session id (full id or 8+ character prefix)')
    result = commands.add_parser('result', help='Show request-specific evidence and archive paths; same exit codes as watch')
    result.add_argument('request')
    supersede = commands.add_parser('supersede', help='Replace an eligible early build for latest-following subscribers')
    supersede.add_argument('job')
    supersede.add_argument('--snapshot', required=True)
    unsubscribe = commands.add_parser('unsubscribe', help='Detach only this request; does not cancel a build')
    unsubscribe.add_argument('request')
    edit = commands.add_parser('edit', help='Hold a persistent cooperative writer lease across source edits')
    edits = edit.add_subparsers(dest='edit_command', required=True)
    begin = edits.add_parser('begin', help='Register writer; lease never expires silently')
    begin.add_argument('--agent', required=True)
    end = edits.add_parser('end', help='Release the exact writer lease returned by begin')
    end.add_argument('writer')
    commands.add_parser('start', help='Start hidden background worker; existing drain state is preserved if running')
    commands.add_parser('serve', help='Run worker in foreground (normally started by start)')
    commands.add_parser('drain', help='Stop admitting new jobs; let active work finish')
    dash_cmd = commands.add_parser('dash', help='Run Web Dashboard to view and manage queue, memory, and whitelist')
    dash_cmd.add_argument('--host', default='127.0.0.1', help='Host to bind (default: 127.0.0.1)')
    dash_cmd.add_argument('--port', type=int, default=11122, help='Port to bind (default: 11122)')
    config_cmd = commands.add_parser('config', help='View or update persistent coordinator settings')
    config_cmd.add_argument('--get', help='Get value of a config key')
    config_cmd.add_argument('--set', action='append', help='Set key=value in config (e.g. --set include_swap=true)')
    config_cmd.add_argument('--list', action='store_true', help='List all config entries')
    whitelist_cmd = commands.add_parser('whitelist', help='Manage external build process whitelist')
    whitelist_sub = whitelist_cmd.add_subparsers(dest='whitelist_action', required=True)
    whitelist_sub.add_parser('list', help='List current whitelist patterns')
    wl_add = whitelist_sub.add_parser('add', help='Add PID or glob pattern to whitelist')
    wl_add.add_argument('pattern', help='PID or pattern (e.g. *idea* or 12345)')
    wl_rm = whitelist_sub.add_parser('remove', help='Remove PID or glob pattern from whitelist')
    wl_rm.add_argument('pattern', help='PID or pattern to remove')
    guard_cmd = commands.add_parser('guard', help='Validate a Gradle wrapper managed-lane lease')
    guard_cmd.add_argument('--root', required=True)
    return p


def run(args):
    if args.command == 'guard':
        return guard(args.root)
    if args.command == 'register':
        from registry import Registry, readonly_connect
        state = safe_path(args.register_state)
        queue = state / 'queue.sqlite3'
        if not queue.is_file():
            raise ValueError('Coordinator state does not contain queue.sqlite3')
        safe_path(queue)
        with readonly_connect(queue) as db:
            row = db.execute("SELECT value FROM metadata WHERE key='source'").fetchone()
        if not row:
            raise ValueError('Coordinator state metadata does not contain its source')
        source = safe_path(json.loads(row['value']))
        if not source.is_dir():
            raise ValueError('Coordinator source directory no longer exists')
        if state_path(source, state) != state:
            raise ValueError('Coordinator state path is invalid')
        Registry().register(source, state, legacy=True)
        emit({'source': str(source), 'state': str(state), 'registered': True, 'legacy_worker': True})
        return 0
    source = safe_path(args.source)
    if not source.is_dir() or source == Path(source.anchor):
        raise ValueError('Source must be an existing checkout directory, not a drive root')
    state = state_path(source, args.state)
    if args.command != 'init' and not (state / 'queue.sqlite3').is_file():
        raise ValueError('Coordinator is not initialized; run init first')
    if args.command == 'init' and not (state / 'queue.sqlite3').is_file():
        # A new registration is refused before any state is created; an existing one keeps
        # working and reports the problem through status.
        issue = state_root_issue(state)
        if issue:
            raise ValueError(issue)
    safe_path(state / 'queue.sqlite3')
    store = Store(state)
    if args.command == 'init':
        with ExclusiveLock(state / 'capture.lock'):
            previous = store.metadata('source')
            if previous and safe_path(previous) != source:
                raise ValueError('State is already registered to another source')
            marker = safe_path(source / MARKER)
            if marker.exists():
                previous_marker = json.loads(marker.read_text(encoding='utf-8'))
                if safe_path(previous_marker['state']) != state or safe_path(previous_marker['source']) != source:
                    raise ValueError('Checkout is already registered to different coordinator state')
            if store.metadata('build_environment') is None:
                from worker import build_environment
                try:
                    with ExclusiveLock(state / 'worker.lock'):
                        store.metadata('build_environment', build_environment())
                except BlockingIOError:
                    raise ValueError('Drain the existing worker before init pins the canonical build environment') from None
            store.metadata('source', str(source))
            store.metadata('slots', args.slots)
            temporary = source / (MARKER + '.' + uuid.uuid4().hex + '.tmp')
            try:
                temporary.write_text(json.dumps({'source': str(source), 'state': str(state)}, indent=2) + '\n', encoding='utf-8')
                temporary.replace(marker)
            finally:
                temporary.unlink(missing_ok=True)
        from registry import Registry
        Registry().register(source, state)
        emit({'source': str(source), 'state': str(state), 'slots': args.slots})
        return 0
    if store.metadata('source') != str(source):
        raise ValueError('State source does not match --source')
    if args.command == 'snapshot':
        from snapshots import capture
        with ExclusiveLock(state / 'capture.lock'):
            with store.connect() as db:
                if db.execute('SELECT 1 FROM writers LIMIT 1').fetchone():
                    raise ValueError('Source has active edit leases; run edit end after all writers finish')
            if any((args.workspace, args.selection, args.agent)):
                if not all((args.workspace, args.selection, args.agent)):
                    raise ValueError('Owned capture requires workspace, selection and agent')
                from owned_inputs import capture_owned
                selection = json.loads(Path(args.selection).read_text(encoding='utf-8-sig'))
                snapshot = capture_owned(source, state, args.workspace, args.agent, selection)
            else:
                snapshot = {**capture(source, state), 'source': str(source)}
            store.register_snapshot(snapshot)
        emit(snapshot)
    elif args.command == 'edit':
        with ExclusiveLock(state / 'capture.lock'), store.transaction() as db:
            if args.edit_command == 'begin':
                if not args.agent.strip():
                    raise ValueError('Agent/session identity is required')
                owned_pending = db.execute("SELECT 1 FROM jobs j JOIN snapshots s ON s.id=json_extract(j.spec,'$.snapshot') "
                    "WHERE j.status IN ('queued','running','cancelling') AND json_extract(s.data,'$.execution.mode')='owned-worktree' LIMIT 1").fetchone()
                if owned_pending:
                    raise ValueError('Owned worktree build is pending; collect its result before editing')
                writer = 'W' + uuid.uuid4().hex
                db.execute('INSERT INTO writers VALUES(?,?,?)', (writer, args.agent, time.time()))
                emit({'writer': writer, 'agent': args.agent})
            else:
                if not db.execute('DELETE FROM writers WHERE id=?', (args.writer,)).rowcount:
                    raise ValueError('Unknown writer lease')
                emit({'released': args.writer})
    elif args.command == 'submit':
        spec = json.loads(Path(args.request).read_text(encoding='utf-8-sig'))
        if not isinstance(spec, dict) or 'runtime' in spec:
            raise ValueError('Request must be an object without a runtime field')
        checked_snapshot(store, source, spec.get('snapshot'))
        from worker import runtime_identity
        base_environment = store.metadata('build_environment')
        if base_environment is None:
            raise ValueError('Canonical build environment is missing; drain the worker and run init')
        spec['runtime'] = runtime_identity(spec.get('environment', {}), base_environment=base_environment)
        request_id = store.submit(spec, args.agent)
        emit({'request': request_id, 'job': store.result(request_id)['job_id']})
    elif args.command == 'status':
        with store.connect() as db:
            writers = [dict(row) for row in db.execute('SELECT * FROM writers ORDER BY created')]
            subscribers = [dict(row) for row in db.execute('SELECT id,agent,job_id,active FROM requests')]
            counts = {row['status']: row['count'] for row in db.execute(
                'SELECT status,COUNT(*) AS count FROM jobs GROUP BY status')}
            terminal_rows = db.execute(
                "SELECT id,status,created,started,finished,lane,phase,pid,replacement,predecessor,spec "
                "FROM jobs WHERE status IN ('passed','failed','interrupted','superseded') "
                "ORDER BY finished DESC LIMIT 20"
            ).fetchall()
        jobs = [{**job_summary(job), 'log': str(state / 'results' / job['id'] / 'build.log'),
                 'subscribers': [r for r in subscribers if r['job_id'] == job['id'] and r['active']]} for job in store.jobs(statuses=('queued', 'running', 'cancelling'))]
        completed = []
        for row in terminal_rows:
            job = dict(row)
            spec = json.loads(job.pop('spec'))
            job.update({'snapshot': spec['snapshot'], 'root': spec['root'], 'tasks': spec['tasks'],
                        'log': str(state / 'results' / row['id'] / 'build.log')})
            completed.append(job)
        blocked = store.metadata('blocked') or {}
        from registry import Registry
        emit({'source': str(source), 'state': str(state), 'state_warning': state_root_issue(state), 'worker_live': worker_live(store),
              'drain': bool(store.metadata('drain')), 'blocked': {k: v for k, v in blocked.items() if k in ('reason', 'pids')},
              'writers': writers, 'jobs': jobs, 'completed': completed, 'counts': counts,
              'global': Registry().global_view()})
    elif args.command == 'cleanup-results':
        emit(store.cleanup_results(apply=args.apply))
    elif args.command == 'cleanup':
        from maintenance import run_maintenance
        report = run_maintenance(store, apply=args.apply)
        emit(report)
        return 1 if report['status'] == 'failed' else 0
    elif args.command == 'recover-crash-artifacts':
        from snapshots import _guard, recover_crash_artifacts
        if args.lane < 0:
            raise ValueError('Lane number must be nonnegative')
        lane_root = Path(os.path.abspath(state / 'lanes' / str(args.lane)))
        lane = lane_root / 'source'
        _guard(lane_root)
        _guard(lane)
        _guard(state / 'start.lock')
        _guard(lane_root / 'lane.lock')
        if not lane.is_dir():
            raise ValueError(f'Lane does not exist: {args.lane}')

        def ensure_quiescent():
            if not store.metadata('drain'):
                raise ValueError('Coordinator must remain drained during crash-artifact recovery')
            if store.jobs(statuses=('running', 'cancelling')):
                raise ValueError('Coordinator has active jobs; crash-artifact recovery is refused')
            try:
                lane_home = (state / 'lanes' / str(args.lane) / 'gradle-home').resolve()
                lane_paths = [str(lane.resolve()).lower(), str(lane_home).lower()]
                from worker import gradle_user_home
                try:
                    physical_home = gradle_user_home(state, args.lane).lower()
                    lane_paths.append(physical_home)
                except Exception:
                    physical_home = str(lane_home).lower()
                from gradle_registry import daemon_states
                lane_daemon_pids = set(daemon_states(Path(physical_home)))
                own_pids = {int(j['pid']) for j in store.job_headers(tuple(ACTIVE)) if j.get('pid')}
                for item in build_activity():
                    command = (item.get('command') or '').lower().replace('\\', '/')
                    cwd = (item.get('cwd') or '').lower().replace('\\', '/')
                    belongs_to_lane = (item['pid'] in lane_daemon_pids or
                                       any(p.replace('\\', '/') in command or cwd == p.replace('\\', '/')
                                           or cwd.startswith(p.replace('\\', '/') + '/')
                                           for p in lane_paths))
                    if (item['pid'] in own_pids and pid_alive(item['pid'])) or belongs_to_lane:
                        raise ValueError('Lane build process detected; crash-artifact recovery is refused')
            except RuntimeError:
                raise ValueError('Host process inspection failed; crash-artifact recovery is refused') from None
        # Serialize against worker start and lane work. Both locks are
        # nonblocking; a busy owner is a hard stop, never an invitation to retry.
        try:
            with ExclusiveLock(state / 'start.lock'), ExclusiveLock(lane_root / 'lane.lock'):
                ensure_quiescent()
                lane_record = json.loads(_guard(lane / '.vdx-coordinator-sources.json').read_text(encoding='utf-8'))
                snapshot = store.snapshot(lane_record.get('snapshot'))
                snapshot_path = safe_path(snapshot.get('path'))
                terminal_jobs = [job for job in store.jobs()
                                 if job.get('lane') == args.lane
                                 and job.get('status') in {'passed', 'failed', 'interrupted', 'superseded'}]
                result = recover_crash_artifacts(
                    snapshot_path, lane, state / 'recovered-crash-artifacts', args.lane,
                    terminal_jobs, recovery_pid_alive, precondition_check=ensure_quiescent,
                )
        except BlockingIOError as error:
            raise ValueError('Coordinator start or lane lock is busy; crash-artifact recovery is refused') from error
        emit(result)
    elif args.command == 'result':
        request = store.result(args.request)
        summary = result_summary(request)
        emit(summary)
        code = result_code(request, summary)
        if code in (0, 1):
            store.mark_collected(args.request)
        return code
    elif args.command == 'wait':
        if args.max_minutes <= 0 or args.stall_minutes < 0 or args.queued_minutes < 0:
            raise ValueError('wait limits must be positive minutes')
        return wait(store, args.request, args.max_minutes, args.stall_minutes, args.queued_minutes)
    elif args.command == 'pending':
        emit({'pending': pending_requests(store, args.agent)})
    elif args.command == 'watch':
        if not 0 <= args.timeout <= 60 or args.after < 0:
            raise ValueError('watch timeout must be 0 through 60 seconds and cursor nonnegative')
        return watch(store, args.request, args.after, args.timeout)
    elif args.command == 'supersede':
        checked_snapshot(store, source, args.snapshot)
        emit({'job': args.job, 'replacement': store.supersede(args.job, args.snapshot)})
    elif args.command == 'unsubscribe':
        store.unsubscribe(args.request)
        emit({'request': args.request, 'status': 'unsubscribed'})
    elif args.command == 'start':
        return start(store, source)
    elif args.command == 'serve':
        from worker import Worker
        Worker(store, source, slots=store.metadata('slots') or 1).serve()
    elif args.command == 'drain':
        store.metadata('drain', True)
        emit({'drain': True})
    elif args.command == 'dash':
        from dashboard import serve_dashboard
        serve_dashboard(store, source, host=args.host, port=args.port)
    elif args.command == 'config':
        if args.get:
            emit({args.get: store.config(args.get)})
        elif args.set:
            updates = {}
            for item in args.set:
                if '=' not in item:
                    raise ValueError('Config format must be key=value')
                k, v = item.split('=', 1)
                k = k.strip()
                v = v.strip()
                if v.lower() == 'true':
                    parsed = True
                elif v.lower() == 'false':
                    parsed = False
                else:
                    try:
                        parsed = float(v) if '.' in v else int(v)
                    except ValueError:
                        parsed = v
                updates[k] = parsed
            store.update_config(updates)
            emit({'updated': updates})
        else:
            emit(store.all_config())
    elif args.command == 'whitelist':
        cfg = store.all_config()
        pats = list(cfg.get('whitelist_patterns', []))
        if args.whitelist_action == 'list':
            emit({'whitelist': pats})
        elif args.whitelist_action == 'add':
            pat = args.pattern.strip()
            if pat and pat not in pats:
                pats.append(pat)
                store.config('whitelist_patterns', pats)
            emit({'added': pat, 'whitelist': pats})
        elif args.whitelist_action == 'remove':
            pat = args.pattern.strip()
            pats = [p for p in pats if str(p) != pat]
            store.config('whitelist_patterns', pats)
            emit({'removed': pat, 'whitelist': pats})
    return 0


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        return run(args)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        # Exceptions from request/runtime parsing can contain supplied secret values.
        if isinstance(error, (KeyError, TypeError)):
            frames = traceback.extract_tb(error.__traceback__)
            if frames:
                frame = frames[-1]
                location = f'{Path(frame.filename).name}:{frame.name}:{frame.lineno}'
            else:
                location = 'unknown'
            print(f'error: invalid coordinator metadata or request structure ({type(error).__name__} at {location})',
                  file=sys.stderr)
        else:
            print('error: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
