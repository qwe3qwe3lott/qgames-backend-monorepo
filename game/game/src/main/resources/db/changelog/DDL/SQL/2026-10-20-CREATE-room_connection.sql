-- Открытые потоки событий комнаты (SSE). Подключение и есть нахождение в комнате: пока у пользователя есть хотя бы
-- одно подключение, он присутствует; когда закрылось последнее, он уходит из комнаты (игрок идущей партии остаётся
-- отсутствующим, room_member.left_at).
-- Инстанс, держащий поток, раз в ~5 с продлевает аренду expires_at своих живых подключений. Если инстанс упал,
-- аренда истекает, и RoomConnectionCleanupJob удаляет подключения и уводит пользователей.
CREATE TABLE room_connection
(
    id          uuid PRIMARY KEY     DEFAULT uuidv7(),
    room_id     uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL,
    -- Случайный id процесса game, который держит поток (генерируется при старте).
    instance_id uuid        NOT NULL,
    expires_at  timestamptz NOT NULL
);

-- Остались ли у пользователя подключения к комнате.
CREATE INDEX room_connection_room_user_idx ON room_connection (room_id, user_id);
-- Продление аренды подключений инстанса.
CREATE INDEX room_connection_instance_idx ON room_connection (instance_id);
-- Поиск протухших подключений (RoomConnectionCleanupJob).
CREATE INDEX room_connection_expires_at_idx ON room_connection (expires_at);
