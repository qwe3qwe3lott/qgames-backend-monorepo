-- Партия любой игры. Состояние не хранится: оно собирается из журнала room_session_event (формат событий задаёт
-- модуль игры и версия state_schema_version).
CREATE TABLE room_session
(
    id                   uuid PRIMARY KEY     DEFAULT uuidv7(),
    room_id              uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    game_code            varchar(32) NOT NULL REFERENCES game (code),
    status               varchar(16) NOT NULL,
    state_schema_version integer     NOT NULL,
    seed                 bigint      NOT NULL,
    -- seq последнего события журнала; новые события пишутся с last_seq + 1 под блокировкой комнаты.
    last_seq             bigint      NOT NULL DEFAULT 0,
    -- Снимок настроек комнаты на момент старта: смена настроек между партиями идущую партию не меняет.
    time_control_mode    varchar(16) NOT NULL,
    move_seconds         integer,
    game_settings        jsonb       NOT NULL,
    -- Проекция таймера для поиска истёкшего времени хода (MoveTimeoutJob): дедлайн идущего таймера. NULL — без
    -- таймера, таймер на паузе или партия закончена/отменена. Ставится при записи событий.
    move_deadline        timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz
);

CREATE INDEX room_session_room_idx ON room_session (room_id, created_at DESC);
-- Поиск партий с истёкшим временем хода (MoveTimeoutJob).
CREATE INDEX room_session_move_deadline_idx ON room_session (move_deadline) WHERE status = 'IN_PROGRESS';
