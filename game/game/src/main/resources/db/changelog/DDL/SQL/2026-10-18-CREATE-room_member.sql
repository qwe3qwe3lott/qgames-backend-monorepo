-- Присутствующие в комнате. Место игрока в партии хранится в room_session_player и не зависит от этой таблицы.
-- Выход отложенный: leave ставит leave_deadline, а строку удаляет RoomLeaveJob, когда срок истёк.
CREATE TABLE room_member
(
    room_id        uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL,
    role           varchar(16) NOT NULL,
    joined_at      timestamptz NOT NULL,
    -- Когда участник, вышедший из комнаты, перестанет в ней числиться. NULL — участник присутствует.
    leave_deadline timestamptz,
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX room_member_user_idx ON room_member (user_id);
-- Поиск участников с истёкшим сроком выхода (RoomLeaveJob).
CREATE INDEX room_member_leave_deadline_idx ON room_member (leave_deadline) WHERE leave_deadline IS NOT NULL;
