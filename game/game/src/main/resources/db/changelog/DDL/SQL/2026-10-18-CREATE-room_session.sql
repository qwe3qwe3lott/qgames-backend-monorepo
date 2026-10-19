-- Партия любой игры. Состояние — JSON, формат задаёт модуль игры (games/*) и версия state_schema_version.
-- version растёт с каждым ходом: ход применяется через UPDATE ... WHERE version = :expected.
CREATE TABLE room_session
(
    id                   uuid PRIMARY KEY     DEFAULT uuidv7(),
    room_id              uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    game_type            varchar(32) NOT NULL,
    status               varchar(16) NOT NULL,
    state                jsonb       NOT NULL,
    state_schema_version integer     NOT NULL,
    seed                 bigint      NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz
);

CREATE INDEX room_session_room_idx ON room_session (room_id, created_at DESC);
