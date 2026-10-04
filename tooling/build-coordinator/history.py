"""Per-task run history: duration, peak memory and health for scheduling and display.

A task key is the job's requested work: sorted task paths with their test
filters. `:service-monolith:jvmTest` and `:service-monolith:jvmTest` limited to
`*Smoke*` behave very differently, so they are tracked separately.
"""
import json
import math
import statistics
import time

LONG_RUNNING_MINUTES = 20
UNKNOWN_DURATION_MINUTES = 10
MIN_RUNS_FOR_ESTIMATE = 3
MEMORY_MARGIN = 1.25
MEMORY_FLOOR_GB = 4


def task_key(spec):
    parts = []
    for task in (spec or {}).get('tasks', []):
        path = task.get('path') if isinstance(task, dict) else str(task)
        tests = sorted(task.get('tests') or []) if isinstance(task, dict) else []
        parts.append(path + (f"[{','.join(tests)}]" if tests else ''))
    return ' '.join(sorted(parts))


def ensure_schema(db):
    """Create the history table; backfill it from finished jobs the first time."""
    created = not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='task_runs'").fetchone()
    db.execute('''CREATE TABLE IF NOT EXISTS task_runs(
                      job_id TEXT PRIMARY KEY, task_key TEXT NOT NULL, status TEXT NOT NULL,
                      queued_at REAL, started REAL NOT NULL, finished REAL NOT NULL, peak_gb REAL)''')
    db.execute('CREATE INDEX IF NOT EXISTS task_runs_key_finished ON task_runs(task_key,finished)')
    columns = {row['name'] for row in db.execute('PRAGMA table_info(jobs)')}
    if 'peak_gb' not in columns:
        db.execute('ALTER TABLE jobs ADD COLUMN peak_gb REAL')
    if created:
        for row in db.execute("SELECT id,spec,status,created,started,finished,peak_gb FROM jobs "
                              "WHERE status IN ('passed','failed') AND started IS NOT NULL AND finished IS NOT NULL").fetchall():
            record(db, row)


def record(db, job):
    """Store one finished run. Interrupted and superseded runs say nothing about the task."""
    job = dict(job)
    if job.get('status') not in ('passed', 'failed') or not job.get('started') or not job.get('finished'):
        return
    spec = job['spec'] if isinstance(job['spec'], dict) else json.loads(job['spec'] or '{}')
    key = task_key(spec)
    if not key:
        return
    db.execute('INSERT OR REPLACE INTO task_runs(job_id,task_key,status,queued_at,started,finished,peak_gb) '
               'VALUES(?,?,?,?,?,?,?)', (job['id'], key, job['status'], job.get('created'), job['started'],
                                        job['finished'], job.get('peak_gb')))


def _percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))]


def summarize(rows):
    """Summary of runs ordered newest first."""
    if not rows:
        return None
    minutes = [(r['finished'] - r['started']) / 60 for r in rows[:50] if r['status'] == 'passed'] or \
              [(r['finished'] - r['started']) / 60 for r in rows[:50]]
    peaks = [r['peak_gb'] for r in rows[:25] if r['peak_gb']]
    outcomes = [r['status'] for r in rows[:25]]
    return {
        'runs': len(rows),
        'median_minutes': round(statistics.median(minutes), 1),
        'p90_minutes': round(_percentile(minutes, 0.9), 1),
        'peak_gb': round(_percentile(peaks, 0.9), 1) if peaks else None,
        'peak_samples': len(peaks),
        'failures': {str(n): outcomes[:n].count('failed') for n in (5, 10, 25)},
        'recent': outcomes[:10],
        'last_finished': rows[0]['finished'],
        'trend': [round((r['finished'] - r['started']) / 60, 1) for r in reversed(rows[:20])],
    }


def stats_for(db, key):
    rows = [dict(r) for r in db.execute('SELECT * FROM task_runs WHERE task_key=? ORDER BY finished DESC LIMIT 200', (key,))]
    return summarize(rows)


def all_stats(db, limit=200):
    keys = [r['task_key'] for r in db.execute(
        'SELECT task_key,MAX(finished) AS last FROM task_runs GROUP BY task_key ORDER BY last DESC LIMIT ?', (limit,))]
    return {key: stats_for(db, key) for key in keys}


def expected_minutes(stats):
    return stats['median_minutes'] if stats and stats['runs'] >= MIN_RUNS_FOR_ESTIMATE else None


def is_long(stats):
    minutes = expected_minutes(stats)
    return minutes is not None and minutes >= LONG_RUNNING_MINUTES


def sized_memory_gb(spec, stats):
    """A smaller reservation for auto-sized jobs whose peak memory is known.

    Never above the original reservation, and only when enough runs recorded a peak.
    """
    if not spec.get('memory_auto') or not stats or stats['peak_samples'] < MIN_RUNS_FOR_ESTIMATE:
        return None
    sized = max(MEMORY_FLOOR_GB, math.ceil(stats['peak_gb'] * MEMORY_MARGIN + 1))
    return sized if sized < float(spec.get('memory_gb', 0)) else None


def admission_score(job, stats, now=None):
    """Lower goes first: expected minutes minus minutes already waited.

    Shorter work tends to go first, but every minute a job waits counts, so a
    long job is never starved by a stream of short ones.
    """
    now = now or time.time()
    minutes = expected_minutes(stats)
    return (UNKNOWN_DURATION_MINUTES if minutes is None else minutes) - (now - job['created']) / 60
