"""Bounded source evidence for coordinator execution in owned Git worktrees.

Archive selected bytes once, then execute in the owner's worktree with the usual
managed process and evidence collector. Shared/release snapshots stay unchanged.
"""
import importlib.util
import json
import os
from pathlib import Path
import stat

import snapshots

_loader = importlib.util.spec_from_file_location('vdx_owned_workspace_management',
    Path(__file__).resolve().parents[1] / 'workspace-management/workspace.py')
management = importlib.util.module_from_spec(_loader)
_loader.loader.exec_module(management)


def validate_workspace(source, state, receipt_path, agent):
    source, state, receipt_path = map(management.guarded, (source, state, receipt_path))
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    plan = receipt['plan']
    management._validate_plan(plan)
    if receipt.get('agent') != agent or plan['agent'] != agent:
        raise ValueError('Owned workspace belongs to another owner')
    if source != management.guarded(plan['destination']):
        raise ValueError('Owned workspace source mismatch')
    if state != management.guarded(plan['coordinatorState']):
        raise ValueError('Owned workspace coordinator state mismatch')
    if receipt_path != management.guarded(Path(plan['state']) / 'workspace.json'):
        raise ValueError('Owned workspace receipt path mismatch')
    if (receipt.get('repositoriesReady') is not True or
            set(receipt.get('completed', [])) != {item['id'] for item in plan['repositories']}):
        raise ValueError('Owned workspace setup is incomplete')
    repositories = []
    for item in plan['repositories']:
        repo = management.Git(item['path'])
        if repo.run('rev-parse', '--show-toplevel') != str(management.guarded(item['path'])).replace('\\', '/'):
            raise ValueError('Owned workspace repository source mismatch')
        if management.guarded(repo.run('rev-parse', '--path-format=absolute', '--git-common-dir')) != management.guarded(item['commonGitDir']):
            raise ValueError('Owned workspace object store mismatch')
        branch = repo.run('symbolic-ref', '--quiet', '--short', 'HEAD')
        if branch != item['branch']:
            raise ValueError('Owned workspace branch changed')
        repositories.append({'path': item['relativePath'], 'head': repo.run('rev-parse', 'HEAD'), 'branch': branch})
    # Every product registration belongs to infra; no hidden nested checkout may
    # be omitted by a selected-input scan.
    infra = management.Git(source)
    registrations = management._modules(infra, 'HEAD')
    expected = {item['relativePath'] for item in plan['repositories'] if item['relativePath'] != '.'}
    if {item['path'] for item in registrations} != expected:
        raise ValueError('Owned workspace requires flat infra repository registrations')
    for item in plan['repositories']:
        if item['relativePath'] != '.' and management._modules(management.Git(item['path']), 'HEAD'):
            raise ValueError('Owned workspace contains nested repository registrations')
    return plan, repositories


def validate_selection(selection):
    if (not isinstance(selection, dict) or not {'roots', 'scopes', 'files'} <= set(selection)
            or set(selection) - {'roots', 'scopes', 'files', 'sourceModules'}):
        raise ValueError('Owned source selection requires roots, scopes and files')
    for key, values in selection.items():
        if key == 'sourceModules':
            if not isinstance(values, dict) or set(values) != set(selection['roots']):
                raise ValueError('Owned source modules must cover exactly the captured roots')
            for names in values.values():
                if not isinstance(names, list) or not names or any(not isinstance(name, str) or not name or any(char not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for char in name) for name in names):
                    raise ValueError('Invalid owned source module selection')
            continue
        if not isinstance(values, list) or (key != 'files' and not values):
            raise ValueError('Owned source selection must be nonempty')
        for value in values:
            if key == 'roots' and value == '.':
                continue
            snapshots._relative(value)
    return {key: ({root: sorted(set(names)) for root, names in values.items()} if key == 'sourceModules' else sorted(set(values))) for key, values in selection.items()}


def selection_from_plan(plan, source):
    """Capture exact producer inputs and their directories to detect new files."""
    source = management.guarded(source)
    if management.guarded(plan['workspaceRoot']) != source:
        raise ValueError('Owned plan source mismatch')
    inputs = {item['path'] for value in plan['producerInputs'].values() for item in value['sourceFiles'] if item['sha256'] is not None}
    selected = plan['sourceModules']
    scopes = {module['path'] for module in selected}
    scopes.update(scope for module in selected for scope in module.get('inputScopes', []))
    # Coordinator/helper bytes are provenance inputs too; this is a bounded
    # tooling directory rather than a walk across all product repositories.
    for folder in ('tooling/build-coordinator', 'tooling/workspace-management'):
        inputs.update(path.relative_to(source).as_posix() for path in (source / folder).glob('*.py'))
    for name in ('events.gradle',):
        inputs.add('tooling/build-coordinator/' + name)
    if plan.get('toolInputs'):
        # The worker imports this verifier from the owned source path. Its
        # bytes must be checked just like the Gradle repository init scripts.
        inputs.add('tooling/workspace-publish/tool_inputs.py')
    names = {group['root']: group['environment']['WORKSPACE_SOURCE_MODULES'].split(',') for group in plan['prepareTasksByRoot']}
    return validate_selection(dict(roots=plan['configurationRoots'], scopes=sorted(scopes), files=sorted(inputs), sourceModules=names))


