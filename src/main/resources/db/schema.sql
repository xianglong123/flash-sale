CREATE TABLE IF NOT EXISTS item (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    item_id          BIGINT      NOT NULL,
    total_stock      INT         NOT NULL,
    available_stock  INT         NOT NULL,
    start_time       DATETIME    NOT NULL,
    end_time         DATETIME    NOT NULL,
    status           VARCHAR(16) NOT NULL,
    version          INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32)   NOT NULL,
    user_id     BIGINT        NOT NULL,
    seckill_id  BIGINT        NOT NULL,
    item_id     BIGINT        NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    created_at  DATETIME      NOT NULL,
    paid_at     DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_seckill (user_id, seckill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reconcile (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    seckill_id  BIGINT      NOT NULL,
    reason      VARCHAR(255) NOT NULL,
    trace_id    VARCHAR(64) NULL,
    retry_count INT         NOT NULL DEFAULT 0,
    resolved    TINYINT     NOT NULL DEFAULT 0,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_seckill (user_id, seckill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
