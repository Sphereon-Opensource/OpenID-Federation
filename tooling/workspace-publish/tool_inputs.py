"""Freeze explicit GBS/App Platform Maven inputs; never resolve or run Gradle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import uuid

NAMESPACES = ('com/sphereon/gradle', 'software/amazon/app/platform', 'software/amazon/lastmile/kotlin/inject/anvil')
# kotlin-inject-anvil arrives only as a transitive dependency of the App Platform fork; locks frozen
# before it was added have no files there.
OPTIONAL_NAMESPACES = frozenset({'software/amazon/lastmile/kotlin/inject/anvil'})
# Plugin coordinates are JVM publications. A catalog BOM under the plain
# com.sphereon.gradle group is accepted only as the typed version-catalog variant.
PREPARED_GROUPS = {'com.sphereon.gradle.plugin': 'jvm', 'com.sphereon.gradle': 'version-catalog'}
SUFFIXES = {'.jar', '.aar', '.pom', '.module', '.toml', '.xml', '.sha1', '.sha256', '.sha512', '.md5'}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def guarded(path, stop=None):
    path = Path(os.path.abspath(path))
    for node in (path, *path.parents):
        try:
            info = node.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Tool input path contains a link or reparse point')
        if stop is not None and node == stop:
            break
    return path


def digest(path, stop=None):
    with guarded(path, stop).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(sources):
    records = []
    for source, namespace in zip(sources, NAMESPACES):
        if source is None:
            if namespace not in OPTIONAL_NAMESPACES:
                raise ValueError('Tool namespace source is required: ' + namespace)
            continue
        source = guarded(source)
        folder = guarded(source / namespace)
        files = []
        if folder.is_dir():
            for path in folder.rglob('*'):
                guarded(path)
                if path.is_file() and path.suffix in SUFFIXES:
                    files.append(path)
        if not any(path.suffix == '.jar' for path in files) or not any(path.suffix == '.pom' for path in files):
            raise ValueError('Tool namespace requires an artifact and POM: ' + namespace)
        for path in files:
            records.append(dict(path=path.relative_to(source).as_posix(), size=path.stat().st_size,
                                sha256=digest(path)))
    return sorted(records, key=lambda item: item['path'])


def verify(lock):
    lock = guarded(lock)
    manifest = json.loads(lock.read_text('utf-8'))
    body = {key: value for key, value in manifest.items() if key != 'id'}
    identity = hashlib.sha256(canonical(body)).hexdigest()
    if manifest.get('schemaVersion') != 1 or manifest.get('id') != identity:
        raise ValueError('Tool lock identity mismatch')
    repository = guarded(lock.parent / 'repo')
    expected = {}
    for record in manifest['files']:
        name = record['path']
        if not isinstance(name, str) or '\\' in name or ':' in name or any(part in ('', '.', '..') for part in name.split('/')):
            raise ValueError('Unsafe tool lock path')
        if not any(name.startswith(namespace + '/') for namespace in NAMESPACES) or name.casefold() in expected:
            raise ValueError('Invalid tool lock namespace or duplicate path')
        expected[name.casefold()] = record
    actual, observed = set(), []

    def fingerprint(info):
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Tool input path contains a link or reparse point')
        return (info.st_mode, info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)

    # Inspect each entry once, instead of walking its ancestors three times.
    # This inventory is local to one verification; hashes are never cached.
    observed.append((repository, fingerprint(repository.lstat())))
    def visit(folder):
        with os.scandir(folder) as entries:
            for entry in entries:
                path = Path(entry.path)
                # Windows DirEntry metadata omits file IDs; use lstat so both
                # boundary fingerprints carry comparable device/inode values.
                info = path.lstat()
                observed.append((path, fingerprint(info)))
                if stat.S_ISDIR(info.st_mode):
                    visit(path)
                elif stat.S_ISREG(info.st_mode):
                    relative = path.relative_to(repository).as_posix()
                    name = relative.casefold()
                    if name in actual:
                        raise ValueError('Tool repository inventory mismatch')
                    actual.add(name)
                    record = expected.get(name)
                    if record is None or relative != record['path']:
                        raise ValueError('Tool repository inventory mismatch')
                    with path.open('rb') as stream:
                        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
                    if info.st_size != record['size'] or checksum != record['sha256']:
                        raise ValueError('Tool input integrity failure: ' + record['path'])
                else:
                    raise ValueError('Tool input is not a regular file or directory')
    visit(repository)
    if set(expected) != actual:
        raise ValueError('Tool repository inventory mismatch')
    # Recheck every file and directory after hashing. An internal directory
    # replaced by a link, or a file changed after its hash, fails closed.
    for path, before in observed:
        if fingerprint(path.lstat()) != before:
            raise ValueError('Tool input changed during verification: ' + str(path))
    for namespace in NAMESPACES:
        selected = [name for name in expected if name.startswith(namespace + '/')]
        if not selected and namespace in OPTIONAL_NAMESPACES:
            continue
        if not any(name.endswith('.jar') for name in selected) or not any(name.endswith('.pom') for name in selected):
            raise ValueError('Tool namespace requires an artifact and POM: ' + namespace)
    # Ancestors outside the repository are common to every record. Check them
    # at both boundaries instead of repeating those probes for every file.
    guarded(repository)
    guarded(lock)
    return manifest


def atomic_json(path, value):
    path = guarded(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.parent / ('.pending-' + uuid.uuid4().hex)
    try:
        temporary.write_bytes(canonical(value))
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def freeze(destination, gbs_source, app_platform_source, activate=True, anvil_source=None):
    destination = guarded(destination)
    sources = [guarded(gbs_source), guarded(app_platform_source), guarded(anvil_source) if anvil_source else None]
    if any(source is not None and (destination == source or source in destination.parents or destination in source.parents) for source in sources):
        raise ValueError('Frozen tool repository must be disjoint from bootstrap sources')
    before = inventory(sources)
    body = dict(schemaVersion=1, files=before)
    identity = hashlib.sha256(canonical(body)).hexdigest()
    manifest = dict(body, id=identity)
    parent = guarded(destination / 'generations')
    parent.mkdir(parents=True, exist_ok=True)
    generation = guarded(parent / identity)
    if not generation.exists():
        with tempfile.TemporaryDirectory(prefix='.freeze-', dir=parent) as temporary:
            pending = Path(temporary) / 'generation'
            repository = pending / 'repo'
            for record in before:
                index = next(index for index, namespace in enumerate(NAMESPACES) if record['path'].startswith(namespace + '/'))
                source = guarded(sources[index] / record['path'])
                target = guarded(repository / record['path'])
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                if target.stat().st_size != record['size'] or digest(target) != record['sha256']:
                    raise ValueError('Tool input changed during capture: ' + record['path'])
            (pending / 'lock.json').write_bytes(canonical(manifest))
            verify(pending / 'lock.json')
            if inventory(sources) != before:
                raise ValueError('Tool inputs changed during capture')
            try:
                pending.rename(generation)
            except FileExistsError:
                # A competing freeze may have completed the same immutable identity.
                verify(generation / 'lock.json')
    verify(generation / 'lock.json')
    pointer = dict(id=identity, repository=str(generation / 'repo'), lock=str(generation / 'lock.json'))
    if activate:
        atomic_json(destination / 'active.json', pointer)
    return pointer


def freeze_prepared(destination, base_lock, request, evidence, snapshot):
    """Replace selected tool coordinates using accepted coordinator archives only."""
    base_lock = guarded(base_lock)
    base = verify(base_lock)
    snapshot_body = {key: value for key, value in snapshot.items() if key != 'id'}
    snapshot_id = hashlib.sha256(canonical(snapshot_body)).hexdigest()
    if snapshot.get('id') != snapshot_id or request.get('snapshot') != snapshot_id or evidence.get('snapshot') != snapshot_id:
        raise ValueError('Prepared tool source snapshot mismatch')
    # Collected request results expose status; raw job archives also expose
    # accepted. Never require callers to synthesize a missing raw-job flag.
    if evidence.get('status') != 'passed' or evidence.get('accepted') is False or evidence.get('issues'):
        raise ValueError('A passed, accepted coordinator result is required')
    if not all(re.fullmatch(r'[A-Za-z0-9_-]+', evidence.get(key, '')) for key in ('request_id', 'job_id')):
        raise ValueError('Missing coordinator request or job identity')
    producers = json.loads(request.get('properties', {}).get('workspaceProducerInputs', '{}'))
    if not producers or request.get('root') != 'gradle-build-support':
        raise ValueError('Expected explicit GBS tool producers')
    tasks = {item['path'] for item in evidence.get('tasks', [])
             if not item.get('failed') and item.get('outcome') in ('EXECUTED', 'SUCCESS', 'UP-TO-DATE', 'FROM-CACHE')}
    archives = {}
    for item in evidence.get('artifacts', []):
        name = item['relative']
        path = guarded(item['path'])
        if (not Path(item['path']).is_absolute() or not path.is_file()
                or digest(path) != item['sha256'] or ('size' in item and path.stat().st_size != item['size'])
                or name.casefold() in archives):
            raise ValueError('Corrupt or duplicate coordinator archive: ' + name)
        archives[name.casefold()] = (path, item)
    replacements, selected = {}, {}
    for name, producer in producers.items():
        project = producer.get('projectPath', ':' + name)
        parts = project.split(':')[1:]
        if not parts or any(not re.fullmatch(r'[A-Za-z0-9_-]+', part) for part in parts):
            raise ValueError('Invalid prepared project path')
        if project + ':prepareWorkspaceArtifacts' not in tasks:
            raise ValueError('Missing accepted producing task: ' + project)
        gav = producer['coordinate'].split(':')
        if len(gav) != 3 or gav[0] not in PREPARED_GROUPS or any(not re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in gav):
            raise ValueError('Invalid prepared tool coordinate')
        coordinate_folder = gav[0].replace('.', '/') + '/' + gav[1] + '/' + gav[2] + '/'
        if coordinate_folder in replacements:
            raise ValueError('Duplicate prepared tool coordinate')
        prefix = request['root'] + '/' + '/'.join(parts) + '/build/workspace-artifacts/'
        record = archives.get((prefix + 'manifest.json').casefold())
        if not record:
            raise ValueError('Missing archived tool manifest')
        producing_task = project + ':prepareWorkspaceArtifacts'
        if record[1].get('producer') != producing_task or record[1].get('producer_outcome') not in ('EXECUTED', 'SUCCESS', 'UP-TO-DATE', 'FROM-CACHE'):
            raise ValueError('Tool manifest lacks its accepted producing task')
        component = json.loads(record[0].read_text('utf-8-sig'))
        if component.get('schemaVersion') != 1 or any(component.get(field) != producer.get(field)
                for field in ('module', 'coordinate', 'producerInputId', 'buildLogicId', 'dependencyIds')):
            raise ValueError('Prepared tool producer identity mismatch')
        records = component.get('files', [])
        if not all(any(item.get('kind') == kind for item in records) for kind in ('artifact', 'pom', 'module')):
            raise ValueError('Prepared tool requires artifact, POM and module metadata')
        variant = PREPARED_GROUPS[gav[0]]
        if component.get('variants') != [variant] or any(item.get('variant', variant) != variant for item in records):
            raise ValueError('Prepared tool variant differs from its coordinate group: ' + producer['coordinate'])
        if variant == 'version-catalog' and any(item.get('kind') == 'artifact' and not item.get('path', '').endswith('.toml') for item in records):
            raise ValueError('Prepared catalog BOM may contain only TOML artifacts: ' + producer['coordinate'])
        replacements[coordinate_folder] = component
        for item in records:
            relative = item['path']
            if (not relative.startswith(coordinate_folder) or relative.count('/') != coordinate_folder.count('/')
                    or '\\' in relative or ':' in relative or relative.endswith(('/', '/.', '/..'))
                    or Path(relative).suffix not in SUFFIXES or relative.casefold() in selected):
                raise ValueError('Prepared file differs from selected coordinate: ' + relative)
            source = archives.get((prefix + 'repo/' + relative).casefold())
            if not source or source[1]['sha256'] != item['sha256'] or source[0].stat().st_size != item['size']:
                raise ValueError('Incomplete prepared tool archive: ' + relative)
            if source[1].get('producer') != producing_task or source[1].get('producer_outcome') not in ('EXECUTED', 'SUCCESS', 'UP-TO-DATE', 'FROM-CACHE'):
                raise ValueError('Tool archive lacks its accepted producing task: ' + relative)
            selected[relative.casefold()] = (relative, source[0], item)
    destination = guarded(destination)
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='vdx-prepared-tools-') as temporary:
        staged = Path(temporary) / 'repo'
        for item in base['files']:
            if any(item['path'].startswith(folder) for folder in replacements):
                continue
            target = guarded(staged / item['path'])
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(guarded(base_lock.parent / 'repo' / item['path']), target)
            if digest(target) != item['sha256']:
                raise ValueError('Frozen base changed during prepared tool installation')
        for relative, source, item in selected.values():
            target = guarded(staged / relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            if digest(target) != item['sha256'] or target.stat().st_size != item['size']:
                raise ValueError('Prepared tool archive changed during installation')
        verify(base_lock)
        pointer = freeze(destination, staged, staged, activate=False)
        receipt = dict(schemaVersion=1, request=evidence['request_id'], job=evidence['job_id'],
                       snapshot=snapshot_id, baseGeneration=base['id'], generation=pointer['id'],
                       components=replacements, archiveIdentity=hashlib.sha256(canonical(evidence['artifacts'])).hexdigest())
        atomic_json(destination / 'preparations' / (evidence['request_id'] + '.json'), receipt)
        verify(pointer['lock'])
        atomic_json(destination / 'active.json', pointer)
        return pointer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('freeze')
    capture.add_argument('--destination', required=True)
    capture.add_argument('--gbs-source', required=True)
    capture.add_argument('--app-platform-source', required=True)
    capture.add_argument('--anvil-source')
    check = commands.add_parser('verify')
    check.add_argument('--lock', required=True)
    prepared = commands.add_parser('freeze-prepared')
    prepared.add_argument('--destination', required=True)
    prepared.add_argument('--base-lock', required=True)
    prepared.add_argument('--request', required=True)
    prepared.add_argument('--result', required=True)
    prepared.add_argument('--snapshot-manifest', required=True)
    args = parser.parse_args()
    if args.command == 'freeze-prepared':
        load = lambda path: json.loads(guarded(path).read_text('utf-8-sig'))
        result = freeze_prepared(args.destination, args.base_lock, load(args.request), load(args.result), load(args.snapshot_manifest))
    else:
        result = freeze(args.destination, args.gbs_source, args.app_platform_source, anvil_source=args.anvil_source) if args.command == 'freeze' else verify(args.lock)
    print(json.dumps(result))


if __name__ == '__main__':
    main()
