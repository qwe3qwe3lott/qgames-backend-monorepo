-- Выполняется перед каждым тестом (@Sql на BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.game.support.TestData.

TRUNCATE game, "user_auth#user", room, room_member, room_session, room_session_player, room_session_move, shedlock CASCADE;

-- Каталог игр — как в DML-миграции 2026-10-18-INSERT-game. Тесты меняют publish_status напрямую.
INSERT INTO game (code, title, description, sort_order, publish_status)
VALUES ('nothing', 'Игра в ничто',
        'Тестовая игра: игроки по очереди завершают ход, партия длится заданное число раундов.', 10, 'PUBLISHED'),
       ('carcassonne', 'Каркассон', 'Строим города, дороги и монастыри. Игра в разработке.', 20, 'NOT_PUBLISHED');

-- Реплика user_auth."user", как будто уже доставлена по CDC.
INSERT INTO "user_auth#user" (id, nickname, avatar, version)
VALUES ('01990000-0000-7000-8000-00000000000a', 'Alice', 'CAT', 0),
       ('01990000-0000-7000-8000-00000000000b', 'Bob', 'DOG', 0),
       ('01990000-0000-7000-8000-00000000000c', 'Carol', 'CAT', 0),
       ('01990000-0000-7000-8000-00000000000d', 'Dave', 'DOG', 0),
       ('01990000-0000-7000-8000-00000000000e', 'Eve', 'CAT', 0);
