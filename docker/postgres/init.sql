-- Роль и БД приложения: одна БД qgames, владелец — роль qgames без прав суперпользователя.
-- Скрипт выполняет одноразовый сервис postgres-init на КАЖДОМ `docker compose up`, поэтому всё здесь идемпотентно.
-- Пароль приходит переменной psql (`-v qgames_password=…`) из postgres-init.

SELECT 'CREATE ROLE qgames' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'qgames')\gexec
ALTER ROLE qgames LOGIN PASSWORD :'qgames_password';
SELECT 'CREATE DATABASE qgames OWNER qgames' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'qgames')\gexec

REVOKE CONNECT ON DATABASE qgames FROM PUBLIC;
