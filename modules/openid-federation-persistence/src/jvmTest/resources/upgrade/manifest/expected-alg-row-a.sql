SELECT 'expected.Jwk.alg' AS name, md5(coalesce(string_agg(id || '>' || coalesce(alg, "key"::json ->> 'alg', '~'), E'\n' ORDER BY id), '')) AS value FROM Jwk;
