SELECT 'actual.AccountSigningKey.count' AS name, count(*)::text AS value FROM AccountSigningKey;
SELECT 'actual.Jwk.revocation' AS name, md5(coalesce(string_agg(id || '>' || coalesce(expires_at::text, '~') || '>' || coalesce(revocation_reason, '~'), E'\n' ORDER BY id), '')) AS value FROM Jwk;
SELECT 'actual.Jwk.alg' AS name, md5(coalesce(string_agg(id || '>' || coalesce(alg, '~'), E'\n' ORDER BY id), '')) AS value FROM Jwk;
SELECT 'actual.TrustMarkTypeRevision' AS name, md5(coalesce(string_agg(trust_mark_type_id || '>' || version || '>' || coalesce(definition, '~') , E'\n' ORDER BY trust_mark_type_id), '')) AS value FROM TrustMarkTypeRevision WHERE version = 1 AND definition IS NULL;
SELECT 'actual.TrustMarkTypeRevision.count' AS name, count(*)::text AS value FROM TrustMarkTypeRevision;
SELECT 'actual.Account.tenant_source' AS name, count(*)::text AS value FROM Account WHERE tenant_source = 'account';
SELECT 'actual.Jwk.kms' AS name, md5(coalesce(string_agg(id || '>' || kms, E'\n' ORDER BY id), '')) AS value FROM Jwk;
