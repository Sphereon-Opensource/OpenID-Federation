"""Source-level settings checks plus real coordinator policy controls.

These are not Gradle execution or repository-resolution acceptance tests.
The optional source override lets the controls run before exact runtime reuse.
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, os.environ.get('CANONICAL_COORDINATOR_TEST_SOURCE',
                                str(ROOT / 'tooling/build-coordinator')))
from worker import build_environment, verify_repository_layers


class CanonicalSettingsTests(unittest.TestCase):
    def test_settings_structurally_treat_worker_cleared_legacy_values_as_absent(self):
        source = (ROOT / 'settings.gradle.kts').read_text('utf-8')
        self.assertEqual(2, source.count(
            'System.getenv("WORKSPACE_MAVEN_REPO")?.takeIf { it.isNotEmpty() }'))
        self.assertEqual(2, source.count(
            'System.getenv("WORKSPACE_MAVEN_MODULES")?.takeIf { it.isNotEmpty() }'))

    def test_settings_structurally_require_captured_policy_helper_not_broad_fallback(self):
        source = (ROOT / 'settings.gradle.kts').read_text('utf-8')
        self.assertEqual(2, source.count('if (exactRepositoryPolicy) {'))
        self.assertEqual(2, source.count('gradle.startParameter.initScripts.any { it.canonicalFile == policyHelper }'))
        self.assertEqual(2, source.count('Exact repository policy cannot be mixed with legacy Maven selection'))

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.lane = self.base / 'lane'
        self.helper = self.lane / 'tooling/workspace-publish/repository-layers.gradle'
        self.helper.parent.mkdir(parents=True)
        self.helper.write_text('// verifier presence fixture', encoding='utf-8')
        repository = self.base / 'generation/repo'
        repository.mkdir(parents=True)
        artifact = repository / 'com/example/library/1/library-1.jar'
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b'literal-library')
        self.artifact = artifact
        self.policy = dict(schemaVersion=1, components={'com.example:library:1': str(repository)},
            files=[dict(repository=str(repository), path='com/example/library/1/library-1.jar',
                        size=15, sha256=hashlib.sha256(b'literal-library').hexdigest())])

    def test_real_worker_no_policy_preserves_ordinary_environment(self):
        environment = build_environment({}, base_environment={'PATH': 'literal', 'UNRELATED_SECRET': 'not-captured'})
        self.assertEqual({'PATH': 'literal'}, environment)
        self.assertIsNone(verify_repository_layers(dict(environment=environment), self.lane))

    def test_real_worker_accepts_exact_inline_policy_with_cleared_legacy_environment(self):
        environment = build_environment(dict(WORKSPACE_REPOSITORY_POLICY=json.dumps(self.policy),
            WORKSPACE_MAVEN_REPO='', WORKSPACE_MAVEN_MODULES='', WORKTREE_MAVEN_REPO=''), base_environment={})
        self.assertEqual('', environment['WORKSPACE_MAVEN_REPO'])
        self.assertEqual('', environment['WORKSPACE_MAVEN_MODULES'])
        self.assertEqual(self.helper, verify_repository_layers(dict(environment=environment), self.lane))

    def test_real_worker_accepts_exact_policy_file_and_rejects_changed_file_or_artifact(self):
        policy_file = self.base / 'policy.json'
        payload = json.dumps(self.policy).encode('utf-8')
        policy_file.write_bytes(payload)
        environment = dict(WORKSPACE_REPOSITORY_POLICY_FILE=str(policy_file),
                           WORKSPACE_REPOSITORY_POLICY_SHA256=hashlib.sha256(payload).hexdigest(),
                           WORKSPACE_MAVEN_REPO='', WORKSPACE_MAVEN_MODULES='')
        self.assertEqual(self.helper, verify_repository_layers(dict(environment=environment), self.lane))
        policy_file.write_bytes(payload + b' ')
        with self.assertRaisesRegex(ValueError, 'changed after submission'):
            verify_repository_layers(dict(environment=environment), self.lane)
        policy_file.write_bytes(payload)
        self.artifact.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'artifact changed'):
            verify_repository_layers(dict(environment=environment), self.lane)

    def test_real_worker_refuses_malformed_and_mixed_policy(self):
        invalid = [dict(WORKSPACE_REPOSITORY_POLICY='{"schemaVersion":2,"components":{},"files":[]}'),
                   dict(WORKSPACE_REPOSITORY_POLICY=json.dumps(self.policy), WORKSPACE_MAVEN_REPO=str(self.base)),
                   dict(WORKSPACE_REPOSITORY_POLICY=json.dumps(self.policy), WORKTREE_MAVEN_REPO=str(self.base)),
                   dict(WORKSPACE_REPOSITORY_POLICY=json.dumps(self.policy),
                        WORKSPACE_REPOSITORY_POLICY_FILE=str(self.base / 'policy.json'),
                        WORKSPACE_REPOSITORY_POLICY_SHA256='0' * 64)]
        for environment in invalid:
            with self.subTest(environment=environment):
                with self.assertRaises(ValueError):
                    verify_repository_layers(dict(environment=environment), self.lane)


if __name__ == '__main__':
    unittest.main()
