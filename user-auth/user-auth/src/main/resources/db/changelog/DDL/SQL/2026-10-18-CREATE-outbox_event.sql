-- Transactional Outbox (libs/common-cdc-source). Debezium читает INSERT из WAL через публикацию qgames_outbox
-- и отправляет payload в топик outbox.event.<aggregate_type> с ключом aggregate_id. Строки сразу удаляются.
CREATE TABLE outbox_event
(
    id             uuid PRIMARY KEY      DEFAULT uuidv7(),
    aggregate_type varchar(255) NOT NULL,
    aggregate_id   varchar(255) NOT NULL,
    event_type     varchar(255) NOT NULL,
    -- Protobuf-сообщение из *-internal-async-api.
    payload        bytea        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE PUBLICATION qgames_outbox FOR TABLE outbox_event;
