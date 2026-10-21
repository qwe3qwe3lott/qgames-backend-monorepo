-- Журнал событий партии: единственный источник её состояния (оно собирается свёрткой журнала, см. SessionEngine).
-- Одна нумерация seq на все изменения партии; журнал только дописывается.
CREATE TABLE room_session_event
(
    session_id uuid        NOT NULL REFERENCES room_session (id) ON DELETE CASCADE,
    seq        bigint      NOT NULL,
    -- Тип события: платформы (started, session-finished, …), модуля (player-changed, turns-changed, timer-changed)
    -- или игры (moved, …).
    type       varchar(64) NOT NULL,
    -- Полные (немаскированные) данные события; формат задаёт платформа, модуль или игра.
    data       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq)
);
