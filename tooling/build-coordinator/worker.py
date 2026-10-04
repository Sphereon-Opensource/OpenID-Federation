"""Single scheduling owner; lane jobs run in contained child processes."""
import concurrent.futures
import fnmatch
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from processes import (ExclusiveLock, OwnedProcess, available_memory_gb, external_builds,
                       pid_alive, reap_idle_gradle_daemons, boot_time, started_before_boot)
from snapshots import materialize, _json as snapshot_json, _relative as snapshot_relative
from store import ACTIVE, TERMINAL, identity, classify_task_tier, host_cpu_load, compute_dynamic_slots, native_request_contract, normalize_excluded_tasks

WINDOWS = os.name == 'nt'
WINDOWS_CRITICAL_PATH_LENGTH = 240
WINDOWS_GRADLE_PROTOC_SUFFIX = 120
DAEMON_LOSS_GRACE_SECONDS = 5.0
# Capture the companion script with this Python module, once per worker process.
# Late included-build configuration must never read a concurrently edited copy.
COORDINATOR_INIT_SCRIPT_BYTES = Path(__file__).with_name('events.gradle').read_bytes()

BUILD_ENV_NAMES = {'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP', 'TMPDIR',
                   'HOME', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA',
                   'PROGRAMDATA', 'PROGRAMFILES', 'PROGRAMFILES(X86)', 'COMMONPROGRAMFILES',
                   'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE', 'OS', 'JAVA_HOME',
                   'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'LANG', 'LC_ALL', 'TZ', 'CI',
                   'BUILD_XCFRAMEWORKS', 'SPHEREON_BUILD_PROFILE', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY'}


def build_environment(overrides=None, base_environment=None):
    # Agents have different API keys/session variables. They must neither split
    # compatible jobs nor leak into build processes. Other inputs are explicit.
    base = os.environ if base_environment is None else base_environment
    if not hasattr(base, 'items') or any(not isinstance(k, str) or not isinstance(v, str) for k, v in base.items()):
        raise ValueError('Canonical build environment must be a string map')
    env = {k.upper() if os.name == 'nt' else k: v for k, v in base.items() if k.upper() in BUILD_ENV_NAMES or k.upper().startswith(('USE_LOCAL_', 'ORG_GRADLE_PROJECT_'))}
    env.update({k.upper() if os.name == 'nt' else k: v for k, v in (overrides or {}).items()})
    return env


def runtime_identity(overrides=None, base_environment=None):
    env = build_environment(overrides, base_environment=base_environment)
    java_home = env.get('JAVA_HOME')
    java_name = 'java.exe' if os.name == 'nt' else 'java'
    if java_home:
        if not Path(java_home).is_absolute():
            raise ValueError('JAVA_HOME must be an absolute build input')
        java = str(Path(java_home) / 'bin' / java_name)
    else:
        entries = env['PATH'].split(os.pathsep) if env.get('PATH') else []
        if any(not entry or not Path(entry).is_absolute() for entry in entries):
            raise ValueError('Java PATH search entries must be absolute build inputs')
        # shutil.which on Windows consults caller PATHEXT and sometimes cwd.
        # Search only the exact Java executable in canonical absolute entries.
        java = next((str(Path(entry) / java_name) for entry in entries
                     if (Path(entry) / java_name).is_file()), None)
    if not java or not Path(java).is_file():
        raise ValueError('Java is unavailable; set JAVA_HOME before submitting')
    java = str(Path(java).resolve())
    release = Path(java).parent.parent / 'release'
    # Hash execution environment without storing inherited credentials in SQLite.
    relevant = {k.upper() if os.name == 'nt' else k: v for k, v in env.items()}
    return {'java': java, 'java_sha256': hashlib.sha256(Path(java).read_bytes()).hexdigest(),
            'release_sha256': hashlib.sha256(release.read_bytes()).hexdigest() if release.exists() else None,
            'environment_sha256': identity(relevant)}


def is_reparse_point(path):
    """Return whether *path* is a Windows reparse point or a link."""
    path = Path(path)
    try:
        if path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction()):
            return True
        if WINDOWS:
            # FILE_ATTRIBUTE_REPARSE_POINT is stable across supported Windows
            # versions and is exposed by stat on some Python builds only.
            return bool(getattr(os.lstat(path), 'st_file_attributes', 0) & 0x400)
    except FileNotFoundError:
        return False
    except OSError as error:
        raise ValueError(f'Unable to inspect build-cache path {path}: {error}') from error
    return False


def reject_reparse_ancestors(path, stop=None):
    """Reject links in an existing physical-cache path before using it."""
    candidate = Path(path)
    boundary = Path(stop) if stop is not None else None
    while True:
        if candidate.exists() and is_reparse_point(candidate):
            raise ValueError(f'Windows Gradle cache path contains a reparse point: {candidate}')
        if boundary is not None and candidate == boundary:
            break
        parent = candidate.parent
        if parent == candidate:
            break
        candidate = parent


def gradle_user_home(state, lane_number, user_home=None):
    """Return the isolated Gradle cache for a lane.

    Gradle resolves native tool dependencies such as ``protoc`` below this
    directory. The coordinator state lives under the Windows app-data tree,
    where the resulting executable path can exceed the Win32 CreateProcess
    limit even though the file exists. Windows therefore uses a persistent
    user-owned physical cache keyed by canonical state and lane; the canonical
    coordinator path remains the ownership and storage identity.
    """
    path = (Path(state) / 'lanes' / str(lane_number) / 'gradle-home').resolve()
    path.mkdir(parents=True, exist_ok=True)
    canonical = str(path)
    if not WINDOWS:
        return canonical
    state_key = hashlib.sha256(canonical.encode('utf-8')).hexdigest()[:32]
    home = Path(user_home).resolve() if user_home is not None else Path.home().resolve()
    physical = home / '.vdx-build-gradle' / state_key / f'lane-{lane_number}'
    projected = len(str(physical)) + WINDOWS_GRADLE_PROTOC_SUFFIX
    if projected >= WINDOWS_CRITICAL_PATH_LENGTH:
        raise ValueError(f'Windows Gradle home remains too long for native tool launch ({projected} characters projected)')
    reject_reparse_ancestors(physical, stop=home)
    try:
        if os.path.normcase(str(physical.resolve())) != os.path.normcase(str(physical)):
            raise ValueError(f'Windows Gradle cache path resolves through a reparse point: {physical}')
    except OSError as error:
        raise ValueError(f'Unable to resolve Windows Gradle cache path {physical}: {error}') from error
    physical.mkdir(parents=True, exist_ok=True)
    reject_reparse_ancestors(physical, stop=home)
    try:
        if os.path.normcase(str(physical.resolve())) != os.path.normcase(str(physical)):
            raise ValueError(f'Windows Gradle cache path resolves through a reparse point: {physical}')
    except OSError as error:
        raise ValueError(f'Unable to resolve Windows Gradle cache path {physical}: {error}') from error
    return str(physical)


def gradle_wrapper(lane, selected_root):
    """Use the closest wrapper within the immutable lane, without changing cwd."""
    lane = Path(lane).absolute()
    requested = lane / selected_root
    current = inside(requested, lane)
    reject_reparse_ancestors(requested, stop=lane)
    boundary = lane.resolve()
    while True:
        candidate = current / 'gradle/wrapper/gradle-wrapper.jar'
        jar = inside(candidate, lane)
        reject_reparse_ancestors(candidate, stop=lane)
        if jar.is_file():
            return jar
        if current == boundary:
            break
        current = current.parent
    raise ValueError(f'Gradle wrapper missing in snapshot root {selected_root} and its ancestors within the immutable lane')


