CREATE TABLE "user"
(
    id             uuid PRIMARY KEY      DEFAULT uuidv7(),
    email          varchar(320) NOT NULL,
    email_verified boolean      NOT NULL DEFAULT false,
    nickname       varchar(32)  NOT NULL,
    avatar         varchar(16)  NOT NULL,
    -- Оптимистичная блокировка и порядок CDC-событий (реплики применяют только более новую версию).
    version        bigint       NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT user_email_uq UNIQUE (email)
);
