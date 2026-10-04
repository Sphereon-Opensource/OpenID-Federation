"""Transactional request queue. Clients never own or terminate build processes."""
from contextlib import ExitStack, contextmanager
import hashlib
import hmac
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import time
import uuid

try:
    from processes import available_memory_gb
except ImportError:  # pragma: no cover - direct package embedding fallback
    available_memory_gb = None

ACTIVE = ('running', 'cancelling')
TERMINAL = ('passed', 'failed', 'interrupted', 'superseded')
DEFAULT_MEMORY_GB = 45
LOW_MEMORY_THRESHOLD_GB = 32
LOW_MEMORY_HEADROOM_GB = 4
MAX_ADMISSION_HEADROOM_GB = 16
ADMISSION_HEADROOM_FRACTION = 0.10
DEFAULT_CONFIG = {
    'slots': 1,
    'tier_sizing': False,
    'include_swap': True,
    'swap_weight': 0.75,
    'threshold_test_gb': 8.0,
    'headroom_test_gb': 3.0,
    'threshold_build_gb': 16.0,
    'headroom_build_gb': 4.0,
    'threshold_heavy_gb': 28.0,
    'headroom_heavy_gb': 6.0,
    'headroom_explicit_gb': 16.0,
    'whitelist_patterns': ['*idea*', '*jetbrains*', '*language-server*'],
    'test_tier_exempt_external_blockers': True,
    'reap_idle_gradle_daemons': True,
    'reap_min_idle_seconds': 120,
    'infrastructure_retries': 1,
    'log_retention_success_days': 7,
    'log_retention_failure_days': 30,
    'log_budget_gb': 2,
    'cleanup_enabled': True,
    'cleanup_interval_minutes': 60,
    'snapshot_retention_days': 7,
    'snapshot_keep_latest': 3,
    'snapshot_budget_gb': 20,
}


def validate_config(mapping):
    """Validate an entire settings update before any value is persisted."""
    if not isinstance(mapping, dict) or set(mapping) - set(DEFAULT_CONFIG):
        raise ValueError('Unknown configuration fields')
    result = {}
    for key, value in mapping.items():
        if key == 'slots':
            if value != 'auto' and (type(value) is not int or not 1 <= value <= 8):
                raise ValueError('slots must be auto or an integer between 1 and 8')
        elif isinstance(DEFAULT_CONFIG[key], bool):
            if type(value) is not bool:
                raise ValueError(f'{key} must be boolean')
        elif key == 'whitelist_patterns':
            if (not isinstance(value, list) or len(value) > 128 or
                    any(not isinstance(pattern, str) or not pattern.strip() or len(pattern) > 256 or
                        any(ord(char) < 32 for char in pattern) for pattern in value)):
                raise ValueError('whitelist_patterns must contain at most 128 nonempty patterns of at most 256 characters')
            value = list(value)
        elif key in ('log_retention_success_days', 'log_retention_failure_days', 'snapshot_retention_days',
                     'snapshot_keep_latest', 'cleanup_interval_minutes'):
            minimum, maximum = ((1, 10080) if key == 'cleanup_interval_minutes' else
                                (1, 1000) if key == 'snapshot_keep_latest' else (1, 3650))
            if type(value) is not int or not minimum <= value <= maximum:
                raise ValueError(f'{key} must be an integer between {minimum} and {maximum}')
        elif key in ('log_budget_gb', 'snapshot_budget_gb'):
            if type(value) not in (int, float) or not 0.01 <= value <= 10240 or not math.isfinite(value):
                raise ValueError(f'{key} must be between 0.01 and 10240 GiB')
        else:
            minimum, maximum = ((0, 1) if key == 'swap_weight' else
                                (0, 86400) if key == 'reap_min_idle_seconds' else
                                (1, 96) if key.startswith('threshold_') else (0, 96))
            if (type(value) not in (int, float) or not minimum <= value <= maximum or
                    not math.isfinite(value)):
                raise ValueError(f'{key} must be a finite number between {minimum} and {maximum}')
        result[key] = value
    return result
TASK_NAMES = {'assemble', 'assembleJsPackage', 'build', 'check', 'classes', 'testClasses', 'jar', 'jvmJar', 'fatJar', 'shadowJar',
              'nativeCompile', 'verifyNativePreparationInputs',
              'prepareWorkspaceArtifacts',
              'test', 'jvmTest', 'jsNodeTest', 'jsBrowserTest', 'wasmJsNodeTest', 'wasmJsBrowserTest',
              'allTests', 'desktopTest', 'generateProto', 'generateTestProto', 'projectGraphJvmMain',
              'projectGraphMain', 'compileKotlinJvm', 'compileTestKotlinJvm', 'compileKotlinJs',
              'compileTestKotlinJs', 'compileKotlinWasmJs', 'compileTestKotlinWasmJs',
              'compileJava', 'compileTestJava', 'processResources', 'processTestResources', 'jvmProcessResources'}
TEST_TASK_NAMES = {
    'test', 'jvmTest', 'jsNodeTest', 'jsBrowserTest', 'wasmJsNodeTest',
    'wasmJsBrowserTest', 'allTests', 'check', 'build', 'testClasses',
    'compileTestKotlinJvm', 'compileTestKotlinJs', 'compileTestKotlinWasmJs',
}


def native_request_contract(spec):
    """Bound the native route; byte/catalog/producer proof is checked by worker."""
    native = [task for task in spec.get('tasks', [])
              if task.get('path', '').split(':')[-1] in ('nativeCompile', 'verifyNativePreparationInputs')]
    if not native:
        return None
    if spec.get('root', '.') != '.':
        raise ValueError('Native preparation requires the infra root')
    producers = set()
    for task in native:
        path = task['path']
        if not re.fullmatch(r':[a-z][a-z0-9-]*-native:(nativeCompile|verifyNativePreparationInputs)', path) or task.get('tests'):
            raise ValueError('Native preparation requires one exact sidecar producer without test filters')
        producers.add(path.rsplit(':', 1)[0] + ':nativeCompile')
    if len(producers) != 1 or len(native) != len(spec['tasks']):
        raise ValueError('Native preparation requires one finite producer per invocation')
    producer = next(iter(producers))
    module = producer.split(':')[1]
    prefix = f'services/{module}/build/native/nativeCompile/'
    environment = spec.get('environment', {})
    required = ('WORKSPACE_NATIVE_INPUTS_FILE', 'WORKSPACE_NATIVE_INPUTS_SHA256', 'WORKSPACE_NATIVE_PRODUCT',
                'WORKSPACE_NATIVE_ROLE', 'WORKSPACE_NATIVE_TARGET', 'GRAALVM_HOME', 'WORKSPACE_SOURCE_MODULES')
    if any(not isinstance(environment.get(key), str) or not environment[key] for key in required):
        raise ValueError('Native preparation requires explicit descriptor, catalog, target and pinned toolchain inputs')
    for key in ('WORKSPACE_NATIVE_INPUTS_FILE', 'GRAALVM_HOME'):
        if not Path(environment[key]).is_absolute():
            raise ValueError('Native descriptor/toolchain paths must be absolute')
    if (not re.fullmatch(r'[a-f0-9]{64}', environment['WORKSPACE_NATIVE_INPUTS_SHA256'])
            or not re.fullmatch(r'[a-z][a-z0-9-]*', environment['WORKSPACE_NATIVE_PRODUCT'])
            or not re.fullmatch(r'[a-z][a-z0-9-]*', environment['WORKSPACE_NATIVE_ROLE'])
            or environment['WORKSPACE_NATIVE_TARGET'] not in {'linux-amd64', 'linux-arm64', 'windows-amd64', 'windows-arm64'}
            or environment['WORKSPACE_SOURCE_MODULES'] != module):
        raise ValueError('Invalid explicit native identity/target/source selection')
    for key in ('USE_LOCAL_VDX', 'USE_LOCAL_EDK', 'USE_LOCAL_IDK', 'USE_LOCAL_GRADLE_BUILD_SUPPORT'):
        if environment.get(key) != 'false':
            raise ValueError('Accepted-JAR native preparation requires artifact-mode product/tool dependencies')
    expected_outputs = [prefix + '**'] if any(task['path'].endswith(':nativeCompile') for task in native) else []
    if spec.get('artifacts', []) != expected_outputs:
        raise ValueError('Native preparation requires its complete finite executable/manifest/library output inventory')
    return dict(producer=producer, module=module, outputPrefix=prefix,
                product=environment['WORKSPACE_NATIVE_PRODUCT'], role=environment['WORKSPACE_NATIVE_ROLE'])


