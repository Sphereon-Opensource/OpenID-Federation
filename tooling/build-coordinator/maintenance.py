"""Background, preview-first retention for coordinator-owned state.

Age, latest-snapshot and live-reference protections take precedence over storage
budgets. Budgets therefore report unmet targets rather than deleting protected
source or evidence. Lane sources and caches are never removed here.
"""
from __future__ import annotations

from contextlib import closing, contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import stat
import threading
import time
import uuid

from processes import ExclusiveLock
from snapshots import MARKER, PENDING, _guard, _relative

HASH = re.compile(r'[a-f0-9]{64}\Z')
JOB = re.compile(r'B[a-f0-9]{16}\Z')
LIVE = ('queued', 'running', 'cancelling')
CHUNK = 65536
MAX_TOKEN = 1024 * 1024
MAX_ACTIONS = 200
DEFAULTS = dict(cleanup_enabled=True, cleanup_interval_minutes=60,
                snapshot_retention_days=7, snapshot_keep_latest=3, snapshot_budget_gb=20)


@contextmanager
def _connection(store):
    with store.connect() as db:
        db.execute('PRAGMA busy_timeout=0')
        yield db


@contextmanager
def _transaction(store):
    with _connection(store) as db:
        db.execute('BEGIN IMMEDIATE')
        yield db


class JsonStream:
    """Validate JSON and expose scalar paths without loading entire manifests.

    The semantic digest ignores object key order and preserves array order. The
    canonical digest omits only the root id, matching sorted snapshot manifests.
    Memory is bounded by one token, nesting depth and one object's keys; large
    arrays (files, tasks, test cases) are streamed.
    """
    def __init__(self, stream):
        self.stream, self.buffer, self.offset, self.eof = stream, '', 0, False
        self.canonical = hashlib.sha256()
        self.digest = None
        self.root_kind = None
        self.top_types = {}

    def peek(self):
        while self.offset == len(self.buffer) and not self.eof:
            self.buffer = self.stream.read(CHUNK)
            self.offset = 0
            self.eof = not self.buffer
        return self.buffer[self.offset:self.offset + 1]

    def space(self):
        while self.peek() and self.peek().isspace():
            self.offset += 1

    def take(self, expected):
        self.space()
        if self.peek() != expected:
            raise ValueError(f'Invalid JSON: expected {expected}')
        self.offset += 1

    def scalar(self):
        self.space()
        if self.peek() == '"':
            decoder = json.JSONDecoder()
            while True:
                try:
                    value, end = decoder.raw_decode(self.buffer, self.offset)
                    if end - self.offset > MAX_TOKEN:
                        raise ValueError('JSON token exceeds maintenance safety bound')
                    self.offset = end
                    return value
                except json.JSONDecodeError:
                    remaining = self.buffer[self.offset:]
                    if self.eof or len(remaining) > MAX_TOKEN:
                        raise ValueError('Invalid or excessive JSON string') from None
                    more = self.stream.read(CHUNK)
                    self.eof = not more
                    self.buffer, self.offset = remaining + more, 0
        token = ''
        quoted = self.peek() == '"'
        escaped = False
        while self.peek():
            char = self.peek()
            if not quoted and (char.isspace() or char in ',]}'):
                break
            self.offset += 1
            token += char
            if len(token) > MAX_TOKEN:
                raise ValueError('JSON token exceeds maintenance safety bound')
            if quoted and len(token) > 1 and char == '"' and not escaped:
                break
            escaped = not escaped if char == '\\' else False
        value = json.loads(token)
        if isinstance(value, (dict, list)) or isinstance(value, float) and not math.isfinite(value):
            raise ValueError('Invalid JSON scalar')
        return value

    def write(self, value, enabled):
        if enabled:
            self.canonical.update(value.encode('utf-8'))

    def value(self, path=(), canonical=True):
        if len(path) > 64:
            raise ValueError('JSON nesting exceeds maintenance safety bound')
        self.space()
        char = self.peek()
        if len(path) == 1:
            self.top_types[path[0]] = char
        if char == '{':
            self.take('{')
            self.write('{', canonical)
            fields, keys, count = [], set(), 0
            self.space()
            while self.peek() != '}':
                if keys:
                    self.take(',')
                key = self.scalar()
                if not isinstance(key, str) or key in keys or len(keys) >= 10000:
                    raise ValueError('Invalid or excessive JSON object keys')
                keys.add(key)
                self.take(':')
                include = canonical and not (not path and key == 'id')
                if include:
                    self.write((',' if count else '') + json.dumps(key, ensure_ascii=True) + ':', True)
                    count += 1
                digest = yield from self.value(path + (key,), include)
                fields.append((key, digest))
                self.space()
            self.take('}')
            self.write('}', canonical)
            result = hashlib.sha256(b'object')
            for key, digest in sorted(fields):
                result.update(json.dumps(key, ensure_ascii=True).encode() + digest)
            return result.digest()
        if char == '[':
            self.take('[')
            self.write('[', canonical)
            result, index = hashlib.sha256(b'array'), 0
            self.space()
            while self.peek() != ']':
                if index:
                    self.take(',')
                    self.write(',', canonical)
                result.update((yield from self.value(path + (index,), canonical)))
                index += 1
                self.space()
            self.take(']')
            self.write(']', canonical)
            return result.digest()
        value = self.scalar()
        encoded = json.dumps(value, ensure_ascii=True, separators=(',', ':'))
        self.write(encoded, canonical)
        yield path, value
        return hashlib.sha256(b'scalar' + encoded.encode()).digest()

    def values(self):
        self.space()
        self.root_kind = self.peek()
        self.digest = yield from self.value()
        self.space()
        if self.peek():
            raise ValueError('Trailing JSON data')


