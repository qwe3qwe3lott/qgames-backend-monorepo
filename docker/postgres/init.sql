-- Database-per-service: у каждого сервиса своя БД и свой пользователь, доступа к чужим БД нет.
-- Локально все БД живут в одном контейнере; в проде их можно разнести по разным серверам.

-- REPLICATION: Debezium читает WAL user_auth под владельцем БД (публикацию qgames_outbox создаёт миграция user-auth).
CREATE ROLE user_auth LOGIN REPLICATION PASSWORD 'user_auth';
CREATE DATABASE user_auth OWNER user_auth;

CREATE ROLE game LOGIN PASSWORD 'game';
CREATE DATABASE game OWNER game;

REVOKE CONNECT ON DATABASE user_auth, game FROM PUBLIC;
