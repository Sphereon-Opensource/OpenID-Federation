"""Bounded build-log reads and explicit, terminal-only log retention.

The worker continues appending to results/<build>/build.log without participating
in these locks. Readers, pin changes and retention share a separate per-log lock.
Compressed logs use independently decompressible gzip members and a fixed-width
index, so seeking near the end never replays the whole log.
"""
from __future__ import annotations

import base64
import codecs
from contextlib import contextmanager
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import struct
import time
import uuid
import zlib

from processes import ExclusiveLock, pid_alive

MAX_CHUNK = 256 * 1024
BLOCK_SIZE = 256 * 1024
_HEADER = struct.Struct('>8sIQQ')
_ENTRY = struct.Struct('>QQI')
_MAGIC = b'VDXLOG01'
_TERMINAL = {'passed', 'failed', 'interrupted', 'superseded'}
_ACTIVE = {'queued', 'running', 'cancelling'}
_ID = re.compile(r'B[0-9a-f]{16}\Z')
_COLUMNS = ('id,status,created,started,finished,phase,pid,predecessor,replacement,'
            'restarts,json_extract(spec,\'$.snapshot\') AS snapshot')


class CursorError(ValueError):
    """A malformed or stale cursor requires the client to reload its log window."""


def _job(store, job_id, db=None):
    if not isinstance(job_id, str) or not _ID.fullmatch(job_id):
        raise ValueError('Invalid build ID')
    if db is None:
        with store.connect() as connection:
            return _job(store, job_id, connection)
    row = db.execute(f'SELECT {_COLUMNS} FROM jobs WHERE id=?', (job_id,)).fetchone()
    if row is None:
        raise LookupError('Unknown build')
    return dict(row)


def _safe(root, *parts):
    root = Path(root).absolute()
    path = root.joinpath(*parts)
    try:
        relative = path.relative_to(root)
    except ValueError as exc:
        raise ValueError('Log path escapes coordinator state') from exc
    if '..' in relative.parts:
        raise ValueError('Log path escapes coordinator state')
    # Check every ancestor, including junctions on Windows. Never follow a
    # redirected results/log-state directory when reading or removing a file.
    for candidate in [*reversed(root.parents), root,
                      *(root.joinpath(*relative.parts[:n]) for n in range(1, len(relative.parts) + 1))]:
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Symlink or reparse point in log path')
    return path


def _paths(store, job_id):
    return {name: _safe(store.state, 'results', job_id, filename) for name, filename in
            [('plain', 'build.log'), ('gzip', 'build.log.gz'), ('index', 'build.log.idx')]} | {
                'state': _safe(store.state, 'log-state', job_id + '.json'),
                'lock': _safe(store.state, 'log-state', job_id + '.lock')}


@contextmanager
def _locked(store, job_id):
    _job(store, job_id)  # Unknown IDs must not create directories or lock files.
    paths = _paths(store, job_id)
    paths['lock'].parent.mkdir(parents=True, exist_ok=True)
    _paths(store, job_id)
    with ExclusiveLock(paths['lock']):
        yield paths


def _read_state(path):
    if not path.exists():
        return {'pinned': False}
    if path.stat().st_size > 16384:
        raise ValueError('Invalid log metadata')
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise ValueError('Invalid log metadata') from exc
    if not isinstance(value, dict) or type(value.get('pinned', False)) is not bool:
        raise ValueError('Invalid log metadata')
    return value


