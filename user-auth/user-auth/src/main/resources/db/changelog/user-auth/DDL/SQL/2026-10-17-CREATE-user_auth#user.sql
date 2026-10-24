CREATE TABLE "user_auth#user"
(
    id         uuid PRIMARY KEY      DEFAULT uuidv7(),
    email      varchar(320) NOT NULL,
    nickname   varchar(32)  NOT NULL,
    avatar     varchar(16)  NOT NULL,
    -- Оптимистичная блокировка и порядок событий профиля (реплики применяют только более новую версию).
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT "user_auth#user_email_uq" UNIQUE (email)
);