def _rooted(state, path):
    state = _guard(Path(os.path.abspath(state)))
    path = _guard(Path(os.path.abspath(path)))
    path.resolve().relative_to(state.resolve())
    if path == state:
        raise ValueError('Maintenance cannot mutate the state root')
    return path


def _walk(state, root):
    """Yield regular files, refusing reparse points rather than traversing them."""
    root = _rooted(state, root)
    if not root.exists():
        return
    stack = [root]
    while stack:
        current = stack.pop()
        with os.scandir(current) as entries:
            for entry in entries:
                path = _rooted(state, entry.path)
                info = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    stack.append(path)
                elif stat.S_ISREG(info.st_mode):
                    yield path, info
                else:
                    raise ValueError(f'Nonregular maintenance entry: {path}')


def _size(state, root):
    total = dict(bytes=0, files=0)
    for _, info in _walk(state, root):
        total['bytes'] += info.st_size
        total['files'] += 1
    return total


def _inventory_walk(state, root):
    """Observe file sizes without repeated ancestor checks for sibling files.

    Only directory handles are opened. Validate their full paths immediately
    before opening, reject redirected entries, and keep a depth-first iterator
    stack so memory does not grow with the number of files or sibling folders.
    Mutation scans continue to use _walk and its per-file path validation.
    """
    stack = []

    def descend(directory):
        directory = _rooted(state, directory)
        try:
            stack.append(os.scandir(directory))
        except FileNotFoundError:
            pass  # Concurrent capture may remove a directory from this estimate.

    try:
        descend(root)
        while stack:
            try:
                entry = next(stack[-1])
            except StopIteration:
                stack.pop().close()
                continue
            try:
                info = entry.stat(follow_symlinks=False)
            except FileNotFoundError:
                continue
            path = Path(entry.path)
            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                raise ValueError(f'Redirected maintenance entry: {path}')
            if stat.S_ISDIR(info.st_mode):
                descend(path)
            elif stat.S_ISREG(info.st_mode):
                yield path, info
            else:
                raise ValueError(f'Nonregular maintenance entry: {path}')
    finally:
        for entries in reversed(stack):
            entries.close()