def _sync_directory(path):
    if os.name != 'nt':
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def _write_state(store, paths, value):
    target = paths['state']
    temporary = _safe(store.state, 'log-state', target.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with open(temporary, 'x', encoding='utf-8') as stream:
            json.dump(value, stream, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        _paths(store, target.name[:-5])
        os.replace(temporary, target)
        _sync_directory(target.parent)
    finally:
        temporary.unlink(missing_ok=True)


def _generation(info):
    return hashlib.sha256(f'{info.st_dev}:{info.st_ino}:{getattr(info, "st_birthtime_ns", 0)}'.encode()).hexdigest()[:24]


def _fingerprint(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _lineage(store, job):
    chain, seen = [job], {job['id']}
    with store.connect() as db:
        while chain[0].get('predecessor') and len(chain) < 128:
            candidate = chain[0]['predecessor']
            if candidate in seen:
                break
            try:
                previous = _job(store, candidate, db)
            except (ValueError, LookupError):
                break
            chain.insert(0, previous)
            seen.add(candidate)
        while len(chain) < 128:
            candidate = chain[-1].get('replacement')
            if not candidate:
                row = db.execute('SELECT id FROM jobs WHERE predecessor=? ORDER BY created,id LIMIT 1',
                                 (chain[-1]['id'],)).fetchone()
                candidate = row['id'] if row else None
            if not candidate:
                break
            if candidate in seen:
                break
            try:
                following = _job(store, candidate, db)
            except (ValueError, LookupError):
                break
            chain.append(following)
            seen.add(candidate)
    return [{key: item.get(key) for key in ('id', 'status', 'snapshot', 'predecessor', 'replacement', 'restarts')}
            for item in chain]


def _source(paths, state):
    if 'expired' in state:
        return None
    if paths['plain'].is_file():
        info = paths['plain'].stat()
        return {'kind': 'plain', 'size': info.st_size, 'generation': _generation(info),
                'last_output': info.st_mtime}
    archive = state.get('archive')
    if archive and paths['gzip'].is_file() and paths['index'].is_file():
        with open(paths['index'], 'rb') as stream:
            header = stream.read(_HEADER.size)
        if len(header) != _HEADER.size:
            raise ValueError('Invalid compressed log index')
        magic, block, size, count = _HEADER.unpack(header)
        if (magic != _MAGIC or block != BLOCK_SIZE or count != (size + block - 1) // block or
                paths['index'].stat().st_size != _HEADER.size + count * _ENTRY.size or
                archive.get('size') != size):
            raise ValueError('Invalid compressed log index')
        return {'kind': 'gzip', 'size': size, 'generation': archive['generation'],
                'last_output': archive['last_output']}
    return None


def _metadata(store, job, paths, state, source, *, lineage=True):
    missing = 'queued' if job['status'] == 'queued' else (
        'not_started' if job['status'] in ('running', 'cancelling') and not job.get('pid') else 'unavailable')
    value = {key: job.get(key) for key in ('id', 'snapshot', 'status', 'predecessor', 'replacement',
                                         'created', 'started', 'finished', 'phase')}
    value.update(job_id=job['id'], pinned=state.get('pinned', False),
                 log_state='expired' if 'expired' in state else ('available' if source else missing),
                 last_output=source['last_output'] if source else state.get('last_output'),
                 size=source['size'] if source else state.get('size', 0),
                 compressed=bool(source and source['kind'] == 'gzip'))
    if lineage:
        value['attempt_chain'] = _lineage(store, job)
    return value


def _read_bytes(paths, source, offset, length):
    if length <= 0:
        return b''
    if length > MAX_CHUNK:
        raise ValueError('Log read exceeds maximum chunk')
    if source['kind'] == 'plain':
        with open(paths['plain'], 'rb') as stream:
            stream.seek(offset)
            return stream.read(length)
    result = bytearray()
    with open(paths['index'], 'rb') as index, open(paths['gzip'], 'rb') as archive:
        while length and offset < source['size']:
            member, inside = divmod(offset, BLOCK_SIZE)
            index.seek(_HEADER.size + member * _ENTRY.size)
            record = index.read(_ENTRY.size)
            if len(record) != _ENTRY.size:
                raise ValueError('Invalid compressed log index')
            position, compressed, raw_length = _ENTRY.unpack(record)
            expected = min(BLOCK_SIZE, source['size'] - member * BLOCK_SIZE)
            if raw_length != expected or not 0 < compressed <= BLOCK_SIZE + 1024:
                raise ValueError('Invalid compressed log member')
            archive.seek(position)
            data = archive.read(compressed)
            decoder = zlib.decompressobj(31)
            try:
                raw = decoder.decompress(data, raw_length + 1)
            except zlib.error as exc:
                raise ValueError('Invalid compressed log member') from exc
            if len(raw) != raw_length or not decoder.eof or decoder.unused_data:
                raise ValueError('Invalid compressed log member')
            take = min(length, raw_length - inside)
            result.extend(raw[inside:inside + take])
            offset += take
            length -= take
    return bytes(result)


def _anchor(paths, source, offset):
    return hashlib.sha256(_read_bytes(paths, source, max(0, offset - 64), min(64, offset))).hexdigest()[:24]


def _cursor(job_id, paths, source, offset):
    payload = [job_id, source['generation'], offset, _anchor(paths, source, offset)]
    return base64.urlsafe_b64encode(json.dumps(payload, separators=(',', ':')).encode()).decode().rstrip('=')


def _offset(value, job_id, paths, source):
    if not isinstance(value, str) or len(value) > 512:
        raise CursorError('Invalid log cursor')
    try:
        payload = json.loads(base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True))
    except (ValueError, UnicodeError) as exc:
        raise CursorError('Invalid log cursor') from exc
    if (not isinstance(payload, list) or len(payload) != 4 or payload[0] != job_id or
            payload[1] != source['generation'] or type(payload[2]) is not int or
            not 0 <= payload[2] <= source['size'] or payload[3] != _anchor(paths, source, payload[2])):
        raise CursorError('Log cursor no longer matches this build log; reload the log')
    return payload[2]


def _limit(value):
    if type(value) is not int or not 4 <= value <= MAX_CHUNK:
        raise ValueError(f'Log chunk must be between 4 and {MAX_CHUNK} bytes')
    return value


def read_chunk(store, job_id, cursor=None, before=None, limit=65536, tail=False):
    """Read raw text for a client to escape; cursors represent UTF-8 byte boundaries."""
    limit = _limit(limit)
    if type(tail) is not bool or sum((cursor is not None, before is not None, tail)) > 1:
        raise ValueError('Choose cursor, before, or tail')
    with _locked(store, job_id) as paths:
        job, state = _job(store, job_id), _read_state(paths['state'])
        source = _source(paths, state)
        result = _metadata(store, job, paths, state, source)
        result.update(text='', cursor=None, before=None, start=0, end=0, has_more=False, has_older=False)
        if source is None:
            return result
        end = _offset(before, job_id, paths, source) if before is not None else source['size']
        start = max(0, end - limit) if before is not None or tail else (
            _offset(cursor, job_id, paths, source) if cursor is not None else 0)
        data = _read_bytes(paths, source, start, min(limit, end - start))
        # A backward/tail window can begin inside a code point; omit its partial
        # prefix. Forward cursors always stop before an incomplete suffix.
        if (before is not None or tail) and start:
            skip = 0
            while skip < min(3, len(data)) and data[skip] & 0xC0 == 0x80:
                skip += 1
            start += skip
            data = data[skip:]
        decoder = codecs.getincrementaldecoder('utf-8')('replace')
        at_completed_eof = start + len(data) == source['size'] and job['status'] in _TERMINAL
        text = decoder.decode(data, final=at_completed_eof)
        end = start + len(data) - len(decoder.getstate()[0])
        result.update(text=text, cursor=_cursor(job_id, paths, source, end),
                      before=_cursor(job_id, paths, source, start), start=start, end=end,
                      has_more=end < source['size'], has_older=start > 0)
        return result


def error_preview(store, job_id):
    """Extract a small actual-error excerpt from a bounded tail, never a full scan."""
    chunk = read_chunk(store, job_id, tail=True, limit=65536)
    preview = {'state': chunk['log_state'], 'text': '', 'truncated': False, 'source': 'tail'}
    if chunk['log_state'] != 'available':
        return {'job_id': job_id, 'error_preview': preview}
    # Terminal escape sequences must not obscure matching or enter the summary.
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', chunk['text'])
    lines = text.splitlines()
    marker = re.compile(r'^\s*\* What went wrong:|^\s*e: |\berror(?:\s|:)|'
                        r'\b(?:[\w.]*Exception|[\w.]*Error):|^Caused by:', re.I)
    matches = [index for index, line in enumerate(lines) if marker.search(line)]
    if matches:
        # Gradle's failure summary is usually more useful than an earlier warning.
        sections = [index for index in matches if '* What went wrong:' in lines[index]]
        start = sections[0] if sections else matches[0]
        excerpt = []
        for line in lines[start:start + 12]:
            if excerpt and line.startswith(('* Try:', '* Get more help', '===')):
                break
            if line.strip():
                excerpt.append(line)
        preview.update(state='available', text='\n'.join(excerpt).strip()[:2400],
                       truncated=bool(chunk['has_older'] or start + len(excerpt) < len(lines)
                                      or len('\n'.join(excerpt)) > 2400))
    else:
        excerpt = '\n'.join(lines[-8:]).strip()
        preview.update(state='no_match', text=excerpt[-2400:],
                       truncated=bool(chunk['has_older'] or len(lines) > 8 or len(excerpt) > 2400))
    return {'job_id': job_id, 'error_preview': preview}


@contextmanager
def download(store, job_id, chunk_size=65536):
    """Yield (metadata, byte iterator); hold reader protection until context exit."""
    chunk_size = _limit(chunk_size)
    with _locked(store, job_id) as paths:
        job, state = _job(store, job_id), _read_state(paths['state'])
        source = _source(paths, state)
        metadata = _metadata(store, job, paths, state, source)
        if source is None:
            raise FileNotFoundError('Build log is ' + metadata['log_state'])

        def chunks():
            offset = 0
            while offset < source['size']:
                data = _read_bytes(paths, source, offset, min(chunk_size, source['size'] - offset))
                if not data:
                    raise ValueError('Build log changed during download')
                yield data
                offset += len(data)

        yield metadata, chunks()


def set_pinned(store, job_id, pinned):
    if type(pinned) is not bool:
        raise ValueError('pinned must be boolean')
    with _locked(store, job_id) as paths:
        state = _read_state(paths['state'])
        if 'expired' in state and pinned:
            raise ValueError('An expired log cannot be pinned')
        state['pinned'] = pinned
        _write_state(store, paths, state)
        return _metadata(store, _job(store, job_id), paths, state, _source(paths, state))


def _protection(job, state):
    if job['status'] not in _TERMINAL:
        return 'active' if job['status'] in _ACTIVE else 'unknown_status'
    if state.get('pinned'):
        return 'pinned'
    if job.get('pid') and pid_alive(job['pid']):
        return 'process_alive'
    return None


def _jobs(store):
    with store.connect() as db:
        return [dict(row) for row in db.execute(f'SELECT {_COLUMNS} FROM jobs ORDER BY created,id')]


def _settings(store):
    config = store.all_config()
    result = {key: config.get(key, default) for key, default in
              [('log_retention_success_days', 7), ('log_retention_failure_days', 30), ('log_budget_gb', 2)]}
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in result.values()):
        raise ValueError('Invalid log retention configuration')
    return result


def _inspect_log(store, job, now, settings):
    with _locked(store, job['id']) as paths:
        job, state = _job(store, job['id']), _read_state(paths['state'])
        source = _source(paths, state)
        item = _metadata(store, job, paths, state, source, lineage=False)
        item['stored_bytes'] = sum(path.stat().st_size for name, path in paths.items()
                                   if name in ('plain', 'gzip', 'index') and path.is_file())
        item['protected'] = _protection(job, state)
        item['finished'] = job.get('finished') or job.get('created') or now
        days = settings['log_retention_success_days' if job['status'] == 'passed' else 'log_retention_failure_days']
        item['age_expired'] = job['status'] in _TERMINAL and now - item['finished'] >= days * 86400
        item['compressible'] = bool(source and source['kind'] == 'plain' and not item['protected'])
        return item


def storage_usage(store, now=None):
    """Return bounded metadata and a conservative preview; never change log bytes."""
    now = time.time() if now is None else now
    settings = _settings(store)
    logs = []
    for job in _jobs(store):
        try:
            logs.append(_inspect_log(store, job, now, settings))
        except (ValueError, OSError) as exc:
            logs.append({'id': job['id'], 'job_id': job['id'], 'status': job['status'],
                         'stored_bytes': 0, 'protected': 'busy' if isinstance(exc, BlockingIOError) else 'unavailable',
                         'log_state': 'unavailable', 'error': str(exc), 'age_expired': False, 'compressible': False})
    # Account for orphaned build directories, but never open their log content or
    # make them eligible for mutation: only the database can authorize a build.
    known = {item['id'] for item in logs}
    try:
        results = _safe(store.state, 'results')
        for directory in results.iterdir() if results.exists() else ():
            if directory.name in known or not _ID.fullmatch(directory.name):
                continue
            try:
                sizes = [_safe(store.state, 'results', directory.name, name).stat().st_size
                         for name in ('build.log', 'build.log.gz', 'build.log.idx')
                         if _safe(store.state, 'results', directory.name, name).is_file()]
                size, error = sum(sizes), None
            except (OSError, ValueError) as exc:
                size, error = 0, str(exc)
            logs.append({'id': directory.name, 'job_id': directory.name, 'status': 'unknown',
                         'stored_bytes': size, 'protected': 'unknown_job', 'log_state': 'unavailable',
                         'error': error, 'age_expired': False, 'compressible': False})
    except (OSError, ValueError):
        # Known jobs above already expose unavailable paths. Never traverse a
        # redirected or unreadable results root to try to improve the estimate.
        pass
    total = sum(item['stored_bytes'] for item in logs)
    completed = sum(item['stored_bytes'] for item in logs if item['status'] in _TERMINAL)
    protected = sum(item['stored_bytes'] for item in logs if item['protected'])
    remaining = completed
    actions, removed = [], set()
    eligible = sorted((item for item in logs if not item['protected'] and item['stored_bytes']),
                      key=lambda item: (item['finished'], item['id']))
    for item in eligible:
        if item['age_expired'] or item['log_state'] == 'expired':
            actions.append({'id': item['id'], 'action': 'expire', 'reason': 'age', 'bytes': item['stored_bytes']})
            remaining -= item['stored_bytes']
            removed.add(item['id'])
    budget = int(settings['log_budget_gb'] * 2**30)
    for item in eligible:
        if item['id'] in removed:
            continue
        if item['compressible']:
            actions.append({'id': item['id'], 'action': 'compress', 'reason': 'completed'})
        if remaining > budget:
            actions.append({'id': item['id'], 'action': 'expire', 'reason': 'budget',
                            'bytes': item['stored_bytes'], 'conditional_on_compression': True})
            remaining -= item['stored_bytes']
    return {'logs': logs, 'total_bytes': total, 'completed_bytes': completed, 'protected_bytes': protected,
            'budget_bytes': budget, 'budget_unmet': remaining > budget,
            'unknown_usage': any(item['protected'] in ('busy', 'unavailable') or item.get('error') for item in logs),
            'actions': actions, 'settings': settings, 'dry_run': True}


def _unchanged_terminal(store, job, state, db):
    current = _job(store, job['id'], db)
    return (not _protection(current, state) and
            all(current.get(key) == job.get(key) for key in ('status', 'finished', 'started', 'pid', 'restarts', 'snapshot')))


def _compress(store, job_id):
    with _locked(store, job_id) as paths:
        job, state = _job(store, job_id), _read_state(paths['state'])
        source = _source(paths, state)
        if _protection(job, state) or not source or source['kind'] != 'plain':
            return False
        original = paths['plain'].stat()
        token = uuid.uuid4().hex
        archive_temp = _safe(store.state, 'results', job_id, 'build.log.' + token + '.gz.tmp')
        index_temp = _safe(store.state, 'results', job_id, 'build.log.' + token + '.idx.tmp')
        try:
            with open(paths['plain'], 'rb') as raw, open(archive_temp, 'xb') as archive, open(index_temp, 'xb') as index:
                count = (source['size'] + BLOCK_SIZE - 1) // BLOCK_SIZE
                index.write(_HEADER.pack(_MAGIC, BLOCK_SIZE, source['size'], count))
                remaining = source['size']
                while remaining:
                    chunk = raw.read(min(BLOCK_SIZE, remaining))
                    if not chunk:
                        raise ValueError('Completed log changed during compression')
                    compressed = gzip.compress(chunk, compresslevel=6, mtime=0)
                    index.write(_ENTRY.pack(archive.tell(), len(compressed), len(chunk)))
                    archive.write(compressed)
                    remaining -= len(chunk)
                for stream in (archive, index):
                    stream.flush()
                    os.fsync(stream.fileno())
            # Compression held only the per-log lock. The database transaction is
            # short and closes the restart/pin race immediately before publishing.
            with store.transaction() as db:
                _paths(store, job_id)
                if (not _unchanged_terminal(store, job, state, db) or
                        _fingerprint(paths['plain'].stat()) != _fingerprint(original)):
                    return False
                os.replace(archive_temp, paths['gzip'])
                os.replace(index_temp, paths['index'])
                _sync_directory(paths['gzip'].parent)
                state['archive'] = {key: source[key] for key in ('size', 'generation', 'last_output')}
                _write_state(store, paths, state)
                paths['plain'].unlink()
                _sync_directory(paths['plain'].parent)
            return True
        finally:
            archive_temp.unlink(missing_ok=True)
            index_temp.unlink(missing_ok=True)


def _expire(store, job_id, now):
    with _locked(store, job_id) as paths:
        job, state = _job(store, job_id), _read_state(paths['state'])
        source = _source(paths, state)
        if _protection(job, state):
            return False
        with store.transaction() as db:
            _paths(store, job_id)
            if not _unchanged_terminal(store, job, state, db):
                return False
            state.update(expired=state.get('expired') or now,
                         size=source['size'] if source else state.get('size', 0),
                         last_output=source['last_output'] if source else state.get('last_output'))
            # Persist this marker before deleting data so absence after a crash is
            # explicitly expired, and an interrupted removal can be retried.
            _write_state(store, paths, state)
            for name in ('plain', 'gzip', 'index'):
                paths[name].unlink(missing_ok=True)
            if paths['plain'].parent.exists():
                _sync_directory(paths['plain'].parent)
        return True


def retain_logs(store, dry_run=True, now=None):
    """Preview by default; apply compresses first, then expires by age and budget."""
    if type(dry_run) is not bool:
        raise ValueError('dry_run must be boolean')
    now = time.time() if now is None else now
    preview = storage_usage(store, now)
    if dry_run:
        return preview
    performed = []

    def perform(item, action, reason):
        try:
            applied = _compress(store, item['id']) if action == 'compress' else _expire(store, item['id'], now)
            performed.append({'id': item['id'], 'action': action, 'reason': reason,
                              'applied': applied, 'skipped': None if applied else 'state_changed_or_protected'})
        except (OSError, ValueError, LookupError) as exc:
            performed.append({'id': item['id'], 'action': action, 'reason': reason,
                              'applied': False, 'error': str(exc)})

    for item in preview['logs']:
        if item['protected'] or not item['stored_bytes']:
            continue
        if item['age_expired'] or item['log_state'] == 'expired':
            perform(item, 'expire', 'age')
        elif item['compressible']:
            perform(item, 'compress', 'completed')
    compacted = storage_usage(store, now)
    remaining = compacted['completed_bytes']
    for item in sorted((item for item in compacted['logs'] if not item['protected'] and item['stored_bytes']),
                       key=lambda item: (item['finished'], item['id'])):
        if remaining <= compacted['budget_bytes']:
            break
        before_count = len(performed)
        perform(item, 'expire', 'budget')
        if len(performed) > before_count and performed[-1]['applied']:
            remaining -= item['stored_bytes']
    result = storage_usage(store, now)
    result.update(actions=performed, dry_run=False, budget_unmet=result['completed_bytes'] > result['budget_bytes'])
    return result
