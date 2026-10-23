"""Validate resolved operator inputs without starting any containers.

Run: python -m unittest discover -s .docker/tests -v
Docker Compose is required; its config command does not need a daemon.
"""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
OPERATOR_KEYS = (
    'DATASOURCE_URL', 'DATASOURCE_USER', 'DATASOURCE_PASSWORD', 'APP_KEY',
    'KMS_PROVIDER', 'ROOT_IDENTIFIER', 'DEV_MODE', 'LOGGER_SEVERITY', 'LOGGER_OUTPUT',
    'CORS_ALLOWED_ORIGINS', 'CORS_ALLOWED_METHODS', 'CORS_ALLOWED_HEADERS', 'CORS_MAX_AGE',
    'AZURE_KEYVAULT_APPLICATION_ID', 'AZURE_KEYVAULT_URL', 'AZURE_KEYVAULT_TENANT_ID',
    'AZURE_KEYVAULT_CLIENT_ID', 'AZURE_KEYVAULT_CLIENT_SECRET', 'AZURE_KEYVAULT_MAX_RETRIES',
    'AZURE_KEYVAULT_BASE_DELAY', 'AZURE_KEYVAULT_MAX_DELAY',
)


def render(relative, overrides=None):
    env = os.environ.copy()
    env.update({key: 'test-' + key.lower() for key in OPERATOR_KEYS})
    env.update(DATASOURCE_DB='compose_test', FEDERATION_HOSTS='Host(`federation.example`)',
               FEDERATION_ADMIN_HOSTS='Host(`admin.example`)', ADMIN_IP_WHITELIST='127.0.0.1/32')
    for key in ('OIDF_OAUTH2_ISSUER_URI', 'OIDF_OAUTH2_AUDIENCE', 'OIDF_OAUTH2_JWT_AUTH_ENABLED',
                'OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI'):
        env.pop(key, None)
    env.update(overrides or {})
    result = subprocess.run(
        ['docker', 'compose', '-f', str(ROOT / relative), 'config', '--format', 'json'],
        cwd=ROOT, env=env, text=True, capture_output=True, check=True, timeout=30,
    )
    return json.loads(result.stdout)['services']


class ComposeEnvironmentTest(unittest.TestCase):
    def test_operator_inputs_reach_both_services_in_local_and_production_compose(self):
        for relative in ('docker-compose.yaml', '.docker/prod-deployment/docker-compose.yaml'):
            services = render(relative)
            for name in ('openid-federation-server', 'openid-federation-admin-server'):
                with self.subTest(compose=relative, service=name):
                    actual = services[name]['environment']
                    for key in OPERATOR_KEYS:
                        self.assertEqual('test-' + key.lower(), actual[key], key)
                    self.assertTrue(all(' ' not in key for key in actual))

    def test_production_admin_accepts_normalized_auth_inputs(self):
        values = {'OIDF_OAUTH2_ISSUER_URI': 'https://issuer.example',
                  'OIDF_OAUTH2_AUDIENCE': 'federation-admin', 'OIDF_OAUTH2_JWT_AUTH_ENABLED': 'true'}
        actual = render('.docker/prod-deployment/docker-compose.yaml', values)['openid-federation-admin-server']['environment']
        for key, value in values.items():
            self.assertEqual(value, actual[key])

    def test_production_admin_preserves_legacy_issuer_fallback(self):
        actual = render('.docker/prod-deployment/docker-compose.yaml',
                        {'OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI': 'https://legacy.example'})['openid-federation-admin-server']['environment']
        self.assertEqual('https://legacy.example', actual['OIDF_OAUTH2_ISSUER_URI'])
        self.assertEqual('auto', actual['OIDF_OAUTH2_JWT_AUTH_ENABLED'])


if __name__ == '__main__':
    unittest.main()