def verify_workspace_tools(spec, lane):
    environment = spec.get('environment', {})
    repository = environment.get('WORKSPACE_TOOL_MAVEN_REPO')
    lock = environment.get('WORKSPACE_TOOL_INPUTS_LOCK')
    tool_id = spec.get('properties', {}).get('workspaceToolInputsId')
    if not any((repository, lock, tool_id)):
        return None
    if not repository or not lock or not tool_id or not re.fullmatch('[0-9a-f]{64}', tool_id):
        raise ValueError('A pinned workspace tool lock, repository and identity are required')
    helper = inside(lane / 'tooling/workspace-publish/tool_inputs.py', lane)
    script = inside(lane / 'tooling/workspace-publish/tool-repositories.gradle', lane)
    reject_reparse_ancestors(helper, stop=lane)
    reject_reparse_ancestors(script, stop=lane)
    if not helper.is_file() or not script.is_file():
        raise ValueError('Workspace tool lock requires its helpers in the immutable snapshot')
    loader = importlib.util.spec_from_file_location('coordinator_workspace_tools', helper)
    module = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(module)
    manifest = module.verify(lock)
    if manifest['id'] != tool_id or module.guarded(repository) != module.guarded(Path(lock).parent / 'repo'):
        raise ValueError('Workspace tool lock differs from the submitted repository or identity')
    return script


