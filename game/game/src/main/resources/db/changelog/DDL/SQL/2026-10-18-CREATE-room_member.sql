-- Присутствующие в комнате. Выход удаляет строку; место игрока в партии хранится в room_session_player.
CREATE TABLE room_member
(
    room_id   uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL,
    role      varchar(16) NOT NULL,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX room_member_user_idx ON room_member (user_id);
