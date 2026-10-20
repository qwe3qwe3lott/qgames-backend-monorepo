CREATE TABLE admin
(
    id            uuid PRIMARY KEY     DEFAULT uuidv7(),
    -- Сравнивается точно, с учётом регистра.
    login         varchar(64)  NOT NULL,
    -- Argon2id, соль внутри хеша.
    password_hash varchar(255) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT admin_login_uq UNIQUE (login)
);
