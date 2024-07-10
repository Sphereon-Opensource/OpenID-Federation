CREATE TYPE "public"."KMS_TYPE" AS ENUM ('LOCAL');

CREATE TABLE "public"."account" (
    id  SERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    kms "public"."KMS_TYPE" NOT NULL DEFAULT 'LOCAL'
);