def storage_inventory(store, progress=None):
    """Observed logical bytes; concurrent writes and shared hardlinks affect totals."""
    scanned_files, scanned_bytes, reported_files = 0, 0, 0
    reported_at = time.monotonic() if progress else 0

    def report(category, now=None):
        nonlocal reported_files, reported_at
        if progress:
            progress(f'inventory ({category}): {scanned_files:,} files, {scanned_bytes:,} bytes scanned')
            reported_files = scanned_files
            reported_at = time.monotonic() if now is None else now

    def record(category, amount):
        nonlocal scanned_files, scanned_bytes
        scanned_files += 1
        scanned_bytes += amount
        if progress:
            now = time.monotonic()
            if scanned_files - reported_files >= 2048 or now - reported_at >= 1:
                report(category, now)

    def size(root, category):
        total = dict(bytes=0, files=0)
        for _, info in _inventory_walk(store.state, root):
            total['bytes'] += info.st_size
            total['files'] += 1
            record(category, info.st_size)
        report(category)
        return total

    usage = {key: dict(bytes=0, files=0) for key in ('snapshots', 'blobs', 'lanes', 'logs', 'evidence', 'database')}
    for name in ('snapshots', 'blobs', 'lanes'):
        usage[name] = size(store.state / name, name)
    # Expired manifests remain evidence, including partially removed content
    # after a crash or locked-file failure.
    usage['evidence'] = size(store.state / 'expired-snapshots', 'expired snapshots')
    for path, info in _inventory_walk(store.state, store.state / 'results'):
        key = 'logs' if path.name in ('build.log', 'build.log.gz', 'events.jsonl', 'events.jsonl.gz') else 'evidence'
        usage[key]['bytes'] += info.st_size
        usage[key]['files'] += 1
        record('logs/evidence', info.st_size)
    report('logs/evidence')
    for name in ('queue.sqlite3', 'queue.sqlite3-wal', 'queue.sqlite3-shm', 'queue.sqlite3-journal'):
        path = _rooted(store.state, store.state / name)
        if path.is_file():
            amount = path.stat().st_size
            usage['database']['bytes'] += amount
            usage['database']['files'] += 1
            record('database', amount)
    report('database')
    return usage


def _manifest(store, path, identity, references=None, expiring=False):
    path = _rooted(store.state, path / 'manifest.json')
    found_id, version, source = None, None, None
    file_index, fields = None, set()
    required = {'path', 'sha256', 'size', 'mode'}
    with path.open('r', encoding='utf-8') as stream:
        reader = JsonStream(stream)
        for key, value in reader.values():
            if key == ('id',):
                found_id = value
            elif key == ('version',):
                version = value
            elif key == ('source',):
                source = value
            elif key and key[0] == 'files':
                if len(key) != 3 or type(key[1]) is not int:
                    raise ValueError('Invalid manifest file entry')
                if file_index != key[1]:
                    if file_index is not None and not required <= fields:
                        raise ValueError('Incomplete manifest file entry')
                    file_index, fields = key[1], set()
                field = key[2]
                fields.add(field)
                if field == 'path':
                    _relative(value)
                elif field in ('size', 'mode'):
                    if type(value) is not int or value < 0 or field == 'mode' and value > 0o777:
                        raise ValueError('Invalid manifest file metadata')
                elif field == 'sha256':
                    if not isinstance(value, str) or not HASH.fullmatch(value):
                        raise ValueError('Invalid manifest content digest')
                    if references is not None:
                        references.execute('INSERT INTO refs VALUES(?,?,?) ON CONFLICT(digest) DO UPDATE SET '
                                           'retained=MAX(retained,excluded.retained),links=links+excluded.links',
                                           (value, int(not expiring), int(expiring)))
        if (found_id != identity or reader.canonical.hexdigest() != identity or version != 1
                or not isinstance(source, str) or not Path(source).is_absolute()
                or reader.top_types.get('files') != '[' or file_index is not None and not required <= fields):
            raise ValueError(f'Invalid immutable snapshot manifest: {identity}')