def native_dependency_allowed(spec, task, root):
    """An ordinary task cannot gain a native compiler through its dependencies."""
    contract = native_request_contract(spec)
    return bool(contract and root == '.' and task in (contract['producer'],
        contract['producer'].removesuffix('nativeCompile') + 'verifyNativePreparationInputs'))


class Connection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'))


def identity(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def classify_task_tier(spec):
    """Classify request into 'test', 'build', or 'heavy'.

    'test': Lightweight tasks (jvmTest, test, jsNodeTest, check, classes) on <= 4 modules, no packaging.
    'heavy': Native image tasks, fatJar, shadowJar, assemble, or native-image artifacts.
    'build': Multi-module compilation, code generation, or full project builds.
    """
    if not isinstance(spec, dict):
        return 'build'
    tasks = spec.get('tasks', [])
    artifacts = spec.get('artifacts', [])
    for a in artifacts:
        lower_a = a.lower()
        if any(w in lower_a for w in ('native', 'fat', 'shadow')) or lower_a.endswith(('.so', '.dylib', '.dll', '.exe')):
            return 'heavy'
    has_heavy_task = False
    has_non_test_task = False
    test_task_names = {'test', 'jvmtest', 'jsnodetest', 'jsbrowsertest', 'wasmjsnodetest',
                       'wasmjsbrowsertest', 'desktoptest', 'check', 'classes', 'testclasses',
                       'processresources', 'processtestresources'}
    for t in tasks:
        path = t.get('path', '') if isinstance(t, dict) else str(t)
        base = path.split(':')[-1].lower()
        if any(w in base for w in ('assemble', 'shadowjar', 'fatjar', 'native', 'alltests', 'build')):
            has_heavy_task = True
            break
        if base not in test_task_names:
            has_non_test_task = True
    if has_heavy_task:
        return 'heavy'
    if has_non_test_task or len(tasks) > 4:
        return 'build'
    return 'test'


def adaptive_memory_reservation(spec=None, config=None):
    """Choose a safe default reservation based on task weight and host sizing.

    If tier_sizing is enabled in config, allocates memory by task tier.
    Otherwise reserves half of available physical memory, bounded to 4-45 GiB.
    Missing telemetry retains the historical conservative 45 GiB reservation.
    """
    cfg = config or {}
    if available_memory_gb is None:
        return DEFAULT_MEMORY_GB, False
    try:
        available = float(available_memory_gb(include_swap=False))
        if not math.isfinite(available) or available < 0:
            raise ValueError("Invalid physical memory telemetry")
    except (OSError, RuntimeError, ValueError, TypeError, OverflowError):
        return DEFAULT_MEMORY_GB, False
    if cfg.get('tier_sizing', False) and spec is not None and available >= LOW_MEMORY_THRESHOLD_GB:
        tier = classify_task_tier(spec)
        target = float(cfg.get(f'threshold_{tier}_gb', 8.0 if tier == 'test' else (28.0 if tier == 'heavy' else 16.0)))
        return target, True
    # Without tier sizing (and on constrained hosts) reserve at most half of the
    # currently available memory, capped at the historical 45 GiB ceiling, so the
    # default request stays admissible on both small and large hosts.
    return max(4, min(DEFAULT_MEMORY_GB, int(available * 0.5))), True


def admission_headroom_gb(free_gb, memory_auto):
    """Return a bounded safety margin that scales with current free memory."""
    if memory_auto:
        return LOW_MEMORY_HEADROOM_GB
    return max(LOW_MEMORY_HEADROOM_GB,
               min(MAX_ADMISSION_HEADROOM_GB, int(free_gb * ADMISSION_HEADROOM_FRACTION)))


def host_cpu_load():
    """Report unavailable load explicitly (notably on Windows)."""
    cores = os.cpu_count()
    load = None
    try:
        if os.name == 'nt':
            from processes import windows_cpu_load
            candidate = windows_cpu_load()
        else:
            candidate = float(os.getloadavg()[0])
        if candidate is not None and math.isfinite(candidate) and candidate >= 0:
            load = candidate
    except (AttributeError, OSError, ValueError, TypeError, IndexError, ImportError):
        pass
    return {'cpu_count': cores, 'cpu_load_1m': load,
            'cpu_known': load is not None and cores is not None and cores > 0}


def reservation_usage_gb(active_jobs):
    """Memory running builds already use, each capped at its own reservation.

    Returns 0 when measurement is unavailable, keeping the full reservation
    charge. A build without a recorded PID has not started using memory yet.
    """
    jobs = [j for j in active_jobs if isinstance(j.get('pid'), int) and j['pid'] > 0]
    if not jobs:
        return 0.0
    try:
        from processes import process_tree_memory_gb
        used = process_tree_memory_gb([j['pid'] for j in jobs])
    except (OSError, ValueError, RuntimeError, ImportError, AttributeError):
        return 0.0
    return sum(min(float(j['spec'].get('memory_gb', 0)), used.get(j['pid'], 0.0)) for j in jobs)


def reservation_headroom(spec, config, free_gb):
    if spec.get('memory_auto', False):
        if config.get('tier_sizing', False):
            key = f'headroom_{classify_task_tier(spec)}_gb'
            return float(config.get(key, DEFAULT_CONFIG[key]))
        return LOW_MEMORY_HEADROOM_GB
    return min(float(config.get('headroom_explicit_gb', 16.0)),
               admission_headroom_gb(free_gb, False))


def compute_dynamic_slots(config=None, active_jobs=(), pending_jobs=(), effective_memory_gb=None,
                          *, physical_memory_gb=None):
    """Bound concurrency by physical RAM, reservations and the next queued work.

    ``effective_memory_gb`` is retained as a legacy keyword for callers supplying
    a RAM-only observation. Swap/commit capacity is a separate admission limit.
    Reservations are deliberately conservative: absent per-job RSS accounting,
    active commitments are retained rather than assumed to be fully allocated.
    """
    cfg = config or {}
    active = list(active_jobs)
    pending = sorted(pending_jobs, key=lambda job: job.get('created') or 0)
    tiers = [job.get('tier') or classify_task_tier(job.get('spec', {})) for job in active]
    next_tier = (pending[0].get('tier') or classify_task_tier(pending[0].get('spec', {}))) if pending else None
    reserved = sum(float(job.get('spec', {}).get('memory_gb', DEFAULT_MEMORY_GB)) for job in active)
    evidence = {'active_tiers': tiers, 'next_tier': next_tier,
                'active_reservations_gb': reserved, 'pending_reservations_gb': 0.0,
                'headroom_gb': None, 'physical_available_gb': None,
                'cpu_known': False, 'cpu_load_1m': None, 'cpu_count': None}
    mode = cfg.get('slots', 1)
    if mode != 'auto':
        # Preserve explicit settings. Validation prevents new malformed values;
        # an old malformed persisted value fails conservatively to one slot.
        slots = mode if type(mode) is int and 1 <= mode <= 8 else 1
        return dict(evidence, slots=slots, mode='fixed', reason=f'Fixed configuration ({slots} slots)')

    cpu = host_cpu_load()
    evidence.update(cpu)
    free_gb = physical_memory_gb if physical_memory_gb is not None else effective_memory_gb
    try:
        if free_gb is None:
            if available_memory_gb is None:
                raise RuntimeError('Memory telemetry is unavailable')
            free_gb = available_memory_gb(include_swap=False)
        free_gb = float(free_gb)
        if not math.isfinite(free_gb) or free_gb < 0:
            raise ValueError('Invalid physical memory telemetry')
    except (OSError, RuntimeError, ValueError, TypeError, OverflowError):
        return dict(evidence, slots=1, mode='auto', reason='Auto: 1 slot; physical RAM telemetry unavailable')
    evidence['physical_available_gb'] = free_gb
    if 'heavy' in tiers:
        return dict(evidence, slots=1, mode='auto', reason='Auto: 1 slot; active heavy task requires exclusive capacity')

    cap = min(3, cpu['cpu_count'] or 1)
    if not cpu['cpu_known']:
        cap = min(cap, 2)
        cpu_reason = 'CPU load unknown; conservative limit 2'
    elif cpu['cpu_load_1m'] > cpu['cpu_count'] * 1.3:
        cap = 1
        cpu_reason = f"high CPU load {cpu['cpu_load_1m']:.1f}/{cpu['cpu_count']}"
    else:
        cpu_reason = f"CPU load {cpu['cpu_load_1m']:.1f}/{cpu['cpu_count']}"
    if 'build' in tiers:
        cap = min(cap, 2)
    slots = len(active)
    extra_reserved = 0.0
    headroom = 0.0
    stop_reason = 'no further queued candidates'
    considered = 0
    for job in pending:
        if slots >= cap:
            stop_reason = 'concurrency limit reached'
            break
        spec = job.get('spec', {})
        tier = job.get('tier') or classify_task_tier(spec)
        candidate_cap = min(cap, 1 if tier == 'heavy' else 2 if tier == 'build' else 3)
        if slots >= candidate_cap:
            stop_reason = f'next {tier} task waits for active work'
            break
        memory = float(spec.get('memory_gb', DEFAULT_MEMORY_GB))
        candidate_headroom = max(headroom, reservation_headroom(spec, cfg, free_gb))
        considered += 1
        evidence['headroom_gb'] = candidate_headroom
        if free_gb < reserved + extra_reserved + memory + candidate_headroom:
            stop_reason = f'next {tier} reservation {memory:g} GiB plus headroom does not fit'
            break
        slots += 1
        extra_reserved += memory
        headroom = candidate_headroom
        cap = candidate_cap
    evidence['pending_reservations_gb'] = extra_reserved
    evidence['candidates_considered'] = considered
    # An idle worker still needs one lane so claim can explain a denied job.
    slots = max(1, min(cap, slots))
    return dict(evidence, slots=slots, mode='auto',
                reason=(f'Auto: {slots} slots; {free_gb:.1f} GiB physical RAM available, '
                        f'{reserved:g} GiB active reservations, {extra_reserved:g} GiB candidate reservations; '
                        f'{cpu_reason}; {stop_reason}'))


def normalize_excluded_tasks(values, tasks):
    """Finite private-CI exclusions; coverage is optional, test paths are exact."""
    if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
        raise ValueError('excluded_tasks must be a list of exact task selectors')
    for value in values:
        if value != 'koverVerify' and not re.fullmatch(r'(?::[A-Za-z0-9_][A-Za-z0-9_-]*)+:jvmTest', value):
            raise ValueError('excluded_tasks accepts only koverVerify or qualified exact jvmTest paths')
    excluded = sorted(set(values))
    if any(task['path'] in excluded or
           ('koverVerify' in excluded and task['path'].split(':')[-1] == 'koverVerify')
           for task in tasks):
        raise ValueError('A requested task cannot also be excluded')
    return excluded


def normalize(spec, config=None):
    allowed = {'snapshot', 'root', 'tasks', 'profile', 'properties', 'environment',
               'resources', 'memory_gb', 'follow_latest', 'reuse_tests', 'reuse_later',
               'artifacts', 'runtime', 'configuration_cache', 'excluded_tasks', 'offline'}
    if not isinstance(spec, dict) or set(spec) - allowed:
        raise ValueError('Unknown request fields')
    if not re.fullmatch('[a-f0-9]{64}', spec.get('snapshot', '')):
        raise ValueError('snapshot must be a registered SHA-256 ID')
    root = spec.get('root', '.')
    if not isinstance(root, str) or '\\' in root or ':' in root or PurePosixPath(root).is_absolute() or '..' in PurePosixPath(root).parts:
        raise ValueError('root must stay within the snapshot')
    tasks = spec.get('tasks', [])
    if not isinstance(tasks, list) or not tasks:
        raise ValueError('At least one task is required')
    normalized = []
    for task in tasks:
        if isinstance(task, str):
            task = {'path': task}
        if not isinstance(task, dict) or set(task) - {'path', 'tests'}:
            raise ValueError('Tasks accept path and tests only')
        path = task.get('path', '')
        if not re.fullmatch(r':?[A-Za-z0-9_][A-Za-z0-9_:-]*', path) or any(x.lower().startswith(('clean', 'publish', 'dockerpush', 'runserver')) for x in path.split(':')):
            raise ValueError('Only finite build/test tasks are allowed; no clean, publish, server, or process control')
        if path.split(':')[-1] not in TASK_NAMES:
            raise ValueError('Use an exact supported build/test task name; task abbreviations are disabled')
        path = ':' + path.lstrip(':')
        tests = task.get('tests', [])
        if not isinstance(tests, list) or any(not isinstance(t, str) or not t or '\n' in t or '\r' in t or t.startswith('-') for t in tests):
            raise ValueError('tests must be a list of Gradle test filters')
        normalized.append({'path': path, 'tests': sorted(set(tests))})
    props = spec.get('properties', {})
    env = spec.get('environment', {})
    for mapping in (props, env):
        if not isinstance(mapping, dict) or any(not isinstance(k, str) or not isinstance(v, str) or '\x00' in k + v for k, v in mapping.items()):
            raise ValueError('properties and environment must be string maps')
    if any(not re.fullmatch(r'[A-Za-z0-9_.-]+', k) or k.startswith(('org.gradle.', 'systemProp.', 'kotlin.daemon.')) or k == 'kotlin.compiler.execution.strategy' for k in props):
        raise ValueError('Coordinator owns Gradle process settings')
    if len({k.upper() for k in env}) != len(env) or any(not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', k) or k.upper().startswith('VDX_BUILD_') or k.upper() in {'GRADLE_USER_HOME', 'GRADLE_OPTS', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'} for k in env):
        raise ValueError('Coordinator owns JVM/Gradle process environment')
    if os.name == 'nt':
        env = {k.upper(): v for k, v in env.items()}
    artifacts = spec.get('artifacts', [])
    if not isinstance(artifacts, list) or any(not isinstance(p, str) or not p or ':' in p or '\\' in p or PurePosixPath(p).is_absolute() or '..' in PurePosixPath(p).parts for p in artifacts):
        raise ValueError('Artifact globs must stay within the snapshot')
    native_request_contract(dict(root=root, tasks=normalized, environment=env, artifacts=artifacts))
    resources = spec.get('resources', [])
    if not isinstance(resources, list) or any(not isinstance(r, str) or not r for r in resources):
        raise ValueError('resources must be explicit resource names; [] means isolated tests')
    memory_auto = 'memory_gb' not in spec
    memory = spec.get('memory_gb') if not memory_auto else None
    if memory_auto:
        memory, memory_auto = adaptive_memory_reservation(spec, config)
    if type(memory) not in (int, float) or not math.isfinite(memory) or not 1 <= memory <= 96:
        raise ValueError('memory_gb must be between 1 and 96')
    profile = spec.get('profile', 'development')
    if profile not in ('development', 'fresh'):
        raise ValueError('profile must be development or fresh')
    configuration_cache = spec.get('configuration_cache', 'auto')
    if configuration_cache not in ('auto', 'off'):
        raise ValueError('configuration_cache must be auto or off')
    if profile == 'fresh':
        configuration_cache = 'off'
    for flag in ('follow_latest', 'reuse_tests', 'reuse_later', 'offline'):
        if flag in spec and not isinstance(spec[flag], bool):
            raise ValueError(f'{flag} must be boolean')
    return dict(snapshot=spec['snapshot'], root=str(PurePosixPath(root)), tasks=merge_tasks([], normalized),
                configuration_cache=configuration_cache, offline=spec.get('offline', False),
                excluded_tasks=normalize_excluded_tasks(spec.get('excluded_tasks', []), normalized),
                profile=profile, properties=props, environment=env, resources=sorted(set(resources)),
                memory_gb=memory, memory_auto=memory_auto, follow_latest=spec.get('follow_latest', False),
                reuse_tests=spec.get('reuse_tests', False), reuse_later=spec.get('reuse_later', True),
                artifacts=sorted(set(artifacts)), runtime=spec.get('runtime', {}))


