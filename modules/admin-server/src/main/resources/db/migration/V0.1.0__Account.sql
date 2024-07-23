CREATE TYPE "KMS_TYPE" AS ENUM ('LOCAL');

CREATE TABLE "account" (
    id  SERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    kms "KMS_TYPE" NOT NULL DEFAULT 'LOCAL'
);