def _lane_pins(store):
    pins = set()
    lanes = _rooted(store.state, store.state / 'lanes')
    if not lanes.exists():
        return pins
    for lane in lanes.iterdir():
        lane = _rooted(store.state, lane)
        if not lane.is_dir():
            continue
        for name in (MARKER, PENDING):
            marker = _rooted(store.state, lane / 'source' / name)
            if not marker.exists():
                continue
            records = {}
            with marker.open('r', encoding='utf-8') as stream:
                reader = JsonStream(stream)
                for key, value in reader.values():
                    record = key[:1] if name == PENDING else ()
                    field = key[1:] if name == PENDING else key
                    if name == PENDING and record not in (('previous',), ('next',)):
                        raise ValueError('Invalid pending lane ownership record')
                    if field and field[0] not in ('snapshot', 'manifest', 'lane', 'files'):
                        raise ValueError('Invalid lane ownership field')
                    if len(field) == 1 and field[0] in ('snapshot', 'manifest', 'lane'):
                        records.setdefault(record, {})[field[0]] = value
                if reader.root_kind != '{':
                    raise ValueError('Invalid lane ownership metadata')
            if (name == MARKER and () not in records or name == PENDING and ('next',) not in records):
                raise ValueError('Lane ownership metadata contains no next snapshot reference')
            for record in records.values():
                identity = record.get('snapshot')
                if (not isinstance(identity, str) or not HASH.fullmatch(identity) or
                        not isinstance(record.get('lane'), str) or Path(record['lane']) != lane / 'source' or
                        not isinstance(record.get('manifest'), str) or
                        Path(record['manifest']) != store.state / 'snapshots' / identity):
                    raise ValueError('Invalid lane snapshot ownership reference')
                pins.add(identity)
    return pins


def _live_pins(db):
    pins = set()
    for row in db.execute("SELECT json_extract(spec,'$.snapshot') AS snapshot FROM jobs WHERE status IN ('queued','running','cancelling')"):
        value = row['snapshot']
        if not isinstance(value, str) or not HASH.fullmatch(value):
            raise ValueError('Invalid live job snapshot reference')
        pins.add(value)
    return pins


def _remove_content(store, content):
    """Delete only an authenticated expired snapshot's content subtree."""
    content = _rooted(store.state, content)
    if content.name != 'content' or content.parent.parent != store.state / 'expired-snapshots':
        raise ValueError('Unexpected snapshot cleanup target')
    # Validate all descendants before the first deletion. Never pass discovered
    # names to a different shell or follow a link during Windows cleanup.
    for _ in _walk(store.state, content):
        pass
    if not content.exists():
        return
    for base, directories, files in os.walk(content, topdown=False, followlinks=False):
        for name in files:
            path = _rooted(store.state, Path(base) / name)
            try:
                path.unlink()
            except PermissionError:
                info = path.stat()
                restore = None
                if info.st_nlink > 1:
                    with path.open('rb') as stream:
                        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    restore = _rooted(store.state, store.state / 'blobs' / digest[:2] / digest)
                    if not restore.is_file() or not os.path.samefile(path, restore):
                        raise ValueError('Readonly shared content has no proven cache link; removal deferred')
                path.chmod(stat.S_IWRITE | stat.S_IREAD)
                try:
                    path.unlink()
                finally:
                    if restore is not None:
                        restore.chmod(stat.S_IMODE(info.st_mode))
        for name in directories:
            _rooted(store.state, Path(base) / name).rmdir()
    content.rmdir()


