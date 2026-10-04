"""Gradle calls this before its first task; SQLite arbitrates start vs replace."""
import os
from pathlib import Path
import argparse
from store import Store, native_dependency_allowed

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native-task')
    parser.add_argument('--native-root')
    args = parser.parse_args()
    state = Path(os.environ['VDX_BUILD_STATE'])
    if not (state / 'queue.sqlite3').is_file():
        raise SystemExit('Coordinator database missing; task execution denied')
    store = Store(state)
    allowed = store.enter_execution(os.environ['VDX_BUILD_JOB'], os.environ['VDX_BUILD_TOKEN'])
    if args.native_task:
        job = store.job(os.environ['VDX_BUILD_JOB'])
        allowed = (allowed and bool(os.environ.get('VDX_BUILD_NATIVE_PRODUCER')) and job is not None
                   and native_dependency_allowed(job['spec'], args.native_task, args.native_root))
        if not allowed:
            raise SystemExit('Undeclared native task; exact verified producer input contract is required')
    raise SystemExit(0 if allowed else 'Build was replaced or its lease expired; task execution denied')
