-- Реестр публикаций событий модулей Spring Modulith (схема v2, как schema-postgresql.sql spring-modulith-events-jdbc).
-- Служебная таблица фреймворка: имя задаёт Spring Modulith (настраивается только схема), поэтому без префикса модуля.
-- Публикация пишется в транзакции события и удаляется после успешной доставки слушателю (completion-mode: delete);
-- оставшиеся строки — недоставленные события, они переотправляются при старте.
CREATE TABLE event_publication
(
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
