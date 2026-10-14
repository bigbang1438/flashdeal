-- =====================================================================
-- FlashDeal 数据库结构（H2 文件模式，MySQL 兼容语法）
-- 表数量：7
--   1. product          商品
--   2. seckill_activity 秒杀活动（含库存与乐观锁版本号）
--   3. seckill_order    秒杀订单
--   4. stock_log        库存流水（可审计每一次扣减/回补）
--   5. idempotent_token 幂等令牌
--   6. mq_dead_letter   消息队列死信
--   7. mq_consume_log   消息消费幂等日志
-- =====================================================================

DROP TABLE IF EXISTS mq_consume_log;
DROP TABLE IF EXISTS mq_dead_letter;
DROP TABLE IF EXISTS idempotent_token;
DROP TABLE IF EXISTS stock_log;
DROP TABLE IF EXISTS seckill_order;
DROP TABLE IF EXISTS seckill_activity;
DROP TABLE IF EXISTS product;

-- 1. 商品表
CREATE TABLE product (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    price_cents   BIGINT       NOT NULL,
    image_url     VARCHAR(256),
    status        TINYINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 秒杀活动表：stock 为剩余库存，version 为乐观锁版本号
CREATE TABLE seckill_activity (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id     BIGINT       NOT NULL,
    title          VARCHAR(128) NOT NULL,
    seckill_price_cents BIGINT NOT NULL,
    total_stock    INT          NOT NULL,
    stock          INT          NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    start_time     TIMESTAMP    NOT NULL,
    end_time       TIMESTAMP    NOT NULL,
    status         TINYINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_activity_product UNIQUE (product_id, title)
);
CREATE INDEX idx_activity_status ON seckill_activity (status);

-- 3. 秒杀订单表：uk_order_user_activity 保证「同一用户同一活动只成一单」
CREATE TABLE seckill_order (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no       VARCHAR(48)  NOT NULL,
    user_id        BIGINT       NOT NULL,
    activity_id    BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL,
    quantity       INT          NOT NULL DEFAULT 1,
    amount_cents   BIGINT       NOT NULL,
    status         TINYINT      NOT NULL DEFAULT 0,
    idempotent_key VARCHAR(96),
    create_path    VARCHAR(16)  NOT NULL DEFAULT 'DB_CAS',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at        TIMESTAMP,
    expire_at      TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT uk_order_user_activity UNIQUE (user_id, activity_id)
);
CREATE INDEX idx_order_activity ON seckill_order (activity_id);
CREATE INDEX idx_order_status_expire ON seckill_order (status, expire_at);

-- 4. 库存流水表：每一次扣减/回补都有据可查
CREATE TABLE stock_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT      NOT NULL,
    user_id      BIGINT,
    delta        INT         NOT NULL,
    stock_after  INT         NOT NULL,
    reason       VARCHAR(32) NOT NULL,
    order_no     VARCHAR(48),
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_stock_log_activity ON stock_log (activity_id);

-- 5. 幂等令牌表
CREATE TABLE idempotent_token (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    token        VARCHAR(96)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    status       TINYINT      NOT NULL DEFAULT 0,
    result_json  VARCHAR(2048),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_idem_token UNIQUE (token)
);
CREATE INDEX idx_idem_created ON idempotent_token (created_at);

-- 6. 消息队列死信表
CREATE TABLE mq_dead_letter (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic         VARCHAR(64)   NOT NULL,
    msg_key       VARCHAR(64)   NOT NULL,
    payload       VARCHAR(4000) NOT NULL,
    attempt       INT           NOT NULL,
    error_message VARCHAR(1000),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dlq_topic ON mq_dead_letter (topic);

-- 7. 消息消费幂等日志
CREATE TABLE mq_consume_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic        VARCHAR(64)  NOT NULL,
    msg_key      VARCHAR(64)  NOT NULL,
    consumed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_consume UNIQUE (topic, msg_key)
);
