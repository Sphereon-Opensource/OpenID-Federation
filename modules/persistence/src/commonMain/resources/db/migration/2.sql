CREATE TABLE jwk (
    id SERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id INTEGER NOT NULL,
    key JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_reason TEXT,
    FOREIGN KEY (account_id) REFERENCES account (id)
);

CREATE INDEX jwks_account_id_index ON jwk (account_id);