-- Партия любой игры. Состояние — JSON, формат задаёт модуль игры (games/*) и версия state_schema_version.
-- version растёт с каждым ходом: ход применяется через UPDATE ... WHERE version = :expected.
CREATE TABLE room_session
(
    id                   uuid PRIMARY KEY     DEFAULT uuidv7(),
    room_id              uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    game_code            varchar(32) NOT NULL REFERENCES game (code),
    status               varchar(16) NOT NULL,
    state                jsonb       NOT NULL,
    state_schema_version integer     NOT NULL,
    seed                 bigint      NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    -- Снимок настроек комнаты на момент старта: смена настроек между партиями идущую партию не меняет.
    time_control_mode    varchar(16) NOT NULL,
    move_seconds         integer,
    game_settings        jsonb       NOT NULL,
    -- Когда истекает время текущего хода. NULL — без таймера или партия закончена/отменена.
    move_deadline        timestamptz,
    -- С какого момента партия на паузе: ходы запрещены, таймер хода и сроки возвращения игроков заморожены.
    -- NULL — партия не на паузе.
    paused_at            timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz
);

CREATE INDEX room_session_room_idx ON room_session (room_id, created_at DESC);
-- Поиск партий с истёкшим временем хода (MoveTimeoutJob); партии на паузе отсекаются фильтром по paused_at.
CREATE INDEX room_session_move_deadline_idx ON room_session (move_deadline) WHERE status = 'IN_PROGRESS';
