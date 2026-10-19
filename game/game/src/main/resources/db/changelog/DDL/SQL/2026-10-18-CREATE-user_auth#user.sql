-- Реплика user_auth."user" (CDC, событие UserProfileChanged). Имя — "<сервис_источник>#<таблица>", колонки — только из оригинала.
-- Снапшот применяется, только если его version больше сохранённой: повторы и перестановки безопасны.
CREATE TABLE "user_auth#user"
(
    id       uuid PRIMARY KEY,
    nickname varchar(32) NOT NULL,
    avatar   varchar(16) NOT NULL,
    version  bigint      NOT NULL
);