def _snapshot_rows(store):
    # Close each read cursor before yielding: SQLite readers must not obstruct
    # the short expiration transaction, and metadata batches stay bounded.
    previous = (-1, '')
    while True:
        with _connection(store) as db:
            rows = db.execute("SELECT id,created,expired_at,json_extract(data,'$.path') AS path FROM snapshots "
                              "WHERE (COALESCE(created,0),id) > (?,?) ORDER BY COALESCE(created,0),id LIMIT 100",
                              previous).fetchall()
        if not rows:
            return
        for row in rows:
            yield row
        previous = (rows[-1]['created'] or 0, rows[-1]['id'])


def _snapshot_cleanup(store, config, apply, now):
    summary = dict(candidates=[], candidate_count=0, candidate_bytes=0, removed_count=0,
                   protected_count=0, total_bytes=0, budget_bytes=int(config['snapshot_budget_gb'] * 2 ** 30),
                   budget_satisfied=True, errors=[], removed_bytes=0, _expiring=[])
    pins = _lane_pins(store)
    with _connection(store) as db:
        pins.update(_live_pins(db))
        latest = {row['id'] for row in db.execute('SELECT id FROM snapshots WHERE expired_at IS NULL ORDER BY created DESC,id LIMIT ?',
                                                (int(config['snapshot_keep_latest']),))}
        pins.update(latest)
    for row in _snapshot_rows(store):
        identity = row['id']
        if not isinstance(identity, str) or not HASH.fullmatch(identity):
            raise ValueError('Invalid registered snapshot ID')
        original = _rooted(store.state, store.state / 'snapshots' / identity)
        archived = _rooted(store.state, store.state / 'expired-snapshots' / identity)
        if row['path'] is None or Path(os.path.abspath(row['path'])) != original:
            raise ValueError(f'Snapshot path is outside its owned location: {identity}')
        path = original if (original / 'manifest.json').exists() else archived
        if not path.exists():
            if row['expired_at'] is not None:
                continue
            raise ValueError(f'Registered snapshot is missing: {identity}')
        _manifest(store, path, identity)
        if row['expired_at'] is not None and not original.exists() and not (archived / 'content').exists():
            continue
        if original.exists() and set(item.name for item in original.iterdir()) - {'content', 'manifest.json'}:
            raise ValueError('Unexpected files in snapshot directory; cleanup deferred')
        size = _size(store.state, path / 'content')['bytes']
        summary['total_bytes'] += size
        if identity in pins or row['expired_at'] is None and (row['created'] is None or now - row['created'] < config['snapshot_retention_days'] * 86400):
            summary['protected_count'] += 1
            continue
        summary['candidate_count'] += 1
        summary['candidate_bytes'] += size
        summary['_expiring'].append(identity)
        action = dict(id=identity, bytes=size, status='preview')
        if len(summary['candidates']) < MAX_ACTIONS:
            summary['candidates'].append(action)
        if not apply:
            continue
        # Recheck under the write transaction: a submit which won first is
        # protected; a later submit sees expired_at and refuses missing bytes.
        fresh_lane_pins = _lane_pins(store)
        with _transaction(store) as writer:
            if writer.execute('SELECT 1 FROM writers LIMIT 1').fetchone():
                action['status'] = 'deferred-writer'
                continue
            if identity in _live_pins(writer) or identity in fresh_lane_pins:
                action['status'] = 'protected-concurrent-reference'
                continue
            writer.execute('UPDATE snapshots SET expired_at=COALESCE(expired_at,?) WHERE id=?', (now, identity))
        try:
            if original.exists():
                archived.parent.mkdir(parents=True, exist_ok=True)
                if archived.exists():
                    # A prior immutable manifest can survive recapture.
                    _manifest(store, archived, identity)
                    if (archived / 'content').exists():
                        raise ValueError('Previous expired content still awaits removal')
                    # Move just content into the already preserved archive.
                    if (original / 'content').exists():
                        (original / 'content').rename(archived / 'content')
                    manifest = _rooted(store.state, original / 'manifest.json')
                    if manifest.exists():
                        manifest.chmod(stat.S_IWRITE | stat.S_IREAD)
                        manifest.unlink()
                    original.rmdir()
                else:
                    original.rename(archived)
            _remove_content(store, archived / 'content')
            action['status'] = 'removed'
            summary['removed_count'] += 1
            summary['removed_bytes'] += size
        except (OSError, ValueError) as error:
            action.update(status='deferred', error=str(error))
            summary['errors'].append(f'{identity}: {error}')
    remaining = summary['total_bytes'] - (summary['removed_bytes'] if apply else summary['candidate_bytes'])
    summary['budget_satisfied'] = remaining <= summary['budget_bytes']
    summary['budget_note'] = 'Age, latest snapshots, live jobs and lane manifests are protected even above budget'
    return summary


