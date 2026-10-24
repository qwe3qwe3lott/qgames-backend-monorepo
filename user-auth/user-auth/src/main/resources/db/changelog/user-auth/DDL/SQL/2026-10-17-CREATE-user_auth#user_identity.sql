CREATE TABLE "user_auth#user_identity"
(
    id            uuid PRIMARY KEY      DEFAULT uuidv7(),
    user_id       uuid         NOT NULL REFERENCES "user_auth#user" (id) ON DELETE CASCADE,
    provider      varchar(32)  NOT NULL,
    subject       varchar(320) NOT NULL,
    password_hash varchar(255),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT "user_auth#user_identity_provider_subject_uq" UNIQUE (provider, subject),
    CONSTRAINT "user_auth#user_identity_user_provider_uq" UNIQUE (user_id, provider)
);
