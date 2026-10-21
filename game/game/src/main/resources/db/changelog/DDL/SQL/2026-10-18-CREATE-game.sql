-- Каталог игр. Строки заводятся DML-миграциями; набор кодов совпадает с enum GameCode (проверяется при старте).
-- Статус публикации позже будет менять админка.
CREATE TABLE game
(
    id             uuid PRIMARY KEY     DEFAULT uuidv7(),
    -- Код игры в путях API и в модуле правил (GameRules.code), например nothing.
    code           varchar(32) NOT NULL UNIQUE,
    title          text        NOT NULL,
    description    text        NOT NULL,
    -- Порядок в каталоге: по возрастанию.
    sort_order     integer     NOT NULL,
    -- PUBLISHED — доступна; HIDDEN — не в каталоге, но доступна по ссылке; NOT_PUBLISHED — в разработке или отключена:
    -- наружу не выдаётся, партии отменяются, комнаты удаляются (может не иметь модуля правил).
    publish_status varchar(32) NOT NULL
        CONSTRAINT game_publish_status_check
            CHECK (publish_status IN ('PUBLISHED', 'HIDDEN', 'NOT_PUBLISHED'))
);