def _blob_cleanup(store, apply, expiring=()):
    summary = dict(candidate_count=0, candidate_bytes=0, removed_count=0, errors=[])
    # A disk-backed temporary SQLite index keeps manifest reference sets bounded.
    with closing(sqlite3.connect('')) as refs:
        refs.execute('CREATE TABLE refs(digest TEXT PRIMARY KEY,retained INTEGER,links INTEGER)')
        for name in ('snapshots', 'expired-snapshots'):
            parent = _rooted(store.state, store.state / name)
            if not parent.exists():
                continue
            for path in parent.iterdir():
                path = _rooted(store.state, path)
                if not path.is_dir() or not HASH.fullmatch(path.name):
                    raise ValueError('Unrecognized snapshot entry; blob sweep deferred')
                # Interrupted removals keep all content references until retry.
                if (path / 'content').exists():
                    _manifest(store, path, path.name, refs, expiring=path.name in expiring)
        # Lane pins should always resolve to retained snapshots; missing source
        # metadata is a reason to preserve every cache blob, not guess ownership.
        for identity in _lane_pins(store):
            path = store.state / 'snapshots' / identity
            _manifest(store, path, identity, refs)
        for path, info in _walk(store.state, store.state / 'blobs'):
            # Windows directory enumeration can omit the hardlink count.
            info = path.stat()
            if not HASH.fullmatch(path.name) or path.parent.name != path.name[:2]:
                continue
            reference = refs.execute('SELECT retained,links FROM refs WHERE digest=?', (path.name,)).fetchone()
            if reference and reference[0] or info.st_nlink > 1 + (reference[1] if reference else 0):
                continue
            summary['candidate_count'] += 1
            summary['candidate_bytes'] += info.st_size
            if apply:
                try:
                    path.chmod(stat.S_IWRITE | stat.S_IREAD)
                    path.unlink()
                    summary['removed_count'] += 1
                except OSError as error:
                    summary['errors'].append(f'{path.name}: {error}')
    return summary


def _file_digest(path):
    with path.open('r', encoding='utf-8') as stream:
        reader = JsonStream(stream)
        for _ in reader.values():
            pass
        if reader.root_kind != '{':
            raise ValueError('Archived result must be an object')
        return reader.digest


def _compact_database(store, budget_seconds):
    # Only offline workers allow file compaction. An exclusive lock keeps the
    # idle check valid across VACUUM; the deadline bounds any racing submit.
    try:
        with ExclusiveLock(_rooted(store.state, store.state / 'worker.lock')), \
                ExclusiveLock(_rooted(store.state, store.state / 'capture.lock')), _connection(store) as db:
            db.execute('PRAGMA busy_timeout=0')
            db.execute('PRAGMA locking_mode=EXCLUSIVE')
            db.execute('BEGIN EXCLUSIVE')
            if db.execute("SELECT 1 FROM jobs WHERE status IN ('queued','running','cancelling') LIMIT 1").fetchone() or db.execute('SELECT 1 FROM writers LIMIT 1').fetchone():
                db.rollback()
                return 'deferred-concurrent-work'
            db.commit()
            deadline = time.monotonic() + budget_seconds
            db.set_progress_handler(lambda: int(time.monotonic() >= deadline), 1000)
            try:
                db.execute('VACUUM')
                return 'completed'
            except sqlite3.OperationalError as error:
                return 'deferred-time-budget' if 'interrupt' in str(error).lower() else 'deferred-busy'
    except (BlockingIOError, sqlite3.OperationalError):
        return 'deferred-worker-or-database-busy'


