-- Выполняется перед каждым тестом (@Sql на site.qgames.BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.game.support.TestData.

TRUNCATE "game#game", "game#user_auth#user", "game#room", "game#room_member", "game#room_session", "game#room_session_player",
    "game#room_session_event", "game#room_connection" CASCADE;

-- Каталог игр — как в DML-миграциях 2026-10-18-INSERT-game#game и 2026-10-22-INSERT-game#game. Тесты меняют publish_status напрямую.
INSERT INTO "game#game" (code, title, description, sort_order, publish_status)
VALUES ('nothing', 'Игра в ничто',
        'Тестовая игра: игроки по очереди завершают ход, партия длится заданное число раундов.', 10, 'PUBLISHED'),
       ('checkers', 'Шашки',
        'Шашки на двоих: вариант правил выбирается в настройках комнаты. Сейчас доступны русские шашки.', 15, 'HIDDEN'),
       ('carcassonne', 'Каркассон', 'Строим города, дороги и монастыри. Игра в разработке.', 20, 'NOT_PUBLISHED');

-- Реплика "user_auth#user", как будто уже доставлена событием профиля. Независима от фикстур user-auth.
INSERT INTO "game#user_auth#user" (id, nickname, avatar, version)
VALUES ('01990000-0000-7000-8000-00000000000a', 'Alice', 'CAT', 0),
       ('01990000-0000-7000-8000-00000000000b', 'Bob', 'DOG', 0),
       ('01990000-0000-7000-8000-00000000000c', 'Carol', 'CAT', 0),
       ('01990000-0000-7000-8000-00000000000d', 'Dave', 'DOG', 0),
       ('01990000-0000-7000-8000-00000000000e', 'Eve', 'CAT', 0);
