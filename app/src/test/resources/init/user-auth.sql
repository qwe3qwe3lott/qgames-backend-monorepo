-- Выполняется перед каждым тестом (@Sql на site.qgames.BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.userauth.support.TestData (и site.qgames.game.support.TestData
-- для игроков тестов game).

TRUNCATE "user", user_identity, user_refresh_token CASCADE;

INSERT INTO "user" (id, login, nickname, avatar, created_at)
VALUES ('01990000-0000-7000-8000-000000000001', 'player', 'Player', 'CAT', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-000000000003', 'google-owner', 'Google Owner', 'CAT', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-000000000004', 'both', 'Both', 'DOG', '2026-10-01 00:00:00+00');

-- Игроки тестов game: ник и аватар game читает из этой таблицы (join). У TestData.STRANGER строки нет.
INSERT INTO "user" (id, login, nickname, avatar, created_at)
VALUES ('01990000-0000-7000-8000-00000000000a', 'alice', 'Alice', 'CAT', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-00000000000b', 'bob', 'Bob', 'DOG', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-00000000000c', 'carol', 'Carol', 'CAT', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-00000000000d', 'dave', 'Dave', 'DOG', '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-00000000000e', 'eve', 'Eve', 'CAT', '2026-10-01 00:00:00+00');

-- password_hash — Argon2id от TestData.PASSWORD ("correct-horse-1")
INSERT INTO user_identity (id, user_id, provider, subject, password_hash, created_at)
VALUES ('01990000-0000-7000-8000-000000000101', '01990000-0000-7000-8000-000000000001', 'PASSWORD', 'player',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk',
        '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-000000000103', '01990000-0000-7000-8000-000000000003', 'GOOGLE', 'google-owner', NULL,
        '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-000000000104', '01990000-0000-7000-8000-000000000004', 'PASSWORD', 'both',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk',
        '2026-10-01 00:00:00+00'),
       ('01990000-0000-7000-8000-000000000105', '01990000-0000-7000-8000-000000000004', 'GOOGLE', 'google-both', NULL,
        '2026-10-01 00:00:00+00');