def _database_cleanup(store, apply, budget_seconds=2.0):
    """Archive bounded batches and compact only when the worker is offline."""
    summary = dict(candidates=0, candidate_bytes=0, archived=0, evicted=0, vacuum='preview', errors=[])
    with _connection(store) as db:
        row = db.execute("SELECT COUNT(*),COALESCE(SUM(length(result)),0) FROM jobs WHERE status IN ('passed','failed','interrupted','superseded') AND result IS NOT NULL").fetchone()
        summary.update(candidates=row[0], candidate_bytes=row[1])
    if not apply:
        return summary
    try:
        with _connection(store) as db:
            db.execute('PRAGMA busy_timeout=0')
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT 1 FROM jobs WHERE status IN ('queued','running','cancelling') LIMIT 1").fetchone() or db.execute('SELECT 1 FROM writers LIMIT 1').fetchone():
                db.rollback()
                summary['vacuum'] = 'deferred-active-work'
                return summary
            rows = db.execute("SELECT id,length(result) AS size FROM jobs WHERE status IN ('passed','failed','interrupted','superseded') AND result IS NOT NULL ORDER BY finished,id LIMIT 20").fetchall()
            db.commit()
            for row in rows:
                identity = row['id']
                if not JOB.fullmatch(identity):
                    summary['errors'].append('Invalid job identity; result preserved')
                    continue
                folder = _rooted(store.state, store.state / 'results' / identity)
                folder.mkdir(parents=True, exist_ok=True)
                archive = _rooted(store.state, folder / 'result.json')
                temporary = _rooted(store.state, folder / ('.maintenance-result-' + uuid.uuid4().hex + '.json'))
                try:
                    # substr reads a bounded chunk even when one payload is
                    # larger than the worker's address space.
                    with temporary.open('x', encoding='utf-8', newline='') as output:
                        offset = 1
                        while True:
                            text = db.execute('SELECT substr(result,?,?) FROM jobs WHERE id=?', (offset, CHUNK, identity)).fetchone()[0]
                            if not text:
                                break
                            output.write(text)
                            offset += len(text)
                        output.flush()
                        os.fsync(output.fileno())
                    digest = _file_digest(temporary)
                    if archive.exists():
                        if _file_digest(archive) != digest:
                            raise ValueError('Archived result differs; SQLite evidence preserved')
                    else:
                        temporary.rename(archive)
                        summary['archived'] += 1
                    db.execute('BEGIN IMMEDIATE')
                    if (db.execute("SELECT 1 FROM jobs WHERE status IN ('queued','running','cancelling') LIMIT 1").fetchone()
                            or db.execute('SELECT 1 FROM writers LIMIT 1').fetchone()):
                        db.rollback()
                        summary['vacuum'] = 'deferred-concurrent-work'
                        return summary
                    db.execute('UPDATE jobs SET result=NULL WHERE id=? AND length(result)=?', (identity, row['size']))
                    db.commit()
                    summary['evicted'] += 1
                except (OSError, ValueError, sqlite3.Error) as error:
                    db.rollback()
                    summary['errors'].append(f'{identity}: {error}')
                finally:
                    temporary.unlink(missing_ok=True)
        summary['vacuum'] = _compact_database(store, budget_seconds)
    except (BlockingIOError, sqlite3.OperationalError):
        summary['vacuum'] = 'deferred-worker-or-database-busy'
    return summary


