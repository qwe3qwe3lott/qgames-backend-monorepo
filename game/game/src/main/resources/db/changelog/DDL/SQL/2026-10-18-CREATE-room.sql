CREATE TABLE room
(
    id                 uuid PRIMARY KEY     DEFAULT uuidv7(),
    game_type          varchar(32) NOT NULL,
    -- NULL, если в комнате никого не осталось: владельцем станет первый вошедший.
    owner_id           uuid,
    status             varchar(16) NOT NULL,
    -- Идущая партия (без FK: room_session ссылается на room).
    current_session_id uuid,
    -- С какого момента в комнате нет ни игроков, ни наблюдателей. Используется для автоудаления.
    empty_since        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX room_empty_since_idx ON room (empty_since) WHERE empty_since IS NOT NULL;
