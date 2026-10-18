CREATE TABLE "user"
(
    id             uuid PRIMARY KEY      DEFAULT uuidv7(),
    email          varchar(320) NOT NULL,
    email_verified boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT user_email_uq UNIQUE (email)
);