def run_maintenance(store, apply=False, now=None, progress=None):
    """Preview or apply retention; nonblocking locks make competing work win."""
    now = time.time() if now is None else now
    report = dict(apply=bool(apply), started=now, status='completed', errors=[])
    config = DEFAULTS | store.all_config()
    notify = progress or (lambda phase: None)
    try:
        with ExclusiveLock(_rooted(store.state, store.state / 'maintenance.lock')):
            # Usage is an observational estimate and can take minutes on large
            # lane caches. It must not hold up edit leases or source capture.
            notify('inventory')
            report['usage'] = storage_inventory(store, progress=notify)
            with ExclusiveLock(_rooted(store.state, store.state / 'capture.lock')):
                # Build eligibility and blob references fresh under one barrier;
                # a capture completed during inventory is included in this scan.
                notify('snapshots')
                report['snapshots'] = _snapshot_cleanup(store, config, apply, now)
                expiring = set(report['snapshots'].pop('_expiring'))
                notify('blobs')
                report['blobs'] = _blob_cleanup(store, apply, expiring if not apply else ())
            notify('database')
            report['database'] = _database_cleanup(store, apply)
            notify('logs')
            from build_logs import retain_logs
            report['logs'] = retain_logs(store, dry_run=not apply, now=now)
            for name in ('snapshots', 'blobs', 'database', 'logs'):
                report['errors'].extend(report.get(name, {}).get('errors', []))
            report['errors'].extend(action['error'] for action in report['logs'].get('actions', []) if action.get('error'))
    except BlockingIOError:
        report.update(status='skipped', reason='Maintenance or capture lock is busy')
    except (OSError, ValueError, sqlite3.Error) as error:
        report.update(status='failed', error=str(error))
        report['errors'].append(str(error))
    report['finished'] = time.time()
    return report


class MaintenanceService:
    """One background job per state; polling status never scans the filesystem."""
    def __init__(self, store, config_provider=None):
        self.store = store
        self.config_provider = config_provider or store.all_config
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._scheduler = None
        self._job_thread = None
        self._job = {'status': 'idle'}

    def start(self):
        with self._lock:
            if self._scheduler is None:
                self._scheduler = threading.Thread(target=self._schedule, name='vdx-maintenance-schedule', daemon=True)
                self._scheduler.start()
        return self

    def _schedule(self):
        # A dashboard restart never triggers an immediate destructive sweep.
        last = time.monotonic()
        while not self._stop.wait(1):
            config = DEFAULTS | self.config_provider()
            interval = float(config['cleanup_interval_minutes']) * 60
            if time.monotonic() - last >= interval:
                last = time.monotonic()
                if config.get('cleanup_enabled', True):
                    self.request(apply=True)

    def status(self):
        with self._lock:
            return dict(self._job)

    def request(self, apply=False):
        with self._lock:
            if self._job_thread is not None and self._job_thread.is_alive():
                return dict(self._job)
            self._job = dict(id='M' + uuid.uuid4().hex, status='queued', apply=bool(apply),
                             created=time.time(), started=None, finished=None, phase='queued', error=None, report=None)
            self._job_thread = threading.Thread(target=self._run, args=(bool(apply),), name='vdx-maintenance', daemon=True)
            result = dict(self._job)
            self._job_thread.start()
            return result

    def _run(self, apply):
        with self._lock:
            self._job.update(status='running', started=time.time())
        def progress(phase):
            with self._lock:
                self._job['phase'] = phase
        try:
            report = run_maintenance(self.store, apply=apply, progress=progress)
            with self._lock:
                self._job.update(status=report['status'], report=report, error=report.get('error'),
                                 finished=time.time(), phase='finished')
        except Exception as error:
            with self._lock:
                self._job.update(status='failed', error=str(error), finished=time.time(), phase='finished')

    def close(self):
        self._stop.set()
        if self._scheduler is not None:
            self._scheduler.join(timeout=2)
