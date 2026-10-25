-- Выполняется перед каждым тестом (@Sql на site.qgames.BaseIntegrationTest): чистит БД и загружает базовые фикстуры.
-- Значения должны совпадать с константами в site.qgames.game.support.TestData. Профили игроков (таблица user) —
-- в user-auth.sql.

TRUNCATE game, room, room_member, room_session, room_session_player, room_session_event, room_connection CASCADE;

-- Каталог игр — как в DML-миграциях 2026-10-18-INSERT-game и 2026-10-22-INSERT-game. Тесты меняют publish_status напрямую.
INSERT INTO game (id, code, title, description, sort_order, publish_status)
VALUES ('019a0000-0000-7000-8000-000000000001', 'nothing', 'Игра в ничто',
        'Тестовая игра: игроки по очереди завершают ход, партия длится заданное число раундов.', 10, 'PUBLISHED'),
       ('019a0000-0000-7000-8000-000000000003', 'checkers', 'Шашки',
        'Шашки на двоих: вариант правил выбирается в настройках комнаты. Сейчас доступны русские шашки.', 15, 'HIDDEN'),
       ('019a0000-0000-7000-8000-000000000002', 'carcassonne', 'Каркассон', 'Строим города, дороги и монастыри. Игра в разработке.',
        20, 'NOT_PUBLISHED');
