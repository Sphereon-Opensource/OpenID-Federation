CREATE TABLE account (
    id SERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX account_username_index ON account (username);

INSERT INTO account (username) VALUES ('root');

CREATE TABLE jwk (
    id SERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id INT NOT NULL,
    kty VARCHAR(10) NOT NULL,         -- Key Type
    crv VARCHAR(10),                  -- Curve (used for EC keys)
    kid VARCHAR(255) UNIQUE,          -- Key ID
    x TEXT,                           -- X coordinate (for EC keys)
    y TEXT,                           -- Y coordinate (for EC keys)
    d TEXT,                           -- Private key (should be secured)
    n TEXT,                           -- Modulus (for RSA keys)
    e TEXT,                           -- Exponent (for RSA keys)
    alg VARCHAR(10),                  -- Algorithm
    use VARCHAR(10),                  -- Key Use (sig, enc, etc.)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT FK_AccountJwk FOREIGN KEY (account_id) REFERENCES account (id)  -- Foreign key constraint moved here
);

CREATE INDEX jwk_account_id_index ON jwk (account_id);