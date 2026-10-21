-- Места игроков партии. Игроки закреплены до конца партии: ушедший остаётся отсутствующим участником комнаты и,
-- вернувшись, продолжает с того же места.
CREATE TABLE room_session_player
(
    session_id uuid    NOT NULL REFERENCES room_session (id) ON DELETE CASCADE,
    seat       integer NOT NULL,
    user_id    uuid    NOT NULL,
    PRIMARY KEY (session_id, seat),
    CONSTRAINT room_session_player_user_uq UNIQUE (session_id, user_id)
);
