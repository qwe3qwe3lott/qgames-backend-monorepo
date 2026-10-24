CREATE TABLE "user_auth#refresh_token"
(
    id         uuid PRIMARY KEY     DEFAULT uuidv7(),
    user_id    uuid        NOT NULL REFERENCES "user_auth#user" (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    -- Заполняется при замене токена новым; отличает ротацию от отзыва при выходе или краже.
    rotated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT "user_auth#refresh_token_hash_uq" UNIQUE (token_hash)
);

CREATE INDEX "user_auth#refresh_token_family_idx" ON "user_auth#refresh_token" (family_id);