def merge_tasks(a, b):
    merged = {}
    for task in a + b:
        path, tests = task['path'], task['tests']
        if path not in merged:
            merged[path] = list(tests)
        elif not tests or not merged[path]:
            merged[path] = []  # empty means entire task, not no tests
        else:
            merged[path] = sorted(set(merged[path] + tests))
    return [{'path': p, 'tests': merged[p]} for p in sorted(merged)]


def build_key(spec):
    return identity({k: v for k, v in spec.items() if k not in ('tasks', 'artifacts', 'follow_latest', 'reuse_tests', 'reuse_later')})


class Store:
    @classmethod
    def open_existing(cls, state):
        """Open a registered queue without creating directories or migrating it.

        Discovery must not acquire a writer lock. Its schema/metadata reads use
        a read-only connection. Read-only discovery fails fast on busy queues;
        explicit mutations use the normal bounded contention timeout.
        Explicit mutations remain supported through existing-only rw handles.
        """
        from snapshots import _guard
        instance = cls.__new__(cls)
        instance.state = _guard(Path(os.path.abspath(state)))
        instance.path = _guard(instance.state / 'queue.sqlite3')
        if not instance.state.is_dir() or not instance.path.is_file():
            raise FileNotFoundError('Coordinator queue does not exist')
        instance._existing_only = True
        with instance.connect(read_only=True) as db:
            db.execute('SELECT key,value FROM metadata LIMIT 0')
        return instance

    def __init__(self, state):
        self.state = Path(state).resolve()
        self.state.mkdir(parents=True, exist_ok=True)
        self.path = self.state / 'queue.sqlite3'
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY, data TEXT NOT NULL, created REAL);
                CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, key TEXT, spec TEXT, status TEXT,
                    created REAL, started REAL, finished REAL, lane INTEGER, phase TEXT, pid INTEGER,
                    token TEXT, replacement TEXT, predecessor TEXT, restarts INTEGER DEFAULT 0, result TEXT);
                CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, agent TEXT, spec TEXT,
                    job_id TEXT, active INTEGER DEFAULT 1, created REAL);
                CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, time REAL,
                    kind TEXT, job_id TEXT, request_id TEXT, data TEXT);
                CREATE TABLE IF NOT EXISTS writers(id TEXT PRIMARY KEY, agent TEXT, created REAL);
                CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS config(key TEXT PRIMARY KEY, value TEXT);
            ''')
            columns = {row['name'] for row in db.execute('PRAGMA table_info(snapshots)')}
            if 'created' not in columns:
                db.execute('ALTER TABLE snapshots ADD COLUMN created REAL')
            if 'expired_at' not in columns:
                db.execute('ALTER TABLE snapshots ADD COLUMN expired_at REAL')
            db.execute('UPDATE snapshots SET created=? WHERE created IS NULL', (time.time(),))
            request_columns = {row['name'] for row in db.execute('PRAGMA table_info(requests)')}
            # collected: when a terminal result was handed to the agent.
            # waiter_*: the live `wait` process following the request, if any.
            for column, kind in (('collected', 'REAL'), ('waiter_pid', 'INTEGER'), ('waiter_heartbeat', 'REAL')):
                if column not in request_columns:
                    db.execute(f'ALTER TABLE requests ADD COLUMN {column} {kind}')
                    if column == 'collected':
                        # Collection was never tracked before; only results that finish
                        # from now on can be reported as uncollected.
                        db.execute('UPDATE requests SET collected=(SELECT finished FROM jobs WHERE jobs.id=requests.job_id) '
                                   'WHERE job_id IN (SELECT id FROM jobs WHERE status IN (?,?,?,?))', TERMINAL)
            db.execute('CREATE INDEX IF NOT EXISTS jobs_status_created ON jobs(status,created)')
            db.execute('CREATE INDEX IF NOT EXISTS requests_job_active ON requests(job_id,active)')
            import history
            history.ensure_schema(db)

    def connect(self, *, read_only=False):
        existing = getattr(self, '_existing_only', False)
        # Existing-queue discovery stays nonblocking and read-only. Explicit
        # mutations (waiters, task gates, leases) must tolerate the same brief
        # reader/writer contention as normal coordinator transactions.
        timeout = 0 if existing and read_only else 30
        if existing or read_only:
            from snapshots import _guard
            # SQLite may open these sidecars as part of the same connection.
            for suffix in ('', '-wal', '-shm', '-journal'):
                _guard(Path(str(self.path) + suffix))
            mode = 'ro' if read_only else 'rw'
            db = sqlite3.connect(self.path.as_uri() + '?mode=' + mode, uri=True,
                                 timeout=timeout, factory=Connection)
        else:
            db = sqlite3.connect(self.path, timeout=timeout, factory=Connection)
        db.row_factory = sqlite3.Row
        db.execute(f'PRAGMA busy_timeout={timeout * 1000}')
        return db

    @contextmanager
    def transaction(self):
        db = self.connect()
        try:
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def event(self, db, kind, job=None, request=None, **data):
        db.execute('INSERT INTO events(time,kind,job_id,request_id,data) VALUES(?,?,?,?,?)',
                   (time.time(), kind, job, request, encoded(data)))

    def register_snapshot(self, snapshot):
        with self.transaction() as db:
            previous = db.execute('SELECT expired_at FROM snapshots WHERE id=?', (snapshot['id'],)).fetchone()
            if previous and previous['expired_at'] is not None:
                # Only a complete, verified recapture may revive expired source.
                from snapshots import _verify
                _verify(Path(snapshot['path']))
                db.execute('UPDATE snapshots SET data=?,created=?,expired_at=NULL WHERE id=?',
                           (encoded(snapshot), time.time(), snapshot['id']))
                return
            db.execute('INSERT OR IGNORE INTO snapshots(id,data,created) VALUES(?,?,?)',
                       (snapshot['id'], encoded(snapshot), time.time()))

    def snapshot(self, snapshot_id):
        with self.connect() as db:
            row = db.execute('SELECT data,expired_at FROM snapshots WHERE id=?', (snapshot_id,)).fetchone()
            if row is None:
                raise ValueError('Unknown snapshot')
            if row['expired_at'] is not None:
                raise ValueError('Snapshot source has expired; capture a new snapshot before submitting')
            return json.loads(row['data'])

    def new_job(self, db, spec, predecessor=None, restarts=0):
        job_id = 'B' + uuid.uuid4().hex[:16]
        db.execute('INSERT INTO jobs(id,key,spec,status,created,phase,predecessor,restarts) VALUES(?,?,?,?,?,?,?,?)',
                   (job_id, build_key(spec), encoded(spec), 'queued', time.time(), 'queued', predecessor, restarts))
        self.event(db, 'queued', job_id, snapshot=spec['snapshot'])
        return job_id

    def submit(self, raw, agent):
        spec = normalize(raw, self.all_config())
        if not isinstance(agent, str) or not agent.strip():
            raise ValueError('Agent/session identity is required')
        snapshot = self.snapshot(spec['snapshot'])
        execution = snapshot.get('execution', {})
        if execution.get('mode') == 'owned-worktree':
            if agent != execution.get('agent'):
                raise ValueError('Owned worktree request belongs to another owner')
            if spec['root'] not in execution['selection']['roots']:
                raise ValueError('Owned worktree root was not captured')
            if spec['follow_latest'] or spec['reuse_tests']:
                raise ValueError('Owned worktree builds require exact source and fresh tests')
            spec['reuse_later'] = False
            spec['resources'] = sorted(set(spec['resources']) | {'owned-worktree:' + execution['workspaceId']})
        request_id = 'R' + uuid.uuid4().hex[:16]
        with self.transaction() as db:
            availability = db.execute('SELECT expired_at FROM snapshots WHERE id=?', (spec['snapshot'],)).fetchone()
            if availability is None:
                raise ValueError('Unknown snapshot; capture it first')
            if availability['expired_at'] is not None:
                raise ValueError('Snapshot source has expired; capture a new snapshot before submitting')
            # Each submission owns one execution; compilation caches remain Gradle's responsibility.
            job_id = self.new_job(db, spec)
            db.execute('INSERT INTO requests(id,agent,spec,job_id,created) VALUES(?,?,?,?,?)',
                       (request_id, agent, encoded(spec), job_id, time.time()))
            self.event(db, 'subscribed', job_id, request_id, agent=agent)
        return request_id

    @staticmethod
    def decode_job(row):
        if row is None:
            return None
        job = dict(row)
        job['spec'] = json.loads(job['spec'])
        job['result'] = json.loads(job['result']) if job['result'] else None
        return job

    def archived_result(self, job_id):
        """Load a result that was archived before its SQLite copy was evicted."""
        path = self.state / 'results' / job_id / 'result.json'
        if not path.is_file():
            return None
        try:
            with path.open('r', encoding='utf-8') as stream:
                result = json.load(stream)
        except (OSError, ValueError):
            return None
        return result if isinstance(result, dict) else None

    def cleanup_results(self, apply=False):
        """Preview or evict terminal JSON only after an equivalent archive exists.

        Preserve the legacy response keys. ``missing_archives`` includes missing,
        malformed, mismatched or changed archives: none may replace evidence.
        Validation streams both representations rather than decoding full result
        payloads. Only this command's verified SQLite duplicates are evicted.
        """
        from maintenance import CHUNK, JOB, JsonStream, _file_digest, _rooted

        class ResultStream:
            def __init__(self, db, job_id):
                self.db, self.job_id, self.offset = db, job_id, 1

            def read(self, size):
                if type(size) is not int or not 0 < size <= CHUNK:
                    raise ValueError('Result verification requires bounded reads')
                row = self.db.execute('SELECT substr(result,?,?) FROM jobs WHERE id=?',
                                      (self.offset, size, self.job_id)).fetchone()
                text = row[0] if row is not None and row[0] is not None else ''
                self.offset += len(text)
                return text

        def fingerprint(path):
            info = path.stat()
            return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)

        terminal = tuple(TERMINAL)
        placeholders = ','.join('?' for _ in terminal)
        with self.connect() as db:
            active = db.execute(
                "SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running','cancelling')"
            ).fetchone()[0]
            rows = db.execute(
                f'SELECT id,status,finished,length(result) AS bytes FROM jobs '
                f'WHERE status IN ({placeholders}) AND result IS NOT NULL', terminal
            ).fetchall()
        candidates = []
        missing = 0
        for row in rows:
            try:
                if not JOB.fullmatch(row['id']):
                    raise ValueError('Invalid archived build identity')
                archive = _rooted(self.state, self.state / 'results' / row['id'] / 'result.json')
                before = fingerprint(archive)
                archive_digest = _file_digest(archive)
                with self.connect() as db:
                    reader = JsonStream(ResultStream(db, row['id']))
                    for _ in reader.values():
                        pass
                if reader.root_kind != '{' or reader.digest != archive_digest or fingerprint(archive) != before:
                    raise ValueError('Archived result differs from SQLite evidence')
                candidates.append((row, before))
            except (OSError, ValueError, TypeError):
                missing += 1
        summary = {
            'active_jobs': active,
            'candidates': len(candidates),
            'candidate_bytes': sum(row['bytes'] or 0 for row, _ in candidates),
            'missing_archives': missing,
            'applied': False,
        }
        if not apply:
            return summary
        evicted = []
        with self.transaction() as db:
            active = db.execute(
                "SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running','cancelling')"
            ).fetchone()[0]
            if active:
                raise ValueError('Drain the coordinator and wait for active jobs before cleanup')
            for row, verified in candidates:
                try:
                    archive = _rooted(self.state, self.state / 'results' / row['id'] / 'result.json')
                    if fingerprint(archive) != verified:
                        raise ValueError('Archive changed after validation')
                except (OSError, ValueError):
                    summary['missing_archives'] += 1
                    continue
                # Terminal results are immutable through Store APIs. Match their
                # lifecycle fields and length so a concurrent cleanup or state
                # transition cannot make this proof refer to a different result.
                changed = db.execute('UPDATE jobs SET result=NULL WHERE id=? AND status=? AND finished IS ? AND length(result)=?',
                                     (row['id'], row['status'], row['finished'], row['bytes'])).rowcount
                if changed:
                    evicted.append(row)
        summary.update(active_jobs=active, candidates=len(evicted),
                       candidate_bytes=sum(row['bytes'] or 0 for row in evicted), applied=True)
        return summary

    def job(self, job_id):
        with self.connect() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None:
                raise ValueError('Unknown build')
            return self.decode_job(row)

    def jobs(self, statuses=None):
        with self.connect() as db:
            if statuses:
                placeholders = ','.join('?' for _ in statuses)
                rows = db.execute(
                    f'SELECT * FROM jobs WHERE status IN ({placeholders}) ORDER BY created DESC',
                    tuple(statuses),
                )
            else:
                rows = db.execute('SELECT * FROM jobs ORDER BY created DESC')
            return [self.decode_job(r) for r in rows]

    def job_headers(self, statuses):
        """Return only lifecycle fields needed by the worker admission loop.

        Job specs and archived results can contain large composite-build metadata.
        The worker only needs ids, status, lane and pid while deciding whether to
        claim work; decoding the full rows here made a long-lived queue vulnerable
        to MemoryError before it could advance a pending build.
        """
        if not statuses:
            raise ValueError('job_headers requires at least one status')
        with self.connect() as db:
            placeholders = ','.join('?' for _ in statuses)
            return [dict(row) for row in db.execute(
                f'SELECT id,status,lane,pid FROM jobs WHERE status IN ({placeholders}) ORDER BY created DESC',
                tuple(statuses),
            )]

    def job_tiers(self, statuses):
        """Return compact tier summaries without decoding results or full job rows."""
        if not statuses:
            raise ValueError('job_tiers requires at least one status')
        with self.connect() as db:
            placeholders = ','.join('?' for _ in statuses)
            rows = db.execute(f'SELECT spec FROM jobs WHERE status IN ({placeholders})', tuple(statuses))
            return [{'tier': classify_task_tier(json.loads(row['spec']))} for row in rows]

    def claim(self, lane, free_gb=None, allowed_tiers=None, other_active_jobs=()):
        source = self.metadata('source')
        if not source:
            return self._claim(lane, free_gb, allowed_tiers, other_active_jobs=other_active_jobs)
        from registry import Registry
        registry = Registry()
        if free_gb is None:
            cfg = self.all_config()
            if available_memory_gb is not None:
                free_gb = available_memory_gb(include_swap=cfg.get('include_swap', True),
                                              swap_weight=float(cfg.get('swap_weight', 0.75)))
        with registry.lock():
            global_state = registry.global_active(prune_reservations=True)
            reserved_gb = global_state['reserved_gb'] + sum(
                float(j['spec'].get('memory_gb', 0)) for j in global_state['legacy'])
            live_keys = {(r['state'], r['job_id']) for r in global_state['reservations']}
            legacy_keys = {(j['state'], j['id']) for j in global_state['legacy']}
            own_state = str(self.state.resolve())
            registered_active = [j for j in global_state['active']
                                 if (j['state'], j['id']) in live_keys | legacy_keys]
            registered_siblings = [j for j in registered_active if j['state'] != own_state or j.get('kind') == 'local-docker-native-image']
            # Registry state is authoritative for machine-wide admission. Keep
            # caller-provided rows only for older/unregistered embedding callers.
            merged_siblings = { (j.get('state'), j['id']): j for j in registered_siblings }
            registered_keys = {(j.get('state'), j['id']) for j in registered_active}
            for row in other_active_jobs:
                if row.get('state') != own_state:
                    key = (row.get('state'), row['id'])
                    if key not in merged_siblings:
                        merged_siblings[key] = row
                        # Legacy callers may pass active rows that have no machine
                        # registry reservation. Preserve their conservative memory
                        # charge, while registered jobs are already counted above.
                        if key not in registered_keys:
                            reserved_gb += float(row['spec'].get('memory_gb', 0))
            job = self._claim(lane, free_gb, allowed_tiers,
                              global_reserved_gb=reserved_gb,
                              other_active_jobs=tuple(merged_siblings.values()))
            if job:
                worker = self.metadata('worker') or {}
                pid = worker.get('pid') if isinstance(worker.get('pid'), int) else os.getpid()
                now = time.time()
                registry.put_reservation(job['id'], self.state, lane, job['spec']['memory_gb'],
                                         classify_task_tier(job['spec']), pid, now)
                registry.record_admission(free_gb, reserved_gb, self.state, job['id'])
            else:
                registry.record_admission(free_gb, reserved_gb, self.state)
            return job

    def scheduling_jobs(self, statuses):
        """Read queue-ordered scheduling inputs, never archived result payloads."""
        if not statuses:
            raise ValueError('scheduling_jobs requires at least one status')
        with self.connect() as db:
            placeholders = ','.join('?' for _ in statuses)
            rows = list(db.execute(
                f'SELECT id,status,created,started,finished,lane,phase,pid,spec FROM jobs '
                f'WHERE status IN ({placeholders}) ORDER BY created,id', tuple(statuses)))
            queued = self._ordered_queue(db, [row for row in rows if row['status'] == 'queued'])
            positions = {row['id']: index + 1 for index, row in enumerate(queued)}
            return [{**dict(row), 'spec': json.loads(row['spec']),
                     'queue_position': positions.get(row['id']),
                     'queue_order': positions.get(row['id']), 'queue_size': len(queued)}
                    for row in rows if row['status'] != 'queued'] + [
                {**dict(row), 'spec': json.loads(row['spec']),
                 'queue_position': positions[row['id']], 'queue_order': positions[row['id']],
                 'queue_size': len(queued)} for row in queued]

    @classmethod
    def _scheduling_order(cls, db, rows, stats):
        """Admission order: explicit moves first, then expected duration against wait time."""
        import history
        saved = db.execute("SELECT value FROM metadata WHERE key='queue_order'").fetchone()
        moved = json.loads(saved['value']) if saved else []
        ranks = {job_id: index for index, job_id in enumerate(moved if isinstance(moved, list) else [])}
        now = time.time()
        return sorted(rows, key=lambda row: (ranks.get(row['id'], len(ranks)),
                                            history.admission_score(dict(row), stats(json.loads(row['spec'])), now),
                                            row['created'], row['id']))

    def sample_peaks(self):
        """Raise each running build's recorded peak to its current process-tree memory."""
        with self.connect() as db:
            running = [dict(r) for r in db.execute("SELECT id,pid FROM jobs WHERE status='running' AND pid IS NOT NULL")]
        if not running:
            return
        from processes import process_tree_memory_gb
        used = process_tree_memory_gb([r['pid'] for r in running])
        with self.transaction() as db:
            for row in running:
                if used.get(row['pid']):
                    db.execute('UPDATE jobs SET peak_gb=MAX(COALESCE(peak_gb,0),?) WHERE id=?',
                               (round(used[row['pid']], 2), row['id']))

    def task_stats(self, spec=None):
        import history
        with self.connect() as db:
            return history.stats_for(db, history.task_key(spec)) if spec is not None else history.all_stats(db)

    @staticmethod
    def _ordered_queue(db, rows):
        """Persist ordering independently of creation time and build identity.

        Only explicit moves write this small metadata record, so existing queues
        can be discovered without schema migrations. New work follows the saved
        order; removed/claimed IDs are discarded on the next explicit move.
        """
        saved = db.execute("SELECT value FROM metadata WHERE key='queue_order'").fetchone()
        order = json.loads(saved['value']) if saved else []
        if not isinstance(order, list) or any(not isinstance(item, str) for item in order):
            raise ValueError('Invalid saved queue order')
        ranks = {job_id: index for index, job_id in enumerate(order)}
        return sorted(rows, key=lambda row: (ranks.get(row['id'], len(ranks)),
                                            row['created'], row['id']))

    def move_queued(self, job_id, direction):
        """Atomically move queued work one position, racing safely with claim."""
        if not isinstance(job_id, str) or not re.fullmatch(r'B[0-9a-f]{16}', job_id):
            raise ValueError('Invalid build identity')
        if direction not in ('up', 'down'):
            raise ValueError('direction must be up or down')
        with self.transaction() as db:
            rows = self._ordered_queue(db, list(db.execute(
                "SELECT id,created FROM jobs WHERE status='queued' ORDER BY created,id")))
            ids = [row['id'] for row in rows]
            if job_id not in ids:
                raise ValueError('Build is no longer queued; refresh the queue')
            position = ids.index(job_id)
            destination = position + (-1 if direction == 'up' else 1)
            moved = 0 <= destination < len(ids)
            if moved:
                ids[position], ids[destination] = ids[destination], ids[position]
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)',
                           ('queue_order', encoded(ids)))
                self.event(db, 'queue-reordered', job_id, direction=direction,
                           position=destination + 1)
            return {'job': job_id, 'moved': moved, 'queue_position': ids.index(job_id) + 1,
                    'queue_size': len(ids)}

    def _claim(self, lane, free_gb=None, allowed_tiers=None,
               global_reserved_gb=None, other_active_jobs=()):
        with self.transaction() as db:
            cfg = self.all_config()
            if free_gb is None:
                if available_memory_gb is not None:
                    try:
                        free_gb = available_memory_gb(
                            include_swap=cfg.get('include_swap', True),
                            swap_weight=float(cfg.get('swap_weight', 0.75))
                        )
                    except (OSError, RuntimeError, ValueError, TypeError) as error:
                        raise RuntimeError('Memory telemetry unavailable; admission deferred') from error
                else:
                    raise RuntimeError('Memory telemetry unavailable; admission deferred')
            if type(free_gb) not in (int, float) or not math.isfinite(free_gb) or free_gb < 0:
                raise RuntimeError('Invalid memory telemetry; admission deferred')
            reason = None
            active = [{**dict(r), 'spec': json.loads(r['spec'])} for r in db.execute(
                "SELECT id,lane,pid,spec FROM jobs WHERE status IN ('running','cancelling')")]
            if any(j['lane'] == lane for j in active):
                return None
            host_active = active + list(other_active_jobs)
            if any(j.get('kind') == 'local-docker-native-image' for j in host_active):
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)',
                           ('blocked',encoded({'reason':'Waiting for globally exclusive external native slot'})))
                return None
            import history
            stats_cache = {}
            def stats(spec):
                key = history.task_key(spec)
                if key not in stats_cache:
                    stats_cache[key] = history.stats_for(db, key)
                return stats_cache[key]
            queued_rows = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created,id").fetchall()
            ordered = self._scheduling_order(db, queued_rows, stats)
            short_waiting = any(not history.is_long(stats(json.loads(r['spec']))) for r in queued_rows)
            long_running = sum(1 for j in host_active if history.is_long(stats(j['spec'])))
            for row in ordered:
                job = self.decode_job(row)
                spec = job['spec']
                # Unsubscribing is intentionally non-destructive for a running
                # build, but a queued build with no remaining consumer must
                # never be admitted.  This also cleans orphaned queued rows
                # created before the guard was installed.
                if not db.execute('SELECT 1 FROM requests WHERE job_id=? AND active=1 LIMIT 1',
                                  (job['id'],)).fetchone():
                    result = {'issues': ['No active subscribers; build was not started'], 'tasks': []}
                    db.execute("UPDATE jobs SET status='superseded',finished=?,result=? WHERE id=?",
                               (time.time(), encoded(result), job['id']))
                    self.event(db, 'superseded', job['id'])
                    continue
                if job['predecessor']:
                    previous = db.execute('SELECT status FROM jobs WHERE id=?', (job['predecessor'],)).fetchone()
                    if previous['status'] not in TERMINAL:
                        reason = 'Waiting for superseded execution to stop'
                        continue
                if any(set(j['spec']['resources']) & set(spec['resources'])
                       for j in host_active):
                    reason = 'Waiting for an exclusive test resource'
                    continue
                tier = classify_task_tier(spec)
                if allowed_tiers is not None and tier not in allowed_tiers:
                    reason = f'Waiting for external build blockers to clear ({tier} tier requires idle host)'
                    continue
                if cfg.get('slots') == 'auto' and host_active:
                    dynamic = compute_dynamic_slots(cfg, active_jobs=host_active, pending_jobs=[job])
                    if dynamic['slots'] <= len(host_active):
                        reason = dynamic['reason']
                        continue
                elif isinstance(cfg.get('slots'), int) and len(host_active) >= cfg['slots']:
                    # Re-check under the registry lock: two workers that each saw a
                    # free slot before locking must not both start a build.
                    reason = f"Waiting for slot (limit {cfg['slots']} [fixed]; {len(host_active)} builds running on this host)"
                    continue
                job_stats = stats(spec)
                slots = cfg.get('slots')
                if (isinstance(slots, int) and slots >= 2 and history.is_long(job_stats) and short_waiting
                        and long_running + 1 >= slots):
                    # Keep the last slot for shorter builds rather than fill the host
                    # with long ones while quick checks wait behind them.
                    reason = (f"Keeping one slot for shorter builds ({long_running} long builds running; "
                              f"this one typically takes {history.expected_minutes(job_stats)} min)")
                    continue
                sized = history.sized_memory_gb(spec, job_stats)
                if sized is not None:
                    spec = {**spec, 'memory_gb': sized, 'memory_sized_from_history': True}
                    job['spec'] = spec
                headroom = reservation_headroom(spec, cfg, free_gb)
                active_reservations = (global_reserved_gb if global_reserved_gb is not None else
                                       sum(j['spec']['memory_gb'] for j in host_active))
                # Memory a running build already uses is missing from free_gb, so
                # charge only the unused rest of its reservation.
                active_reservations = max(0.0, active_reservations - reservation_usage_gb(host_active))
                if free_gb < spec['memory_gb'] + headroom + active_reservations:
                    swap_note = ' (including swap)' if cfg.get('include_swap', True) else ' (RAM only)'
                    reason = (f'Memory admission denied: {free_gb:.1f} GiB available{swap_note}; [{tier.upper()} tier] '
                              f'requires reservation {spec["memory_gb"]} GiB plus {headroom} GiB headroom '
                              f'and active reservations ({active_reservations} GiB).')
                    continue
                token = uuid.uuid4().hex
                if spec.get('memory_sized_from_history'):
                    db.execute('UPDATE jobs SET spec=? WHERE id=?', (json.dumps(spec, sort_keys=True), job['id']))
                db.execute("UPDATE jobs SET status='running',started=?,lane=?,phase='preparing',token=? WHERE id=?",
                           (time.time(), lane, token, job['id']))
                self.event(db, 'started', job['id'], lane=lane)
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', ('blocked', '{}'))
                return self.decode_job(db.execute('SELECT * FROM jobs WHERE id=?', (job['id'],)).fetchone())
            if reason:
                blocked = {'reason': reason}
                previous = db.execute('SELECT value FROM metadata WHERE key=?', ('blocked',)).fetchone()
                previous = json.loads(previous['value'] or '{}') if previous else {}
                now = time.time()
                category = reason.split(':', 1)[0]
                old_category = str(previous.get('reason', '')).split(':', 1)[0]
                emit_event = category != old_category or now - previous.get('event_time', 0) >= 60
                blocked['event_time'] = now if emit_event else previous.get('event_time', now)
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', ('blocked', encoded(blocked)))
                if emit_event:
                    waiting_job = job['id'] if 'job' in locals() else None
                    self.event(db, 'blocked', waiting_job, reason=reason)
            else:
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', ('blocked', '{}'))
        return None

    def has_active_subscribers(self, job_id):
        with self.connect() as db:
            return bool(db.execute('SELECT 1 FROM requests WHERE job_id=? AND active=1 LIMIT 1', (job_id,)).fetchone())

    def requeue_infrastructure(self, job_id, max_retries=None):
        """Queue one bounded new attempt and move only still-active subscribers."""
        limit = int(self.config('infrastructure_retries') if max_retries is None else max_retries)
        with self.transaction() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None:
                return None
            job = self.decode_job(row)
            if job['status'] not in ('failed', 'interrupted') or job['restarts'] >= limit:
                return None
            previous_result = json.loads(row['result']) if row['result'] else {}
            if not previous_result.get('infrastructure'):
                return None
            subscribers = db.execute('SELECT id FROM requests WHERE job_id=? AND active=1', (job_id,)).fetchall()
            if not subscribers:
                return None
            replacement = self.new_job(db, job['spec'], job_id, job['restarts'] + 1)
            db.execute('UPDATE requests SET job_id=? WHERE job_id=? AND active=1', (replacement, job_id))
            self.event(db, 'infrastructure-requeued', job_id, replacement=replacement,
                       attempt=job['restarts'] + 1, limit=limit,
                       requests=[r['id'] for r in subscribers])
            return replacement

    def attempt_history(self, job_id, status, infrastructure=None):
        with self.connect() as db:
            rows, current = [], job_id
            while current:
                row = db.execute('SELECT id,status,result,predecessor,restarts FROM jobs WHERE id=?', (current,)).fetchone()
                if row is None:
                    break
                result = json.loads(row['result']) if row['result'] else {}
                rows.append({'job_id': row['id'], 'status': status if row['id'] == job_id else row['status'],
                             'infrastructure': infrastructure if row['id'] == job_id else result.get('infrastructure')})
                current = row['predecessor']
        return list(reversed(rows))

    def phase(self, job_id, phase, pid=None):
        with self.transaction() as db:
            db.execute("UPDATE jobs SET phase=CASE WHEN phase='executing' AND ?='configuring' THEN phase ELSE ? END,pid=COALESCE(?,pid) WHERE id=?", (phase, phase, pid, job_id))
            self.event(db, 'phase', job_id, phase=phase)

    def enter_execution(self, job_id, token):
        with self.transaction() as db:
            row = db.execute('SELECT status,token FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None or row['status'] != 'running' or not hmac.compare_digest(row['token'] or '', token):
                return False
            db.execute("UPDATE jobs SET phase='executing' WHERE id=?", (job_id,))
            self.event(db, 'execution-started', job_id)
            return True

    def finish(self, job_id, status, result):
        source = self.metadata('source')
        if source:
            from registry import Registry
            registry = Registry()
            with registry.wait_for_lock():
                self._finish(job_id, status, result)
                registry.remove_reservation(job_id)
            return
        self._finish(job_id, status, result)

    def _finish(self, job_id, status, result):
        if status not in TERMINAL:
            raise ValueError('Invalid terminal status')
        with self.transaction() as db:
            row = db.execute('SELECT status FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None or row['status'] not in ACTIVE:
                raise ValueError('Build is not active')
            if row['status'] == 'cancelling':
                status = 'superseded'
            db.execute('UPDATE jobs SET status=?,finished=?,result=?,token=NULL WHERE id=?',
                       (status, time.time(), encoded(result), job_id))
            import history
            history.record(db, db.execute('SELECT id,spec,status,created,started,finished,peak_gb FROM jobs WHERE id=?',
                                          (job_id,)).fetchone())
            self.event(db, status, job_id)

    def supersede(self, job_id, snapshot_id):
        with self.transaction() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (job_id,)).fetchone()
            if row is None:
                raise ValueError('Unknown build')
            job = self.decode_job(row)
            if job['restarts']:
                raise ValueError('Automatic restart limit reached; queue next snapshot')
            if job['status'] not in ('queued', 'running') or (job['status'] == 'running' and
                    (job['phase'] not in ('preparing', 'configuring') or time.time() - job['started'] > 120)):
                raise ValueError('Replacement is only allowed in the early preparation/configuration window')
            subscribers = db.execute('SELECT spec FROM requests WHERE job_id=? AND active=1', (job_id,)).fetchall()
            if any(not json.loads(r['spec'])['follow_latest'] for r in subscribers):
                raise ValueError('An exact-snapshot subscriber prevents replacement')
            if not db.execute('SELECT 1 FROM snapshots WHERE id=? AND expired_at IS NULL', (snapshot_id,)).fetchone():
                raise ValueError('Unknown replacement snapshot')
            spec = job['spec']
            spec['snapshot'] = snapshot_id
            replacement = self.new_job(db, spec, job_id, 1)
            status = 'cancelling' if job['status'] == 'running' else 'superseded'
            db.execute('UPDATE jobs SET status=?,replacement=? WHERE id=?', (status, replacement, job_id))
            db.execute('UPDATE requests SET job_id=? WHERE job_id=? AND active=1', (replacement, job_id))
            self.event(db, 'superseding', job_id, replacement=replacement)
            return replacement

    def unsubscribe(self, request_id):
        with self.transaction() as db:
            row = db.execute('SELECT job_id FROM requests WHERE id=?', (request_id,)).fetchone()
            if row is None:
                raise ValueError('Unknown request')
            db.execute('UPDATE requests SET active=0 WHERE id=?', (request_id,))
            self.event(db, 'unsubscribed', row['job_id'], request_id)
            # An unclaimed job with no subscribers owns no process or source
            # execution. Close it atomically so it cannot retain an edit barrier
            # while its worker is drained or waiting for global admission.
            job_id = row['job_id']
            if (not db.execute('SELECT 1 FROM requests WHERE job_id=? AND active=1', (job_id,)).fetchone()
                    and db.execute("SELECT 1 FROM jobs WHERE id=? AND status='queued'", (job_id,)).fetchone()):
                result = {'accepted': False, 'issues': ['No active subscribers; build was not started'], 'tasks': []}
                db.execute("UPDATE jobs SET status='superseded',finished=?,result=?,token=NULL WHERE id=?",
                           (time.time(), encoded(result), job_id))
                import history
                history.record(db, db.execute('SELECT id,spec,status,created,started,finished,peak_gb FROM jobs WHERE id=?',
                                              (job_id,)).fetchone())
                self.event(db, 'superseded', job_id)

    def mark_collected(self, request_id):
        with self.transaction() as db:
            db.execute('UPDATE requests SET collected=COALESCE(collected,?) WHERE id=?', (time.time(), request_id))

    def set_waiter(self, request_id, pid):
        with self.transaction() as db:
            db.execute('UPDATE requests SET waiter_pid=?,waiter_heartbeat=? WHERE id=?',
                       (pid, time.time() if pid else None, request_id))

    def agent_requests(self, agent_match=None):
        """Requests an agent still owes an answer on: unfinished, or finished but never collected.

        agent_match selects requests whose agent id ends in, or whose final
        ':'/'/' segment is a prefix of, the given session identifier. Agents
        write either the full session id or a short prefix of it.
        """
        with self.connect() as db:
            rows = [dict(r) for r in db.execute(
                'SELECT r.id,r.agent,r.job_id,r.active,r.created,r.collected,r.waiter_pid,r.waiter_heartbeat,'
                'j.status,j.started,j.finished,j.phase,j.spec FROM requests r JOIN jobs j ON j.id=r.job_id '
                'WHERE r.active=1 AND (j.status NOT IN (?,?,?,?) OR r.collected IS NULL) ORDER BY r.created',
                TERMINAL)]
        if agent_match:
            needle = agent_match.strip().lower()
            def matches(agent):
                agent = (agent or '').strip().lower()
                tail = re.split(r'[:/]', agent)[-1]
                # Agents also append a role, as in "claude:d37569e6-coord".
                prefixes = (tail, tail.split('-')[0])
                return bool(needle) and (agent.endswith(needle) or
                                         any(len(p) >= 8 and needle.startswith(p) for p in prefixes))
            rows = [r for r in rows if matches(r['agent'])]
        for row in rows:
            spec = json.loads(row.pop('spec') or '{}')
            row['tasks'] = [t.get('path') for t in spec.get('tasks', [])]
            row['task_specs'] = spec.get('tasks', [])
        return rows

    def result(self, request_id):
        with self.connect() as db:
            db.execute('BEGIN')  # Pin request and job reads to the same SQLite snapshot.
            row = db.execute('SELECT * FROM requests WHERE id=?', (request_id,)).fetchone()
            if row is None:
                raise ValueError('Unknown request')
            request = dict(row)
            request['spec'] = json.loads(request['spec'])
            request['job'] = self.decode_job(db.execute('SELECT * FROM jobs WHERE id=?', (row['job_id'],)).fetchone())
            attempts, current = [], request['job']
            while current:
                payload = current.get('result') or {}
                if not payload and current['status'] in TERMINAL:
                    try:
                        payload = self.archived_result(current['id']) or {}
                    except (OSError, ValueError):
                        payload = {}
                attempts.append({'job_id': current['id'], 'status': current['status'],
                                 'infrastructure': payload.get('infrastructure')})
                current = self.decode_job(db.execute('SELECT * FROM jobs WHERE id=?',
                                                     (current['predecessor'],)).fetchone()) if current.get('predecessor') else None
            request['attempt_history'] = list(reversed(attempts))
            if request['job'] and request['job']['result'] is None and request['job']['status'] in TERMINAL:
                request['job']['result'] = self.archived_result(row['job_id'])
            request['job']['log'] = str(self.state / 'results' / row['job_id'] / 'build.log')
            return request

    def events(self, after=0):
        with self.connect() as db:
            return [{**dict(r), 'data': json.loads(r['data'])} for r in db.execute('SELECT * FROM events WHERE seq>? ORDER BY seq LIMIT 1000', (after,))]

    def record_event(self, kind, **data):
        """Record an event that belongs to the host rather than to one job."""
        with self.transaction() as db:
            self.event(db, kind, **data)

    def metadata(self, key, value=None):
        if value is not None:
            with self.transaction() as db:
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', (key, encoded(value)))
        connection = self.connect(read_only=True) if getattr(self, '_existing_only', False) else self.connect()
        with connection as db:
            row = db.execute('SELECT value FROM metadata WHERE key=?', (key,)).fetchone()
            return json.loads(row['value']) if row else None

    def config(self, key, value=None):
        if value is not None:
            self.update_config({key: value})
            return value
        with self.connect() as db:
            row = db.execute('SELECT value FROM config WHERE key=?', (key,)).fetchone()
            if row is not None:
                try:
                    return json.loads(row['value'])
                except (ValueError, TypeError):
                    return row['value']
            if key == 'slots':
                baseline = self.metadata('slots')
                return baseline if type(baseline) is int and 1 <= baseline <= 8 else DEFAULT_CONFIG[key]
            return DEFAULT_CONFIG.get(key)

    def all_config(self):
        result = dict(DEFAULT_CONFIG)
        baseline = self.metadata('slots')
        if type(baseline) is int and 1 <= baseline <= 8:
            result['slots'] = baseline
        with self.connect() as db:
            for row in db.execute('SELECT key, value FROM config'):
                try:
                    result[row['key']] = json.loads(row['value'])
                except (ValueError, TypeError):
                    result[row['key']] = row['value']
        return result

    def update_config(self, mapping):
        validated = validate_config(mapping)
        with self.transaction() as db:
            for k, v in validated.items():
                db.execute('INSERT OR REPLACE INTO config VALUES(?,?)', (k, encoded(v)))

    def temp_whitelist_pids(self):
        pids = self.metadata('temp_whitelist_pids') or []
        alive_pids = []
        for p in pids:
            try:
                pid_int = int(p)
                if os.name == 'posix':
                    os.kill(pid_int, 0)
                alive_pids.append(pid_int)
            except (ProcessLookupError, ValueError):
                pass
            except PermissionError:
                alive_pids.append(int(p))
            except OSError:
                pass
        if len(alive_pids) != len(pids):
            self.metadata('temp_whitelist_pids', alive_pids)
        return alive_pids

    def add_temp_whitelist_pid(self, pid: int):
        current = set(self.temp_whitelist_pids())
        current.add(int(pid))
        self.metadata('temp_whitelist_pids', sorted(current))

    def remove_temp_whitelist_pid(self, pid: int):
        current = set(self.temp_whitelist_pids())
        current.discard(int(pid))
        self.metadata('temp_whitelist_pids', sorted(current))