def native_host_target():
    import platform
    system = {'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
    arch = {'AMD64': 'amd64', 'x86_64': 'amd64', 'aarch64': 'arm64', 'ARM64': 'arm64'}.get(platform.machine())
    return system + '-' + arch if system and arch else None


def verify_native_inputs(spec, lane, store, snapshot):
    """Native admission proof; ordinary finite requests retain their contract."""
    contract = native_request_contract(spec)
    if contract is None:
        return None
    helper = inside(lane / 'tooling/workspace-runtime/native_artifacts.py', lane)
    catalog_path = inside(lane / 'tooling/workspace-runtime/product-catalogs.json', lane)
    reject_reparse_ancestors(helper, stop=lane)
    reject_reparse_ancestors(catalog_path, stop=lane)
    if not helper.is_file() or not catalog_path.is_file():
        raise ValueError('Native preparation requires captured verifier and catalog')
    loader = importlib.util.spec_from_file_location('coordinator_native_inputs', helper)
    module = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(module)
    catalog = module._read_json(catalog_path)
    finite = module.native_producer_contract(catalog, contract['product'], [contract['role']])
    if finite['tasks'] != [dict(path=contract['producer'])]:
        raise ValueError('Native producer differs from the exact catalog role')
    environment = spec['environment']
    descriptor_path = module._guarded(environment['WORKSPACE_NATIVE_INPUTS_FILE'])
    if module._sha_file(descriptor_path) != environment['WORKSPACE_NATIVE_INPUTS_SHA256']:
        raise ValueError('Immutable native descriptor checksum mismatch')
    descriptor = module._read_json(descriptor_path)
    if (not isinstance(descriptor, dict) or descriptor.get('fixtureOnly')
            or descriptor.get('nativeSourceSnapshot') != spec['snapshot']):
        raise ValueError('Native descriptor must bind the registered current source snapshot')
    inputs = descriptor.get('inputs')
    module.native_input_id(inputs)
    if inputs['target'] != environment['WORKSPACE_NATIVE_TARGET'] or inputs['target'] != native_host_target():
        raise ValueError('Native target requires a matching explicit host/toolchain; container execution is not implemented')
    from snapshots import _verify
    captured = _verify(Path(snapshot['path']))
    if captured['id'] != spec['snapshot']:
        raise ValueError('Native source snapshot registration mismatch')
    files = {item['path']: item['sha256'] for item in captured['files']}
    configs = finite['workloads'][0]['nativeConfigInputs']
    configuration_files = module.native_config_files(configs, files)
    if (not {'tooling/workspace-runtime/native_artifacts.py',
             'tooling/workspace-runtime/product-catalogs.json'} <= set(files)
            or descriptor.get('nativeConfigFiles') != configuration_files):
        raise ValueError('Native descriptor configuration differs from captured producer inputs')
    jar_record = descriptor.get('serviceJar')
    if not isinstance(jar_record, dict) or module._guarded(jar_record.get('coordinatorState', '')) != module._guarded(store.state):
        raise ValueError('Native preparation requires its registered coordinator JAR archive')
    jar_job = store.job(jar_record.get('jobId', ''))
    assembly = contract['module'].removesuffix('-native')
    if (not isinstance(jar_job, dict) or jar_job.get('status') != 'passed'
            or jar_job['spec'].get('root', '.') != '.'
            or not any(item.get('path') == ':' + assembly + ':fatJar' for item in jar_job['spec']['tasks'])
            or jar_record.get('snapshot') != jar_job['spec']['snapshot']):
        raise ValueError('Native JAR requires a registered passed exact fatJar producer/snapshot')
    jar_snapshot = store.snapshot(jar_job['spec']['snapshot'])
    jar_manifest = _verify(Path(jar_snapshot['path']))
    if jar_manifest['id'] != jar_record['snapshot'] or jar_manifest['source'] != captured['source']:
        raise ValueError('Native JAR source snapshot belongs to another workspace')
    verified = module.verify_native_bridge(descriptor_path, environment['WORKSPACE_NATIVE_INPUTS_SHA256'],
        producer=contract['producer'], native_args=inputs['nativeArgs'], graalvm_home=environment['GRAALVM_HOME'])
    return dict(verified, outputPrefix=contract['outputPrefix'],
                nativeSourceSnapshot=spec['snapshot'], jarSnapshot=jar_record['snapshot'])


def verify_repository_layers(spec, lane):
    environment = spec.get('environment', {})
    encoded = environment.get('WORKSPACE_REPOSITORY_POLICY')
    policy_file = environment.get('WORKSPACE_REPOSITORY_POLICY_FILE')
    digest = environment.get('WORKSPACE_REPOSITORY_POLICY_SHA256')
    if not any((encoded, policy_file, digest)):
        return None
    if any(environment.get(name) for name in ('WORKTREE_MAVEN_REPO', 'WORKSPACE_MAVEN_REPO')):
        raise ValueError('Exact repository routing requires a bounded policy without broad worktree filtering')
    if policy_file or digest:
        if encoded or not policy_file or not digest or not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('Exact repository policy requires one file and its immutable digest')
        path = Path(policy_file)
        if not path.is_absolute():
            raise ValueError('Exact repository policy file must be absolute')
        reject_reparse_ancestors(path)
        if not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError('Exact repository policy file exceeds bounded size or is missing')
        payload = path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != digest:
            raise ValueError('Exact repository policy file changed after submission')
        encoded = payload.decode('utf-8')
    elif len(encoded.encode('utf-8')) > 20000:
        raise ValueError('Exact repository policy exceeds bounded request size')
    policy = json.loads(encoded)
    if set(policy) != {'schemaVersion', 'components', 'files'} or policy['schemaVersion'] != 1 or not isinstance(policy['components'], dict) or not isinstance(policy['files'], list):
        raise ValueError('Unsupported repository routing policy')
    for coordinate, origin in policy['components'].items():
        if not re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+', coordinate):
            raise ValueError('Invalid exact Maven coordinate')
        if coordinate.startswith(('com.sphereon.gradle:', 'com.sphereon.gradle.', 'software.amazon.app.platform')):
            raise ValueError('Build tools must retain their separate frozen repository')
        if not isinstance(origin, str) or not Path(origin).is_absolute():
            raise ValueError('Repository origin must be absolute')
        reject_reparse_ancestors(Path(origin))
    for record in policy['files']:
        if record.get('repository') not in policy['components'].values():
            raise ValueError('Artifact receipt origin is absent from repository routing')
        relative = snapshot_relative(record['path'])
        artifact = inside(Path(record['repository']) / relative, Path(record['repository']))
        reject_reparse_ancestors(artifact)
        if not artifact.is_file() or artifact.stat().st_size != record.get('size') or hashlib.sha256(artifact.read_bytes()).hexdigest() != record.get('sha256'):
            raise ValueError('Selected Maven artifact changed after planning: ' + record['path'])
    script = inside(lane / 'tooling/workspace-publish/repository-layers.gradle', lane)
    if not script.is_file():
        raise ValueError('Exact repository routing helper is absent from captured inputs')
    return script


def gradle_command(spec, lane, init_script, runtime):
    jar = gradle_wrapper(lane, spec['root'])
    argv = [runtime['java'], '-Xmx64m', '-Dorg.gradle.workers.max=1',
            '-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process', '-classpath', str(jar), 'org.gradle.wrapper.GradleWrapperMain']
    for task in spec['tasks']:
        argv.append(task['path'])
        for pattern in task['tests']:
            argv.extend(['--tests', pattern])
    excluded = normalize_excluded_tasks(spec.get('excluded_tasks', []), spec['tasks'])
    for selector in excluded:
        if selector == 'koverVerify':
            # Optional CI coverage task: Gradle rejects -x when the task is absent.
            argv.append('-Dvdx.build.exclude-kover=true')
        else:
            argv.extend(['--exclude-task', selector])
    argv.extend(['--no-daemon', '--no-parallel', '--max-workers=1', '--console=plain', '--init-script', str(init_script)])
    if spec.get('offline', False):
        argv.append('--offline')
    if spec.get('owned_selection') is not None:
        names = spec['owned_selection'].get('sourceModules', {}).get(spec['root'])
        selected = sorted(set(spec['environment'].get('WORKSPACE_SOURCE_MODULES', '').split(',')))
        if not names or selected != names:
            raise ValueError('Owned Gradle execution requires the exact captured source module selection')
        source_guard = inside(lane / 'tooling/workspace-publish/selected-sources.gradle', lane)
        if not source_guard.is_file():
            raise ValueError('Owned source selection guard is absent')
        argv.extend(['--init-script', str(source_guard)])
    tools_script = verify_workspace_tools(spec, lane)
    if tools_script:
        argv.extend(['--init-script', str(tools_script)])
    repository_script = verify_repository_layers(spec, lane)
    if repository_script:
        argv.extend(['--init-script', str(repository_script)])
    if configuration_cache_enabled(spec):
        argv.extend(['--configuration-cache', '--configuration-cache-problems=fail'])
    else:
        argv.append('--no-configuration-cache')
    if any(task['path'].split(':')[-1] == 'prepareWorkspaceArtifacts' for task in spec['tasks']):
        publisher = inside(lane / 'tooling/workspace-publish/prepare-artifacts.gradle', lane)
        if not publisher.is_file():
            raise ValueError('prepareWorkspaceArtifacts requires tooling/workspace-publish/prepare-artifacts.gradle '
                             'in the immutable snapshot; capture a checkout containing the publisher helper')
        argv.extend(['--init-script', str(publisher)])
    argv.append('-Dorg.gradle.workers.max=1')  # Repo auto-tuner checks system property.
    argv.extend(['-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process', '-Pkotlin.compiler.execution.strategy=in-process'])
    if spec['profile'] == 'fresh':
        argv.extend(['--no-build-cache', '--rerun-tasks'])
    else:
        argv.append('--build-cache')
    argv.extend('-P' + k + '=' + v for k, v in sorted(spec['properties'].items()))
    return argv


def configuration_cache_enabled(spec):
    mode = spec.get('configuration_cache', 'auto')
    if mode not in ('auto', 'off'):
        raise ValueError('configuration_cache must be auto or off')
    return mode == 'auto' and spec['profile'] != 'fresh'


def snapshot_repository_provenance(snapshot):
    """Read Git identity from the manifest already verified by materialize.

    Authenticate the manifest again without rehashing every source file. Never
    use caller environment or mutable checkout Git metadata for a pinned build.
    Only repository identity goes into the cache input, not job/snapshot IDs.
    """
    directory = Path(snapshot['path'])
    manifest_path = directory / 'manifest.json'
    inside(manifest_path, directory)
    reject_reparse_ancestors(manifest_path, stop=directory)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if not isinstance(manifest, dict):
        raise ValueError('Invalid snapshot provenance manifest')
    body = {key: value for key, value in manifest.items() if key != 'id'}
    if (manifest.get('id') != snapshot['id'] or manifest.get('version') != 1 or
            hashlib.sha256(snapshot_json(body)).hexdigest() != snapshot['id']):
        raise ValueError('Snapshot provenance manifest identity mismatch')
    repositories = manifest.get('repositories')
    if not isinstance(repositories, list) or not repositories:
        raise ValueError('Snapshot repository provenance is missing')
    result, seen = [], set()
    for repository in repositories:
        if not isinstance(repository, dict):
            raise ValueError('Invalid snapshot repository provenance')
        path, head, branch = (repository.get(key) for key in ('path', 'head', 'branch'))
        if path != '.':
            snapshot_relative(path)
        key = os.path.normcase(path)
        if key in seen:
            raise ValueError('Duplicate snapshot repository provenance path')
        seen.add(key)
        if head is not None and (not isinstance(head, str) or not re.fullmatch(r'(?:[a-f0-9]{40}|[a-f0-9]{64})', head)):
            raise ValueError('Invalid snapshot repository HEAD')
        if branch is not None and (not isinstance(branch, str) or not branch or
                                   any(ord(char) < 32 or ord(char) == 127 for char in branch)):
            raise ValueError('Invalid snapshot repository branch')
        result.append({'path': path, 'head': head, 'branch': branch})
    if '.' not in seen:
        raise ValueError('Snapshot root repository provenance is missing')
    return sorted(result, key=lambda repository: repository['path'])


def configuration_cache_result(log):
    """Inspect bounded log chunks; this records observations, not task acceptance."""
    found = set()
    markers = {'reused': 'reusing configuration cache.', 'stored': 'configuration cache entry stored',
               'discarded': 'configuration cache entry discarded',
               'problem': 'configuration cache problems found in this build',
               'serialization_problem': 'configuration cache state could not be cached',
               'task_output': '> task '}
    carry = ''
    if Path(log).exists():
        with Path(log).open('r', encoding='utf-8', errors='replace') as stream:
            while chunk := stream.read(65536):
                text = carry + chunk.lower()
                found.update(key for key, marker in markers.items() if marker in text)
                carry = text[-128:]
    return found


def configuration_cache_fallback_allowed(log, event_path, exit_code):
    """Only a failed cache serialization before any task work permits a retry.

    Any event bytes (including malformed/partial records) or Gradle task output
    deny fallback. This deliberately refuses to replay buildSrc/included-build
    tasks or execution-time cache violations, even when the main graph did not run.
    """
    if exit_code == 0 or (Path(event_path).exists() and Path(event_path).stat().st_size):
        return False
    observed = configuration_cache_result(log)
    return ('discarded' in observed and 'task_output' not in observed and
            bool(observed & {'problem', 'serialization_problem'}))


def reset_event_spool(path, state):
    """Start an empty lane-owned stream while the exclusive lane lock is held."""
    path, state = Path(path), Path(state)
    inside(path, state)
    reject_reparse_ancestors(path, stop=state)
    # Unlink, rather than truncate, so even an old hard link cannot change a
    # previously archived invocation. Exclusive creation fails closed on races.
    path.unlink(missing_ok=True)
    with path.open('xb'):
        pass


def archive_event_spool(path, destination, state):
    """Publish only after the contained process tree and its file handles drain."""
    path, destination, state = Path(path), Path(destination), Path(state)
    inside(path, state)
    inside(destination, state)
    reject_reparse_ancestors(path, stop=state)
    reject_reparse_ancestors(destination, stop=state)
    shutil.copy2(path, destination)


def pin_init_script(path, contents, state):
    """Atomically install this worker's runtime bytes at the stable lane path."""
    path, state = Path(path), Path(state)
    inside(path, state)
    reject_reparse_ancestors(path, stop=state)
    if path.is_file() and path.stat().st_size == len(contents) and path.read_bytes() == contents:
        return
    descriptor, temporary = tempfile.mkstemp(prefix='.events-', suffix='.gradle', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def inside(path, root):
    path = Path(path)
    resolved = path.resolve()
    resolved.relative_to(root.resolve())
    for parent in [path, *path.parents]:
        if parent == root.parent:
            break
        if parent.is_symlink() or (hasattr(parent, 'is_junction') and parent.is_junction()):
            raise ValueError('Evidence path contains a link')
    return resolved


def read_events(path):
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        try:
            value = json.loads(line)
            if isinstance(value, dict):
                rows.append(value)
        except json.JSONDecodeError:
            continue  # A partial last line is normal while a process is running.
    return rows


def match_task(requested, event):
    return ':' + requested.lstrip(':') == event.get('identity', event['path'])


def case_matches(case, pattern):
    """Match a requested Gradle filter without discarding raw report names.

    Kotlin Multiplatform's JVM test report appends the exact ``()[jvm]`` or
    ``[jvm]`` execution suffix to method names.  Keep that display name in
    evidence, while also exposing the one canonical method spelling to
    Gradle-style filters.  Other parameterized or engine-specific names
    remain unchanged.
    """
    classname = case.get('class', '')
    name = case.get('name', '')
    candidates = [classname, f'{classname}.{name}']
    for suffix in ('()[jvm]', '[jvm]'):
        if name.endswith(suffix):
            candidates.append(f'{classname}.{name[:-len(suffix)]}')
            break
    return any(fnmatch.fnmatchcase(candidate, pattern) for candidate in candidates)


def evaluate(tasks, requested):
    issues = []
    for wanted in requested:
        candidates = [t for t in tasks if match_task(wanted['path'], t)]
        if len(candidates) != 1:
            issues.append(f'{wanted["path"]}: expected one task result, got {len(candidates)}')
            continue
        task = candidates[0]
        issues.extend(task.get('issues', []))
        needs_tests = bool(wanted['tests']) or wanted['path'].lower().endswith(('test', 'tests'))
        if task['outcome'] not in ('EXECUTED', 'UP-TO-DATE', 'FROM-CACHE'):
            issues.append(f'{wanted["path"]}: {task["outcome"]}')
        if needs_tests:
            cases = task.get('cases', [])
            if task['outcome'] != 'EXECUTED' or not any(not c['skipped'] for c in cases):
                issues.append(f'{wanted["path"]}: no fresh executed test cases')
            for pattern in wanted['tests']:
                if not any(not c['skipped'] and case_matches(c, pattern) for c in cases):
                    issues.append(f'{wanted["path"]}: no executed cases match {pattern}')
            if any(c['failed'] for c in cases):
                issues.append(f'{wanted["path"]}: test failures')
    return issues


def _parse_test_xml(report):
    cases = []
    totals = dict(executed=0, failures=0, errors=0, skipped=0)
    tree = ET.parse(report)
    for case in tree.iter('testcase'):
        failed = case.find('failure') is not None or case.find('error') is not None
        skipped = case.find('skipped') is not None
        cases.append({'class': case.get('classname', ''), 'name': case.get('name', ''), 'failed': failed, 'skipped': skipped})
        totals['executed'] += not skipped
        totals['skipped'] += skipped
        totals['failures'] += case.find('failure') is not None
        totals['errors'] += case.find('error') is not None
    return cases, totals


def collect_evidence(lane, event_path, destination, exit_code, requested, artifact_patterns, min_mtime_ns=0):
    lane, destination = Path(lane).resolve(), Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    tasks, artifacts, issues, artifact_issues = [], [], [], {}
    totals = dict(executed=0, failures=0, errors=0, skipped=0)
    seen_xml = set()
    for event in read_events(Path(event_path)):
        if event.get('event') != 'task':
            continue
        task = dict(event, cases=[], reports=[], issues=[])
        for directory in event.get('xml', []):
            directory = inside(directory, lane)
            for report in directory.glob('TEST-*.xml'):
                report = inside(report, lane)
                if report in seen_xml or report.stat().st_mtime_ns < min_mtime_ns or event['outcome'] not in ('EXECUTED', 'FAILED'):
                    continue
                seen_xml.add(report)
                target = destination / 'reports' / report.relative_to(lane)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(report, target)
                task['reports'].append(str(target))
                try:
                    cases, report_totals = _parse_test_xml(target)
                    task['cases'].extend(cases)
                    for name, count in report_totals.items():
                        totals[name] += count
                except ET.ParseError:
                    issue = f'Invalid test XML: {report.relative_to(lane)}'
                    task['issues'].append(issue)
                    issues.append(issue)
        tasks.append(task)
    # The contained process has drained and the source barrier is still held.
    # Verify each declared output once, not once for every matching artifact.
    producer_outputs = []
    checked_outputs = {}
    if artifact_patterns:
        for task in tasks:
            if task['outcome'] not in ('EXECUTED', 'UP-TO-DATE', 'FROM-CACHE'):
                continue
            declared_outputs = []
            for output in task.get('outputs', []):
                if output not in checked_outputs:
                    try:
                        declared = inside(output, lane)
                        checked_outputs[output] = (declared, declared.is_dir())
                    except ValueError:
                        checked_outputs[output] = None
                if checked_outputs[output] is not None:
                    declared_outputs.append(checked_outputs[output])
            producer_outputs.append((task, declared_outputs))
    seen_artifacts = set()
    for pattern in artifact_patterns:
        matches = [p for p in lane.glob(pattern) if p.is_file()]
        if not matches:
            issues.append(f'No artifacts matched {pattern}')
            artifact_issues[pattern] = [f'No artifacts matched {pattern}']
        for source in matches:
            source = inside(source, lane)
            producers = []
            source_parents = frozenset(source.parents)
            for task, declared_outputs in producer_outputs:
                if any(source == declared or (is_directory and declared in source_parents)
                       for declared, is_directory in declared_outputs):
                    producers.append(task)
            if not producers:
                issue = f'No successful current-build producer declares artifact {source.relative_to(lane)}'
                issues.append(issue)
                artifact_issues.setdefault(pattern, []).append(issue)
                continue
            if source in seen_artifacts:
                continue
            seen_artifacts.add(source)
            target = destination / 'artifacts' / source.relative_to(lane)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            with target.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            artifacts.append({'path': str(target), 'relative': source.relative_to(lane).as_posix(), 'sha256': digest,
                              'producer': producers[0].get('identity', producers[0]['path']), 'producer_outcome': producers[0]['outcome']})
    issues.extend(evaluate(tasks, requested))
    if exit_code != 0:
        issues.append(f'Gradle exited {exit_code}')
    return dict(accepted=not issues, exit_code=exit_code, tasks=tasks, tests=totals, artifacts=artifacts, issues=issues, artifact_issues=artifact_issues)


def request_result(request):
    job = request['job']
    report = job.get('result') or {}
    tasks = [t for t in report.get('tasks', []) if any(match_task(w['path'], t) for w in request['spec']['tasks'])]
    selected = []
    for task in tasks:
        filters = [w['tests'] for w in request['spec']['tasks'] if match_task(w['path'], task)]
        patterns = [p for group in filters for p in group]
        cases = task.get('cases', [])
        if filters and all(filters):
            cases = [c for c in cases if any(case_matches(c, p) for p in patterns)]
        selected.append({**task, 'cases': cases})
    tasks = selected
    issues = evaluate(tasks, request['spec']['tasks']) if job['status'] in TERMINAL else []
    # A failed shared invocation may still prove an independently completed task.
    status = job['status']
    if status in ('passed', 'failed'):
        status = 'failed' if issues else 'passed'
        if not report.get('tasks'):
            status = 'failed'
            issues.extend(report.get('issues', []))
    if not request['active']:
        status = 'unsubscribed'
    cases = [c for t in tasks for c in t.get('cases', [])]
    artifacts = [a for a in report.get('artifacts', []) if any(fnmatch.fnmatchcase(a['relative'], p) for p in request['spec']['artifacts'])]
    for pattern in request['spec']['artifacts']:
        if status in ('passed', 'failed') and report.get('artifact_issues', {}).get(pattern):
            issues.extend(report['artifact_issues'][pattern])
            status = 'failed'
        if status in ('passed', 'failed') and not any(fnmatch.fnmatchcase(a['relative'], pattern) for a in artifacts):
            issues.append(f'No archived artifacts matched {pattern}')
            status = 'failed'
    reused = None
    if job['spec']['snapshot'] != request['spec']['snapshot']:
        reused = {
            'mode': 'later-snapshot-superset',
            'requested_snapshot': request['spec']['snapshot'],
            'build_snapshot': job['spec']['snapshot'],
            'reason': 'build snapshot contains all requested source inputs',
        }
    return dict(status=status, request_id=request['id'], job_id=job['id'], started=job.get('started'), finished=job.get('finished'), snapshot=job['spec']['snapshot'],
                requested_snapshot=request['spec']['snapshot'], excluded_tasks=request['spec'].get('excluded_tasks', []), reuse=reused, log=report.get('log', job.get('log')), tasks=tasks,
                tests={'executed': sum(not c['skipped'] for c in cases), 'failures': sum(c['failed'] for c in cases), 'skipped': sum(c['skipped'] for c in cases)},
                artifacts=artifacts, issues=issues, build_status=job['status'],
                infrastructure=report.get('infrastructure'),
                attempts=report.get('attempts') or request.get('attempt_history', []),
                crash_artifacts=report.get('crash_artifacts'))


def _gradle_failure_summary(log):
    """Return only Gradle's structured failure-summary section, if present."""
    content = Path(log).read_text(encoding='utf-8', errors='replace') if Path(log).is_file() else ''
    match = re.search(r'^\s*\*\s+What went wrong:\s*$', content, re.IGNORECASE | re.MULTILINE)
    if not match:
        return ''
    end = re.search(r'^\s*\*\s+Try:\s*$', content[match.end():], re.IGNORECASE | re.MULTILINE)
    return content[match.end():match.end() + end.start() if end else len(content)].lower()


def _ordinary_failure(summary, task_events, task_results):
    compile_markers = ('compilation error', 'compilation failed', 'could not compile',
                       'failed to compile', 'e: file:', 'unresolved reference', 'cannot find symbol')
    test_markers = ('there were failing tests',)
    config_markers = ('a problem occurred configuring', 'a problem occurred evaluating',
                      'script compilation errors', 'could not compile build file',
                      'could not compile settings file', 'could not configure',
                      'could not resolve plugin', 'plugin [id:', 'plugin with id',
                      'failed to apply plugin', 'could not create task', 'unknown property',
                      'could not find method')
    if any(marker in summary for marker in (*compile_markers, *test_markers, *config_markers)):
        return True
    if any(event.get('outcome') == 'FAILED' and
           ('test' in event.get('identity', event.get('path', '')).lower())
           for event in task_events) and any(
               case.get('failed') for task in task_results for case in task.get('cases', [])):
        return True
    return False


def _crash_artifact_oom_evidence(crash_artifacts):
    """Read OOM headers only from artifacts recorded in this job's archive."""
    if not isinstance(crash_artifacts, dict) or not crash_artifacts.get('archive'):
        return []
    archive = Path(crash_artifacts['archive'])
    try:
        manifest = json.loads((archive / 'recovery.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return []
    expected = {(item.get('path'), item.get('job', {}).get('id'))
                for item in crash_artifacts.get('artifacts', [])}
    headers = ('there is insufficient memory for the java runtime environment',
               'native memory allocation (malloc) failed', 'java.lang.outofmemoryerror')
    evidence = []
    for item in manifest.get('artifacts', []):
        if (item.get('relative_path'), item.get('job', {}).get('id')) not in expected:
            continue
        path = item.get('relative_path', '')
        if not re.fullmatch(r'(?:source|gradle-workers)/.*(?:hs_err_pid\d+\.log|replay_pid\d+\.log|java_pid\d+\.hprof)', path, re.IGNORECASE):
            continue
        try:
            content = (archive / item['archive_file']).read_bytes().decode('utf-8', errors='replace').lower()
        except (OSError, KeyError):
            continue
        evidence.extend(f'crash artifact {path}: {header}' for header in headers if header in content)
    return list(dict.fromkeys(evidence))


def infrastructure_failure(log, task_events, crash_artifacts=None, daemon_kind=None, task_results=()):
    """Classify only structured Gradle, watchdog, or attributed JVM-crash evidence."""
    summary = _gradle_failure_summary(log)
    # User-code and configuration failures take precedence over all infrastructure signals.
    if _ordinary_failure(summary, task_events, task_results):
        return None

    evidence = []
    kinds = []
    summary_signals = (
        ('daemon-lost', 'gradle build daemon disappeared unexpectedly'),
        ('worker-daemon-lost', 'failed to run gradle worker daemon'),
    )
    for kind, phrase in summary_signals:
        if phrase in summary:
            kinds.append(kind)
            evidence.append(phrase)
    if re.search(r"process ['\"]gradle worker daemon \d+['\"] finished with non-zero exit value", summary):
        kinds.append('worker-daemon-lost')
        evidence.append('Gradle Worker Daemon finished with non-zero exit value')
    for phrase in ('java.lang.outofmemoryerror', 'insufficient memory'):
        if phrase in summary:
            kinds.append('oom')
            evidence.append(phrase)
    artifact_evidence = _crash_artifact_oom_evidence(crash_artifacts)
    if artifact_evidence:
        kinds.append('oom')
        evidence.extend(artifact_evidence)
    if daemon_kind == 'daemon-lost':
        kinds.append('daemon-lost')
        evidence.append('watchdog detected Gradle daemon loss')
    if not kinds:
        return None
    # OOM artifacts/summary take precedence, then worker loss, then the build daemon.
    kind = 'oom' if 'oom' in kinds else ('worker-daemon-lost' if 'worker-daemon-lost' in kinds else 'daemon-lost')
    return {'kind': kind, 'evidence': list(dict.fromkeys(evidence))}


def finalize_orphan(store, job_id, issue='Coordinator restarted; previous execution is unverified'):
    """Finalize a job with no surviving process tree and archive its proven crash files.

    Returns False when the job was already terminal, so concurrent callers are harmless.
    """
    job = store.job(job_id)
    if job['status'] not in ACTIVE:
        return False
    lane = store.state / 'lanes' / str(job['lane']) / 'source'
    output = store.state / 'results' / job['id']
    snapshot = store.snapshot(job['spec']['snapshot'])
    if snapshot.get('execution', {}).get('mode') == 'owned-worktree':
        if Path(snapshot['source']).resolve() != Path(store.metadata('source')).resolve():
            raise ValueError('Interrupted owned worktree source mismatch')
        lane = Path(snapshot['source'])
    log = output / 'build.log'
    result = {'log': str(log), 'excluded_tasks': job['spec'].get('excluded_tasks', []), 'issues': [issue], 'tasks': []}
    infra = None
    if job.get('lane') is not None:
        home = Path(gradle_user_home(store.state, job['lane']))
        try:
            with ExclusiveLock(store.state / 'lanes' / str(job['lane']) / 'lane.lock'):
                if store.job(job_id)['status'] not in ACTIVE:
                    return False
                finished = time.time()
                from snapshots import archive_job_crash_artifacts
                crash = archive_job_crash_artifacts(
                    lane, home, store.state / 'recovered-crash-artifacts', job['lane'],
                    {**job, 'started': job.get('started') or job['created'], 'finished': finished, 'status': 'interrupted'})
                task_events = [e for e in read_events(output / 'events.jsonl') if e.get('event') == 'task']
                infra = infrastructure_failure(log, task_events, crash)
                result.update(crash_artifacts=crash, infrastructure=infra,
                              attempts=store.attempt_history(job['id'], 'interrupted', infra))
        except Exception as error:
            result['issues'].append(f'Interrupted-job crash artifact handling failed: {error}')
            infra = None
    output.mkdir(parents=True, exist_ok=True)
    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    try:
        store.finish(job['id'], 'interrupted', result)
    except ValueError:
        return False  # Another caller finalized it first.
    if infra:
        replacement = store.requeue_infrastructure(job['id'])
        if replacement:
            store.record_event('infrastructure-retry-scheduled', job_id=job['id'], replacement=replacement,
                               infrastructure_kind=infra['kind'])
    return True


class Worker:
    def __init__(self, store, source, slots=1, command_builder=gradle_command):
        if type(slots) is not int or not 1 <= slots <= 8:
            raise ValueError('Between one and eight lanes are supported')
        self.store, self.source = store, Path(source).resolve()
        self.base_environment = store.metadata('build_environment')
        if self.base_environment is None:
            raise ValueError('Canonical build environment is missing; run init before starting the worker')
        self.base_environment = build_environment(base_environment=self.base_environment)
        self.slots = slots
        self.command_builder = command_builder
        self.init_script_bytes = COORDINATOR_INIT_SCRIPT_BYTES
        self.init_script_sha256 = hashlib.sha256(self.init_script_bytes).hexdigest()

    def close_owned(self, process, job_id):
        while True:
            try:
                process.close()
                return
            except (TimeoutError, OSError) as error:
                # Keep the lane lock, job and ownership handles. Never turn a
                # timeout into a reusable lane while descendants may be alive.
                self.store.phase(job_id, 'containment-blocked')
                self.store.metadata('blocked', {'reason': 'Owned process tree has not drained; lane retained', 'job': job_id})
                time.sleep(1)

    FAIR_SHARE_PATIENCE = 90

    def fair_share_ahead(self, registry):
        """The workspace whose turn it is, when that is not this one.

        Waiting is bounded: if the preferred workspace has not taken a slot
        within FAIR_SHARE_PATIENCE seconds (its jobs may not fit, or its
        worker may be stuck), this worker proceeds rather than idle a slot.
        """
        if registry is None:
            return None
        try:
            order = registry.fair_share_order()
        except (sqlite3.Error, OSError, ValueError):
            return None
        mine = str(self.store.state.resolve())
        if not order or order[0]['state'] == mine or all(c['state'] != mine for c in order):
            self.fair_share_waiting_since = None
            return None
        now = time.time()
        since = getattr(self, 'fair_share_waiting_since', None) or now
        self.fair_share_waiting_since = since
        return order[0] if now - since < self.FAIR_SHARE_PATIENCE else None

    def finish_orphan(self, header):
        finalize_orphan(self.store, header['id'])

    def close_pre_boot_orphans(self, orphans):
        """Finalize jobs whose processes cannot have survived the last host boot.

        The external-process preflight exists to prove a dead worker's tree is
        gone. A reboot already proves that, so these jobs must not wait on
        drain state or unrelated Java processes.
        """
        boot = boot_time()
        remaining = []
        for orphan in orphans:
            job = self.store.job(orphan['id'])
            if started_before_boot(job, boot):
                finalize_orphan(self.store, job['id'])
            else:
                remaining.append(orphan)
        return remaining


    def run_job(self, job):
        store = self.store
        output = store.state / 'results' / job['id']
        lane = store.state / 'lanes' / str(job['lane']) / 'source'
        event_path, log = output / 'events.jsonl', output / 'build.log'
        event_spool = lane.parent / 'events.jsonl'
        pinned_init = lane.parent / 'events.gradle'
        spool_initialized = False
        process = None
        lane_lock = None
        owned_lock = None
        run_started = None
        gradle_home = None
        native_inputs = None
        crash = {'archive': None, 'artifacts': []}
        terminal_recording_pending = False
        try:
            lane_lock = ExclusiveLock(store.state / 'lanes' / str(job['lane']) / 'lane.lock')
            lane_lock.__enter__()
            output.mkdir(parents=True, exist_ok=False)
            snapshot = store.snapshot(job['spec']['snapshot'])
            owned = snapshot.get('execution', {}).get('mode') == 'owned-worktree'
            if owned:
                # Edit leases and owned builds use the same barrier for the
                # entire process/evidence interval, not just capture.
                owned_lock = ExclusiveLock(store.state / 'capture.lock')
                owned_lock.__enter__()
                with store.connect() as db:
                    if db.execute('SELECT 1 FROM writers LIMIT 1').fetchone():
                        raise ValueError('Owned build has active source writers')
                from owned_inputs import verify_owned
                lane = Path(snapshot['source'])
                verify_owned(snapshot, lane, store.state)
            else:
                materialize(Path(snapshot['path']), lane)
            repositories = snapshot_repository_provenance(snapshot)
            spec = job['spec']
            if owned:
                spec = dict(spec, owned_selection=snapshot['execution']['selection'])
            runtime = runtime_identity(spec['environment'], base_environment=self.base_environment) if spec['runtime'] else {}
            if spec['runtime'] and runtime != spec['runtime']:
                raise ValueError('Execution environment or Java changed since submission; resubmit')
            env = build_environment(spec['environment'], base_environment=self.base_environment)
            # Auxiliary builds inherit environment but not project properties.
            # Derive this selector from the accepted root, never caller input.
            env['WORKSPACE_PREPARATION_ROOT_DIR'] = str(inside(lane / spec['root'], lane))
            env.update(VDX_BUILD_STATE=str(store.state), VDX_BUILD_JOB=job['id'], VDX_BUILD_TOKEN=job['token'],
                       VDX_BUILD_EVENTS_FILE=str(event_spool), VDX_BUILD_LANE=str(lane),
                       VDX_BUILD_CACHE=str(store.state / 'build-cache'),
                       VDX_BUILD_REPOSITORIES=json.dumps(repositories, sort_keys=True, separators=(',', ':')),
                       VDX_BUILD_PYTHON=sys.executable, VDX_BUILD_GATE=str(Path(__file__).with_name('task_gate.py')),
                       GRADLE_USER_HOME=gradle_user_home(store.state, job['lane']))
            native_inputs = verify_native_inputs(spec, lane, store, snapshot)
            if native_inputs is not None:
                env['VDX_BUILD_NATIVE_PRODUCER'] = native_inputs['producer']
            pin_init_script(pinned_init, self.init_script_bytes, store.state)
            (output / 'events.gradle').write_bytes(self.init_script_bytes)
            argv = self.command_builder(spec, lane, pinned_init, runtime)
            if store.job(job['id'])['status'] == 'cancelling':
                store.finish(job['id'], 'superseded', {'log': str(log), 'issues': ['Replaced during preparation']})
                return
            if not store.has_active_subscribers(job['id']):
                store.finish(job['id'], 'superseded',
                             {'log': str(log), 'issues': ['No active subscribers; build was not started'], 'tasks': []})
                return
            cache_enabled = configuration_cache_enabled(spec)
            if cache_enabled and not store.enter_execution(job['id'], job['token']):
                store.finish(job['id'], 'superseded', {'log': str(log), 'issues': ['Replaced before cache-enabled launch']})
                return
            # Cache hits skip configuration callbacks. Close the supersession
            # window before launch; the runtime service also checks the current
            # token before task actions. Only the stable lane event path can
            # enter the cache; the job archive is selected by this worker.
            started_ns = time.time_ns()
            run_started = time.time()
            from gradle_registry import INACTIVE_STATES, daemon_states
            gradle_home = Path(env['GRADLE_USER_HOME'])
            observed_daemons = set()
            lost_daemon = False
            fallback = False
            for attempt in range(2):
                reset_event_spool(event_spool, store.state)
                spool_initialized = True
                process = OwnedProcess(argv, lane / spec['root'], env, log)
                store.phase(job['id'], 'configuring', process.pid)
                daemon_missing_since = None
                while process.poll() is None:
                    if store.job(job['id'])['status'] == 'cancelling':
                        self.close_owned(process, job['id'])
                        break
                    try:
                        live_daemons = daemon_states(gradle_home)
                        serving_daemons = {pid for pid, entry in live_daemons.items()
                                           if entry.get('state') not in INACTIVE_STATES}
                        observed_daemons.update(serving_daemons)
                        if observed_daemons and not (observed_daemons & set(live_daemons)):
                            # A single-use daemon unregisters before the wrapper
                            # client exits. Let that client report its real exit
                            # code; persistent loss still closes only this job.
                            now = time.monotonic()
                            if daemon_missing_since is None:
                                daemon_missing_since = now
                            if now - daemon_missing_since >= DAEMON_LOSS_GRACE_SECONDS and process.poll() is None:
                                lost_daemon = True
                                self.close_owned(process, job['id'])
                                break
                        else:
                            daemon_missing_since = None
                    except Exception:
                        pass
                    time.sleep(0.1)
                code = process.poll()
                drain_started = time.monotonic()
                store.phase(job['id'], 'draining-processes')
                self.close_owned(process, job['id'])
                archive_event_spool(event_spool, event_path, store.state)
                drain_seconds = time.monotonic() - drain_started
                store.record_event('post-gradle-step', job_id=job['id'],
                                   phase='draining-processes', seconds=drain_seconds)
                if (attempt != 0 or not cache_enabled or lost_daemon or
                        not configuration_cache_fallback_allowed(log, event_path, code)):
                    break
                if not store.enter_execution(job['id'], job['token']):
                    break
                # Preserve the failed cache attempt and keep one shared log.
                if event_path.exists():
                    event_path.replace(output / 'events.configuration-cache-attempt.jsonl')
                with log.open('a', encoding='utf-8') as stream:
                    stream.write('\n[coordinator] Configuration cache rejected before task execution; '
                                 'retrying once with configuration cache off.\n')
                fallback = True
                with store.transaction() as db:
                    store.event(db, 'configuration-cache-fallback', job['id'], reason='pre-execution-cache-problem')
                argv = self.command_builder(dict(spec, configuration_cache='off'), lane,
                                            pinned_init, runtime)
                started_ns = time.time_ns()
            post_started = drain_started
            post_timings = {'draining-processes': drain_seconds}

            def post_step(phase, action):
                store.phase(job['id'], phase)
                started = time.monotonic()
                try:
                    return action()
                finally:
                    duration = time.monotonic() - started
                    post_timings[phase] = duration
                    store.record_event('post-gradle-step', job_id=job['id'],
                                       phase=phase, seconds=duration)

            result = post_step('collecting-artifacts', lambda: collect_evidence(
                lane, event_path, output, code, spec['tasks'], spec['artifacts'], started_ns))
            from snapshots import archive_job_crash_artifacts
            finished_at = time.time()
            job_meta = {**job, 'started': run_started, 'finished': finished_at,
                        'status': 'passed' if result['accepted'] else 'failed'}
            task_events = read_events(event_path)
            preliminary_infra = infrastructure_failure(
                log, [e for e in task_events if e.get('event') == 'task'],
                daemon_kind='daemon-lost' if lost_daemon else None,
                task_results=result.get('tasks', []))
            # Owned sources are never recycled as shared lanes. A successful
            # build needs its task/artifact and input gates, not a recursive
            # search for unreported crashes in unrelated generated directories.
            if owned and result['accepted'] and not lost_daemon and not preliminary_infra:
                crash = {'archive': None, 'artifacts': [],
                         'discovery': 'skipped-successful-owned-worktree'}
            else:
                crash = post_step('collecting-diagnostics', lambda: archive_job_crash_artifacts(
                    lane, gradle_home, store.state / 'recovered-crash-artifacts', job['lane'], job_meta))
            from snapshots import verify_lane
            post_step('verifying-source', lambda: verify_owned(snapshot, lane, store.state)
                      if owned else verify_lane(Path(snapshot['path']), lane))
            post_step('verifying-tools', lambda: verify_workspace_tools(spec, lane))
            post_step('verifying-repositories', lambda: verify_repository_layers(spec, lane))
            if native_inputs is not None:
                post_step('verifying-native-inputs', lambda: verify_native_inputs(spec, lane, store, snapshot))
            store.phase(job['id'], 'finalizing')
            result['post_gradle_seconds'] = post_timings
            result['log'] = str(log)
            result['excluded_tasks'] = spec.get('excluded_tasks', [])
            result['source_execution'] = 'owned-worktree' if owned else 'snapshot-lane'
            result['coordinator_runtime'] = {'init_script_sha256': self.init_script_sha256,
                                             'init_script': str(output / 'events.gradle')}
            task_events = read_events(event_path)
            infra = infrastructure_failure(log, [e for e in task_events if e.get('event') == 'task'],
                                           crash, 'daemon-lost' if lost_daemon else None,
                                           result.get('tasks', []))
            if infra:
                result['infrastructure'] = infra
                result['accepted'] = False
                result['issues'] = [*result.get('issues', []), f'Infrastructure failure: {infra["kind"]}']
            result['attempts'] = store.attempt_history(job['id'], 'failed' if infra else ('passed' if result['accepted'] else 'failed'), infra)
            result['crash_artifacts'] = crash
            observations = configuration_cache_result(log)
            result['offline'] = spec.get('offline', False)
            result['configuration_cache'] = {
                'requested': spec.get('configuration_cache', 'auto'),
                'enabled': cache_enabled and not fallback, 'fallback': fallback,
                'reused': cache_enabled and not fallback and 'reused' in observations,
                'stored': cache_enabled and not fallback and 'stored' in observations,
                'supersession_window': 'before-launch' if cache_enabled else 'before-first-task',
            }
            post_timings['total'] = time.monotonic() - post_started
            (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            status = 'failed' if infra or not result['accepted'] else 'passed'
            terminal_recording_pending = True
            store.finish(job['id'], status, result)
            terminal_recording_pending = False
            if infra:
                cfg = store.all_config()
                replacement = store.requeue_infrastructure(job['id'], cfg.get('infrastructure_retries', 1))
                if replacement:
                    store.record_event('infrastructure-retry-scheduled', job_id=job['id'], replacement=replacement,
                                       infrastructure_kind=infra['kind'])
        except BaseException as error:
            if terminal_recording_pending:
                # Terminal recording failed after process containment and evidence archival.
                # Keep that evidence and the queue's actual state; do not replace it with
                # an empty-task failure or try to record a different terminal result.
                raise
            if process is not None:
                self.close_owned(process, job['id'])
            if spool_initialized:
                try:
                    archive_event_spool(event_spool, event_path, store.state)
                except (OSError, ValueError):
                    pass  # Preserve the original failure; partial evidence is unverified.
            if store.job(job['id'])['status'] in ACTIVE:
                finished_at = time.time()
                if run_started is not None and gradle_home is not None:
                    try:
                        from snapshots import archive_job_crash_artifacts
                        crash = archive_job_crash_artifacts(lane, gradle_home, store.state / 'recovered-crash-artifacts',
                                                            job['lane'], {**job, 'started': run_started,
                                                                          'finished': finished_at, 'status': 'failed'})
                    except Exception as archive_error:
                        error = RuntimeError(f'{error}; crash-artifact archival failed: {archive_error}')
                task_events = [e for e in read_events(event_path) if e.get('event') == 'task']
                lane_integrity_error = any(marker in str(error) for marker in
                                           ('Unpinned file in lane source inputs', 'Build changed pinned source input'))
                infra = (infrastructure_failure(log, task_events, crash)
                         if run_started is not None and not lane_integrity_error else None)
                result = {'log': str(log), 'excluded_tasks': job['spec'].get('excluded_tasks', []), 'issues': [str(error)], 'tasks': [], 'crash_artifacts': crash,
                          'infrastructure': infra, 'attempts': store.attempt_history(job['id'], 'failed', infra)}
                try:
                    output.mkdir(parents=True, exist_ok=True)
                    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
                except OSError:
                    pass
                store.finish(job['id'], 'failed', result)
                if infra:
                    replacement = store.requeue_infrastructure(job['id'])
                    if replacement:
                        store.record_event('infrastructure-retry-scheduled', job_id=job['id'], replacement=replacement,
                                           infrastructure_kind=infra['kind'])
        finally:
            if owned_lock is not None:
                owned_lock.close()
            if lane_lock is not None:
                lane_lock.close()

    def serve(self):
        store = self.store
        pool_size = max(8, self.slots)
        with ExclusiveLock(store.state / 'worker.lock'), concurrent.futures.ThreadPoolExecutor(max_workers=pool_size) as pool:
            futures = {}
            legacy_lock = None
            from registry import Registry
            registry = Registry() if store.metadata('source') else None
            if registry:
                registry.register(self.source, store.state, worker_pid=os.getpid(), heartbeat=time.time(),admission_version=1)
            try:
                # A crash closes Windows Job Objects. Never adopt/kill stale PIDs:
                # external-process preflight must first prove the lane is clear.
                orphans = self.close_pre_boot_orphans(store.job_headers(tuple(ACTIVE)))
                while True:
                    store.metadata('worker', {'pid': os.getpid(), 'heartbeat': time.time(), 'queue_order_version': 1, 'offline_request_version': 1})
                    if registry:
                        running = [{'id': j['id'], 'lane': j['lane'], 'memory_gb': j['spec'].get('memory_gb', 0),
                                    'tier': classify_task_tier(j['spec'])}
                                   for j in store.scheduling_jobs(tuple(ACTIVE))]
                        registry.heartbeat(self.source, store.state, os.getpid(), running)
                    for lane, future in list(futures.items()):
                        if future.done():
                            future.result()
                            del futures[lane]
                    pending = orphans or bool(store.job_headers(('queued',)))
                    if not pending and not futures and store.metadata('blocked'):
                        # A previous admission failure must not make an idle
                        # coordinator look blocked after the queue drains.
                        store.metadata('blocked', {})
                    if store.metadata('drain') and not futures:
                        break
                    if pending and not store.metadata('drain'):
                        try:
                            config = store.all_config()
                            whitelist = list(config.get('whitelist_patterns', []))
                            temp_pids = store.temp_whitelist_pids()

                            # 1. Discover sibling workspaces, active jobs, and coordinator child PIDs
                            sibling_active, sibling_jobs, sibling_pids = [], [], []
                            try:
                                global_state = registry.global_active() if registry else None
                                if global_state is None:
                                    raise RuntimeError('Coordinator queue is not registered')
                                live_keys = {(r['state'], r['job_id']) for r in global_state['reservations']}
                                legacy_keys = {(j['state'], j['id']) for j in global_state['legacy']}
                                now = time.time()
                                # Exempt only descendants of a live coordinator
                                # worker. Job PIDs and stale registry entries are
                                # not process ownership evidence on their own.
                                for workspace in global_state['workspaces']:
                                    worker_pid = workspace.get('worker_pid')
                                    heartbeat = workspace.get('last_seen') or 0
                                    if (workspace['state'] != str(store.state.resolve()) and worker_pid
                                            and 0 <= now - heartbeat < 10 and pid_alive(worker_pid)):
                                        sibling_pids.append(int(worker_pid))
                                for aj in global_state['active']:
                                    key = (aj['state'], aj['id'])
                                    if (aj['state'] != str(store.state.resolve()) or aj.get('kind') == 'local-docker-native-image') and (key in live_keys or key in legacy_keys):
                                        sibling_active.append((Path(aj['state']).name, aj['id']))
                                        sibling_jobs.append(aj)
                            except Exception:
                                pass

                            # 2. Check for external blockers, treating local AND sibling coordinator PIDs as owned
                            owned_pids = [
                                j['pid'] for j in store.job_headers(tuple(ACTIVE))
                                if j['pid'] and not orphans
                            ] + sibling_pids

                            # Reap first: a Gradle daemon left idle by a finished build holds
                            # memory this queue needs and would otherwise linger for its full
                            # idle timeout. Only daemons Gradle itself reports as idle qualify.
                            if config.get('reap_idle_gradle_daemons', True):
                                try:
                                    reaped = reap_idle_gradle_daemons(
                                        owned_pids, whitelist=whitelist + temp_pids,
                                        min_idle_seconds=float(config.get('reap_min_idle_seconds', 120)))
                                    for daemon in reaped:
                                        details = dict(daemon)
                                        daemon_kind = details.pop('kind', None)
                                        store.record_event('daemon-reaped', daemon_kind=daemon_kind, **details)
                                except RuntimeError:
                                    pass  # Inspection unavailable; external_builds below defers admission.

                            blockers = external_builds(owned_pids, whitelist=whitelist + temp_pids)

                            # 3. Determine tier exemption: if external blockers exist, can test jobs still run?
                            allowed_tiers = None
                            if blockers:
                                exempt_tests = config.get('test_tier_exempt_external_blockers', True)
                                can_run_tests = False
                                # Startup recovery must prove orphan lanes are clear;
                                # a test-tier exemption cannot establish that proof.
                                if exempt_tests and not orphans:
                                    try:
                                        free_gb = available_memory_gb(include_swap=False)
                                    except Exception:
                                        free_gb = None
                                    cpu = host_cpu_load()
                                    if (free_gb is not None and math.isfinite(free_gb) and free_gb >= 24.0 and cpu['cpu_known']
                                            and cpu['cpu_load_1m'] < cpu['cpu_count'] * 1.5):
                                        can_run_tests = True

                                if can_run_tests:
                                    # Allow test tier jobs to claim available slots; non-test jobs stay queued
                                    allowed_tiers = {'test'}
                                else:
                                    store.metadata('blocked', {
                                        'reason': 'External build processes; waiting without stopping them',
                                        'pids': [p['pid'] for p in blockers],
                                        'blockers': blockers
                                    })
                                    time.sleep(1)
                                    continue

                            for orphan in orphans:
                                self.finish_orphan(orphan)
                            orphans = []

                            # 4. Check dynamic slot limit across all local and sibling active builds
                            local_active = store.scheduling_jobs(tuple(ACTIVE))
                            queued_jobs = store.scheduling_jobs(('queued',))
                            if allowed_tiers is not None:
                                queued_jobs = [job for job in queued_jobs if classify_task_tier(job['spec']) in allowed_tiers]
                            dyn = compute_dynamic_slots(config, active_jobs=local_active + sibling_jobs, pending_jobs=queued_jobs)
                            max_host_slots = dyn['slots']

                            if len(futures) + len(sibling_active) >= max_host_slots:
                                other_ws, other_job = sibling_active[0] if sibling_active else (self.source.name, 'local')
                                store.metadata('blocked', {
                                    'reason': f"Waiting for slot (limit {max_host_slots} [{dyn['mode']}]; active build in {other_ws})",
                                    'workspace': other_ws,
                                    'job': other_job,
                                    'dynamic_slots': dyn
                                })
                                time.sleep(1)
                                continue

                            # claim records transitions and clears the reason on success.
                            # Clearing it here generated a duplicate durable blocked event
                            # every poll even when the admission condition never changed.
                            if legacy_lock is None:
                                lock_path = self.source / 'deploy/edk/e2e/scripts/wallet-proof/wallet-proof.lck'
                                if lock_path.parent.is_dir():
                                    legacy_lock = ExclusiveLock(lock_path)
                                    legacy_lock.__enter__()
                            ahead = self.fair_share_ahead(registry)
                            if ahead:
                                store.metadata('blocked', {
                                    'reason': f"Waiting for {Path(ahead['source']).name} ({ahead['priority']} priority) to take the next slot",
                                    'workspace': ahead['source'],
                                })
                                time.sleep(1)
                                continue
                            for lane in range(max_host_slots):
                                if lane not in futures and len(futures) + len(sibling_active) < max_host_slots:
                                    job = store.claim(lane,
                                                      allowed_tiers=allowed_tiers, other_active_jobs=sibling_jobs)
                                    if job:
                                        futures[lane] = pool.submit(self.run_job, job)
                                        self.fair_share_waiting_since = None
                                        if registry:
                                            try:
                                                registry.record_start(store.state)
                                            except (sqlite3.Error, OSError, ValueError):
                                                pass
                        except (RuntimeError, BlockingIOError, OSError) as error:
                            store.metadata('blocked', {'reason': str(error)})
                    if not futures and legacy_lock is not None:
                        legacy_lock.close()
                        legacy_lock = None
                    if futures and time.monotonic() - getattr(self, 'last_peak_sample', 0) >= 10:
                        # Peak memory per build feeds history-based reservation sizing.
                        self.last_peak_sample = time.monotonic()
                        try:
                            store.sample_peaks()
                        except (OSError, RuntimeError, ValueError, sqlite3.Error):
                            pass
                    time.sleep(1)
            finally:
                if legacy_lock is not None:
                    legacy_lock.close()
                store.metadata('worker', {'pid': os.getpid(), 'heartbeat': 0, 'queue_order_version': 1, 'offline_request_version': 1})
