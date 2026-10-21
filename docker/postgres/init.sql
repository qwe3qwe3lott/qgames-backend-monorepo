-- Database-per-service: у каждого сервиса своя БД и свой пользователь, доступа к чужим БД нет.
-- Локально все БД живут в одном контейнере; в проде их можно разнести по разным серверам.
--
-- Скрипт выполняет одноразовый сервис postgres-init на КАЖДОМ `docker compose up`, поэтому всё здесь идемпотентно:
-- новый сервис получает роль и БД на уже существующем томе без `down -v`.
-- Пароли приходят переменными psql (`-v <роль>_password=…`) из postgres-init.
-- Новый сервис с БД: блок здесь, его пароль в postgres-init и `depends_on: postgres-init` у сервиса.

-- REPLICATION: Debezium читает WAL user_auth под владельцем БД (публикацию qgames_outbox создаёт миграция user-auth).
SELECT 'CREATE ROLE user_auth' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'user_auth')\gexec
ALTER ROLE user_auth LOGIN REPLICATION PASSWORD :'user_auth_password';
SELECT 'CREATE DATABASE user_auth OWNER user_auth' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'user_auth')\gexec

SELECT 'CREATE ROLE game' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'game')\gexec
ALTER ROLE game LOGIN PASSWORD :'game_password';
SELECT 'CREATE DATABASE game OWNER game' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'game')\gexec

-- Администраторы админ-панели. CDC нет, поэтому REPLICATION не нужен.
SELECT 'CREATE ROLE admin_auth' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'admin_auth')\gexec
ALTER ROLE admin_auth LOGIN PASSWORD :'admin_auth_password';
SELECT 'CREATE DATABASE admin_auth OWNER admin_auth' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'admin_auth')\gexec

REVOKE CONNECT ON DATABASE user_auth, game, admin_auth FROM PUBLIC;
