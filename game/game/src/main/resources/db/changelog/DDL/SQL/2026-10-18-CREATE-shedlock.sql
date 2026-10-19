-- Блокировки ShedLock: запланированная задача выполняется одним инстансом сервиса.
CREATE TABLE shedlock
(
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
