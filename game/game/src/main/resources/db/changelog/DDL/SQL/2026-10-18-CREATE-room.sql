CREATE TABLE room
(
    id                     uuid PRIMARY KEY     DEFAULT uuidv7(),
    game_code              varchar(32) NOT NULL REFERENCES game (code),
    -- NULL — у комнаты нет владельца: идёт окно владения (owner_grace_*) или в комнате никого нет (владельцем станет
    -- первый вошедший). Владелец всегда присутствует в комнате.
    owner_id               uuid,
    -- Окно владения: владелец ушёл, и до owner_grace_deadline вернуть владение может только он (owner_grace_user_id).
    -- По истечении окна RoomOwnerGraceJob передаёт владение присутствующему участнику. Оба NULL — окна нет.
    owner_grace_user_id    uuid,
    owner_grace_deadline   timestamptz,
    status                 varchar(16) NOT NULL,
    -- Идущая партия (без FK: room_session ссылается на room).
    current_session_id     uuid,
    -- С какого момента в комнате нет присутствующих участников (отсутствующие игроки партии комнату не держат).
    -- Используется для автоудаления.
    empty_since            timestamptz,
    -- Настройки комнаты: с ними стартует следующая партия. Режим времени (NONE, PER_MOVE, …) и секунды на ход.
    time_control_mode      varchar(16) NOT NULL,
    move_seconds           integer,
    -- Настройки конкретной игры, формат задаёт модуль игры (games/*).
    game_settings          jsonb       NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT room_owner_grace_ck CHECK ((owner_grace_user_id IS NULL) = (owner_grace_deadline IS NULL)),
    CONSTRAINT room_owner_grace_no_owner_ck CHECK (owner_grace_user_id IS NULL OR owner_id IS NULL)
);

CREATE INDEX room_empty_since_idx ON room (empty_since) WHERE empty_since IS NOT NULL;
-- Комнаты игры (ForceDisabledGameCleanupJob).
CREATE INDEX room_game_code_idx ON room (game_code);
-- Поиск истёкших окон владения (RoomOwnerGraceJob).
CREATE INDEX room_owner_grace_deadline_idx ON room (owner_grace_deadline) WHERE owner_grace_deadline IS NOT NULL;
