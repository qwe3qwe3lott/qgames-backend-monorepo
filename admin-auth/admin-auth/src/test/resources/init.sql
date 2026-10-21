-- Выполняется перед каждым тестом (@Sql на BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.adminauth.support.TestData.

TRUNCATE admin, refresh_token CASCADE;

-- password_hash — Argon2id от TestData.PASSWORD ("correct-horse-1").
-- Operator создан раньше Root, хотя его id больше: порядок списка задаёт created_at, а не id.
INSERT INTO admin (id, login, password_hash, created_at)
VALUES ('01990000-0000-7000-8000-0000000000a1', 'root',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk',
        '2026-10-02 00:00:00+00'),
       ('01990000-0000-7000-8000-0000000000a2', 'operator',
        '$argon2id$v=19$m=16384,t=2,p=1$4voBS5f4G3DCHN5PUcEFPg$0vZ6WePBIfRL3H+PY8ijDTDvG9lqEbAUVvW9d/zO0Jk',
        '2026-10-01 00:00:00+00');
