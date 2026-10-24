-- Выполняется перед каждым тестом (@Sql на site.qgames.BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.userauth.support.TestData.

TRUNCATE "user_auth#user", "user_auth#user_identity", "user_auth#refresh_token" CASCADE;

INSERT INTO "user_auth#user" (id, email, nickname, avatar)
VALUES ('01990000-0000-7000-8000-000000000001', 'player@example.com', 'Player', 'CAT'),
       ('01990000-0000-7000-8000-000000000003', 'owner@gmail.com', 'Google Owner', 'CAT'),
       ('01990000-0000-7000-8000-000000000004', 'both@example.com', 'Both', 'DOG');

-- password_hash — Argon2id от TestData.PASSWORD ("correct-horse-1")
INSERT INTO "user_auth#user_identity" (user_id, provider, subject, password_hash)
VALUES ('01990000-0000-7000-8000-000000000001', 'PASSWORD', 'player@example.com',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk'),
       ('01990000-0000-7000-8000-000000000003', 'GOOGLE', 'google-owner', NULL),
       ('01990000-0000-7000-8000-000000000004', 'PASSWORD', 'both@example.com',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk'),
       ('01990000-0000-7000-8000-000000000004', 'GOOGLE', 'google-both', NULL);
