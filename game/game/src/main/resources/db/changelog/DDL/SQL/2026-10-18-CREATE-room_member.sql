-- Участники комнаты. Место игрока в партии хранится в room_session_player и не зависит от этой таблицы.
-- Вне партии участник есть только пока подключён: закрыл последний поток — строка удаляется сразу. Игрок идущей
-- партии закреплён до её конца: при уходе строка остаётся с left_at, а конец или отмена партии удаляют отсутствующих.
CREATE TABLE room_member
(
    room_id   uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL,
    role      varchar(16) NOT NULL,
    joined_at timestamptz NOT NULL,
    -- Когда игрок идущей партии закрыл последний поток комнаты. NULL — участник присутствует.
    left_at   timestamptz,
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX room_member_user_idx ON room_member (user_id);
