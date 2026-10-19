-- Журнал ходов сессии: по нему партию можно восстановить и переиграть. seq совпадает с версией партии после хода.
CREATE TABLE room_session_move
(
    session_id uuid        NOT NULL REFERENCES room_session (id) ON DELETE CASCADE,
    seq        bigint      NOT NULL,
    player_id  uuid        NOT NULL,
    -- Конверт хода {type, data}, как его прислал клиент.
    move       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq)
);
