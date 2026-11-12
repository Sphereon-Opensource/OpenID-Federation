// Stand-in token issuer for the upgrade lane. The OIDC provider is not part of the upgrade, so the lane runs the real
// source and target images against this minimal issuer: discovery document, JWKS and a token endpoint that signs an
// RS256 access token for the requested subject. It is started and deleted by upgrade_lane.py and ships in no image.
const http = require('http');
const crypto = require('crypto');

const issuer = process.env.ISSUER_URL;
const audience = process.env.AUDIENCE || 'openid-federation';
const port = Number(process.env.PORT || 8090);
if (!issuer) throw new Error('ISSUER_URL is required');

const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = 'upgrade-lane-1';
const jwk = { ...publicKey.export({ format: 'jwk' }), kid, use: 'sig', alg: 'RS256' };
const b64 = (value) => Buffer.from(value).toString('base64url');

// The 0.23.0 admin server maps the roles claim to authorities and demands the admin role on every path, so the default
// token carries it. A request for roles= with no value mints a token without any role. The 0.26 admin server accepts
// only an access token whose header typ is at+jwt (RFC 9068) and the earlier one accepts typ JWT, so typ= selects it.
function token(subject, roles, typ, tenant) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ, kid };
  const payload = { iss: issuer, sub: subject, aud: audience, iat: now, exp: now + 3600, scope: 'openid', ...(roles.length > 0 && { roles }), ...(tenant && { tenant_id: tenant }) };
  const signingInput = `${b64(JSON.stringify(header))}.${b64(JSON.stringify(payload))}`;
  return `${signingInput}.${b64(crypto.sign('RSA-SHA256', Buffer.from(signingInput), privateKey))}`;
}

http.createServer((request, response) => {
  const url = new URL(request.url, issuer);
  const send = (status, body) => {
    response.writeHead(status, { 'content-type': 'application/json' });
    response.end(JSON.stringify(body));
  };
  if (url.pathname === '/.well-known/openid-configuration') {
    return send(200, {
      issuer, jwks_uri: `${issuer}/jwks`, token_endpoint: `${issuer}/token`,
      response_types_supported: ['token'], subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['RS256'],
    });
  }
  if (url.pathname === '/jwks') return send(200, { keys: [jwk] });
  if (url.pathname === '/token') {
    const roles = url.searchParams.has('roles') ? url.searchParams.get('roles').split(',').filter(Boolean) : ['admin'];
    const typ = url.searchParams.get('typ') || 'JWT';
    const tenant = url.searchParams.get('tenant_id');
    return send(200, { access_token: token(url.searchParams.get('sub') || 'upgrade-operator', roles, typ, tenant), token_type: 'Bearer', expires_in: 3600 });
  }
  return send(404, { error: 'not_found' });
}).listen(port, '0.0.0.0', () => console.log(`stand-in issuer ${issuer} on ${port}`));
