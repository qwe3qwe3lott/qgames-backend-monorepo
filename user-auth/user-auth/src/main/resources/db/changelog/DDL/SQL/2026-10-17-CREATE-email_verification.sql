CREATE TABLE email_verification
(
    id          uuid PRIMARY KEY     DEFAULT uuidv7(),
    user_id     uuid        NOT NULL REFERENCES "user" (id) ON DELETE CASCADE,
    code_hash   varchar(64) NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX email_verification_active_idx ON email_verification (user_id, created_at DESC) WHERE consumed_at IS NULL;
