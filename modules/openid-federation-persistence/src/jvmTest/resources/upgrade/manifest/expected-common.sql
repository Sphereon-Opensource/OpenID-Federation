SELECT 'expected.AccountSigningKey.count' AS name, '0' AS value;
SELECT 'expected.Jwk.revocation' AS name, md5(coalesce(string_agg(id || '>' || coalesce(revoked_at::text, '~') || '>' || coalesce(CASE WHEN lower(trim(revoked_reason)) IN ('unspecified', 'compromised', 'superseded') THEN lower(trim(revoked_reason)) END, '~'), E'\n' ORDER BY id), '')) AS value FROM Jwk;
SELECT 'expected.TrustMarkTypeRevision' AS name, md5(coalesce(string_agg(id || '>1>~', E'\n' ORDER BY id), '')) AS value FROM TrustMarkType;
SELECT 'expected.TrustMarkTypeRevision.count' AS name, count(*)::text AS value FROM TrustMarkType;
SELECT 'expected.Account.tenant_source' AS name, count(*)::text AS value FROM Account;
SELECT 'expected.Jwk.kms' AS name, md5(coalesce(string_agg(id || '>' || CASE WHEN kms IN ('MEMORY', 'AWS', 'AZURE') THEN lower(kms) ELSE kms END, E'\n' ORDER BY id), '')) AS value FROM Jwk;
