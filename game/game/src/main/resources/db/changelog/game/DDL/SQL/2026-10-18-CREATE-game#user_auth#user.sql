-- Реплика "user_auth#user" (событие модуля UserProfileChanged). Имя — "<модуль-владелец>#<модуль-источник>#<таблица>", колонки — только из оригинала.
-- Снапшот применяется, только если его version больше сохранённой: повторы и перестановки безопасны.
CREATE TABLE "game#user_auth#user"
(
    id       uuid PRIMARY KEY,
    nickname varchar(32) NOT NULL,
    avatar   varchar(16) NOT NULL,
    version  bigint      NOT NULL
);
