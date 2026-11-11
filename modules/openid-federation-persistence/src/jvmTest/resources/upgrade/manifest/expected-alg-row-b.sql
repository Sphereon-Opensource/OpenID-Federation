SELECT 'expected.Jwk.alg' AS name, md5(coalesce(string_agg(id || '>' || coalesce("key"::json ->> 'alg', '~'), E'\n' ORDER BY id), '')) AS value FROM Jwk;
