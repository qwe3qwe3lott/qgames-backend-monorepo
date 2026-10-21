-- Журнал событий партии: единственный источник её состояния. Каждое событие — одна операция над документом партии
-- {status, meta, state}; состояние — свёртка журнала общим применителем (SessionPatch). Одна нумерация seq на все
-- изменения партии; журнал только дописывается.
CREATE TABLE room_session_event
(
    session_id uuid        NOT NULL REFERENCES room_session (id) ON DELETE CASCADE,
    seq        bigint      NOT NULL,
    -- Как событие меняет документ.
    method     varchar(8)  NOT NULL CHECK (method IN ('PUT', 'PATCH', 'DELETE', 'INFO')),
    -- Что произошло: код платформы (session-started, …), модуля (move-options-changed, turn-changed, timer-started, …)
    -- или игры (player-passed, …). Состояние по коду не меняется.
    code       varchar(64) NOT NULL,
    -- Полные (немаскированные) данные события, как в кадре потока: PUT и PATCH — {path, value}, DELETE — {path},
    -- INFO — произвольный объект.
    data       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq)
);
