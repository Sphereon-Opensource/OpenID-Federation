"""Explicitly structural build-profile checks; no Gradle acceptance is claimed."""
from pathlib import Path
import os
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tooling/build-coordinator'))
from store import Store, normalize, build_key
from processes import ExclusiveLock
from worker import gradle_command

WEB_MODULES = ('client', 'client-impl', 'client-public', 'common', 'core-impl',
               'core-public', 'http-resolver', 'openapi', 'wallet-public')


class CanonicalTargetProfileTests(unittest.TestCase):
    def test_ordinary_request_retains_legacy_execution_identity(self):
        ordinary = normalize(dict(snapshot='a' * 64, tasks=[':modules:openid-federation-core-public:jvmJar'], memory_gb=1))
        legacy = {key: value for key, value in ordinary.items() if key != 'offline'}
        self.assertEqual(build_key(legacy), build_key(ordinary))
        self.assertNotEqual(build_key(ordinary), build_key(dict(ordinary, offline=True)))

    def test_offline_submission_refuses_absent_legacy_stale_or_unheld_capability(self):
        for kind in ('absent', 'legacy', 'stale', 'unheld'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                store = Store(state)
                store.register_snapshot(dict(id='a' * 64, path=str(state / 'snapshot'), files=1))
                request = dict(snapshot='a' * 64, tasks=[':modules:openid-federation-core-public:jvmJar'], memory_gb=1)
                marker = dict(pid=os.getpid(), heartbeat=time.time(), offline_request_version=1)
                if kind == 'legacy':
                    marker.pop('offline_request_version')
                if kind == 'stale':
                    marker['heartbeat'] = time.time() - 31
                if kind != 'absent':
                    store.metadata('worker', marker)
                if kind == 'unheld':
                    with ExclusiveLock(state / 'worker.lock'):
                        pass
                    with self.assertRaisesRegex(ValueError, 'offline-capable worker'):
                        store.submit(dict(request, offline=True), 'fixture')
                else:
                    with ExclusiveLock(state / 'worker.lock'):
                        with self.assertRaisesRegex(ValueError, 'offline-capable worker'):
                            store.submit(dict(request, offline=True), 'fixture')
                self.assertEqual([], store.jobs())
                store.submit(request, 'fixture')
                self.assertEqual(1, len(store.jobs()))

    def test_offline_submission_with_actual_live_held_capability_preserves_request(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            store = Store(state)
            store.register_snapshot(dict(id='a' * 64, path=str(state / 'snapshot'), files=1))
            store.metadata('worker', dict(pid=os.getpid(), heartbeat=time.time(), offline_request_version=1))
            with ExclusiveLock(state / 'worker.lock'):
                request = store.submit(dict(snapshot='a' * 64,
                    tasks=[':modules:openid-federation-core-public:jvmJar'], memory_gb=1, offline=True), 'fixture')
            self.assertIs(True, store.result(request)['spec']['offline'])
            self.assertEqual(1, len(store.jobs()))

    def test_offline_is_a_validated_boolean_with_ordinary_default_and_distinct_identity(self):
        request = dict(snapshot='0' * 64, tasks=[':modules:openid-federation-core-public:jvmJar'], memory_gb=1)
        ordinary = normalize(request)
        self.assertIs(False, ordinary.get('offline'))
        try:
            disabled = normalize(dict(request, offline=False))
            enabled = normalize(dict(request, offline=True))
        except ValueError as error:
            self.fail('Explicit boolean offline mode must be supported: ' + str(error))
        self.assertEqual(build_key(ordinary), build_key(disabled))
        self.assertNotEqual(build_key(ordinary), build_key(enabled))
        self.assertIs(True, enabled['offline'])
        for invalid in (None, 0, 1, 'true', 'false', [], {}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                normalize(dict(request, offline=invalid))

    def test_real_coordinator_emits_offline_only_for_explicit_true_including_cache_off(self):
        spec = normalize(dict(snapshot='0' * 64,
            tasks=[':modules:openid-federation-core-public:jvmJar'], memory_gb=1))
        ordinary = gradle_command(spec, ROOT, ROOT / 'tooling/build-coordinator/events.gradle', {'java': 'java'})
        self.assertNotIn('--offline', ordinary)
        for enabled in (False, True):
            for cache in ('auto', 'off'):
                with self.subTest(offline=enabled, cache=cache):
                    argv = gradle_command(dict(spec, offline=enabled, configuration_cache=cache),
                        ROOT, ROOT / 'tooling/build-coordinator/events.gradle', {'java': 'java'})
                    self.assertEqual(1 if enabled else 0, argv.count('--offline'))

    def test_root_structurally_defaults_all_and_overwrites_stale_system_selection(self):
        source = (ROOT / 'build.gradle.kts').read_text('utf-8')
        self.assertIn('providers.gradleProperty("canonical.kmp.targets").orNull ?: "all"', source)
        self.assertIn('System.setProperty("kmp.targets", canonicalKmpTargets)', source)
        self.assertLess(source.index('System.setProperty("kmp.targets", canonicalKmpTargets)'),
                        source.index('subprojects {'))
        self.assertNotIn('System.getProperty("kmp.targets")', source)

    def test_root_structurally_rejects_empty_unknown_or_padded_profiles(self):
        source = (ROOT / 'build.gradle.kts').read_text('utf-8')
        self.assertIn('require(canonicalKmpTargets == "jvm" || canonicalKmpTargets == "all")', source)
        self.assertNotIn('gradleProperty("canonical.kmp.targets").orNull?.trim()', source)

    def test_selected_web_modules_structurally_use_shared_helpers_and_optional_lookups(self):
        for suffix in WEB_MODULES:
            with self.subTest(module=suffix):
                source = (ROOT / 'modules' / ('openid-federation-' + suffix) / 'build.gradle.kts').read_text('utf-8')
                self.assertEqual(1, source.count('configureJsTargetIfEnabled {'))
                self.assertEqual(1, source.count('configureWasmJsTargetIfEnabled {'))
                for name in ('jsMain', 'jsTest', 'wasmJsMain', 'wasmJsTest'):
                    self.assertNotIn('val ' + name + ' by getting', source)
                if 'npmPublish {' in source:
                    self.assertIn('if (kotlin.targets.findByName("js") != null) {', source)

    def test_real_coordinator_emits_profile_project_property_and_cache_off_without_jvm_bypass(self):
        spec = normalize(dict(snapshot='0' * 64,
            tasks=[':modules:openid-federation-core-public:prepareWorkspaceArtifacts'],
            profile='development', configuration_cache='off',
            properties={'canonical.kmp.targets': 'jvm'}))
        argv = gradle_command(spec, ROOT, ROOT / 'tooling/build-coordinator/events.gradle', {'java': 'java'})
        self.assertIn('-Pcanonical.kmp.targets=jvm', argv)
        self.assertIn('--no-configuration-cache', argv)
        self.assertNotIn('--configuration-cache', argv)
        self.assertFalse(any(arg.startswith('-Dkmp.targets=') for arg in argv))
        ordinary = normalize(dict(snapshot='0' * 64, tasks=[':modules:openid-federation-core-public:jvmJar']))
        self.assertEqual({}, ordinary['properties'])


if __name__ == '__main__':
    unittest.main()