def git_selected(repo, options, names):
    """Keep Git pathspec commands comfortably below Windows' UTF-16 argv limit."""
    batches, batch, size = [], [], 0
    for name in names:
        cost = len(name.encode('utf-16-le')) // 2 + 3
        if cost > 12000:
            raise ValueError('Selected Git pathspec is too long')
        if batch and size + cost > 12000:
            batches.append(batch)
            batch, size = [], 0
        batch.append(name)
        size += cost
    if batch:
        batches.append(batch)
    return b''.join(snapshots._git(repo, *options, '--', *batch) for batch in batches)


def inventory_owned(source, state, receipt_path, agent, selection):
    plan, repositories = validate_workspace(source, state, receipt_path, agent)
    selection = validate_selection(selection)
    source = management.guarded(source)
    scopes = selection['scopes'] + selection['files']
    grouped = {}
    # Longest repository path wins; never enumerate an unrelated repository tree.
    paths = sorted((item['relativePath'] for item in plan['repositories']), key=len, reverse=True)
    for scope in scopes:
        prefix = next((name for name in paths if name == '.' or scope == name or scope.startswith(name + '/')), None)
        relative = scope if prefix == '.' else scope[len(prefix) + 1:]
        if not relative:
            raise ValueError('Select module/input scopes, not an entire repository')
        grouped.setdefault(prefix, []).append(relative)
    files, deleted = {}, []
    for prefix, names in grouped.items():
        repo = source if prefix == '.' else management.guarded(source / prefix)
        tracked = {}
        # An explicit required file inside a selected directory needs no duplicate pathspec.
        names = sorted(set(names))
        names = [name for name in names if not any(name.startswith(parent + '/') for parent in names if parent != name)]
        for record in git_selected(repo, ['ls-files', '--stage', '-z'], names).split(b'\0'):
            if not record:
                continue
            metadata, name = record.split(b'\t', 1)
            mode, oid, stage = metadata.decode('ascii').split()
            if stage != '0' or mode == '160000':
                raise ValueError('Owned selection contains an unmerged entry or repository link')
            tracked[os.fsdecode(name)] = mode
        untracked = {os.fsdecode(name) for name in git_selected(repo, ['ls-files', '--others', '--exclude-standard', '-z'], names).split(b'\0') if name}
        for name in sorted(set(tracked) | untracked):
            if name not in tracked and any(part.lower() in snapshots.GENERATED for part in Path(name).parts):
                continue
            full_name = name if prefix == '.' else prefix + '/' + name
            path = snapshots._within(source, full_name)
            if not path.exists():
                deleted.append(full_name)
                continue
            info = path.stat()
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('Nonregular owned source input')
            mode = stat.S_IMODE(info.st_mode)
            if os.name == 'nt':
                mode = 0o755 if tracked.get(name) == '100755' else 0o644
            files[full_name] = dict(path=full_name, sha256=snapshots._digest(path), size=info.st_size,
                                   mode=mode, tracked=name in tracked, repository=prefix)
    for name in selection['files']:
        if name not in files:
            raise ValueError('Required owned source input is absent: ' + name)
    return dict(version=1, source=str(source), repositories=repositories, deleted=sorted(deleted),
                files=[files[name] for name in sorted(files)],
                execution=dict(mode='owned-worktree', agent=agent, workspace=str(receipt_path),
                               workspaceId=plan['id'], selection=selection))


def capture_owned(source, state, receipt_path, agent, selection):
    selection = validate_selection(selection)
    provider = lambda root: inventory_owned(root, state, receipt_path, agent, selection)
    captured = snapshots.capture(Path(source), Path(state), inventory=provider)
    manifest = snapshots._verify(Path(captured['path']))
    return dict(captured, execution=manifest['execution'], source=manifest['source'])


def verify_owned(snapshot, source, state):
    manifest = snapshots._verify(Path(snapshot['path']))
    execution = manifest['execution']
    if execution.get('mode') != 'owned-worktree' or snapshot.get('execution') != execution:
        raise ValueError('Owned execution record mismatch')
    current = inventory_owned(source, state, execution['workspace'], execution['agent'], execution['selection'])
    if current != {key: value for key, value in manifest.items() if key != 'id'}:
        raise ValueError('Owned worktree inputs changed after capture; replan')
    return manifest
