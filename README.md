# FlashDeal · 高并发秒杀交易系统

Java 21 + Spring Boot 3.3 + Vue 3 前后端分离的秒杀交易系统，覆盖
**库存防超卖、多级缓存、限流、幂等、内存消息队列、分布式锁、可观测**。

> **零外部依赖可跑**：数据库用 H2 文件模式，缓存 / 锁 / 队列全部自研。
> 本机没有 Redis / MySQL / Docker 也能完成全部测试与压测；
> Redis 与 Docker 的接入路径已写好，见「可插拔边界」。
>
> 这是一个**个人工程实践项目**，用于学习和演示，不是实习、商业上线或生产团队使用经历。

---

## 核心数据（全部可复现）

| 指标 | 数值 | 说明 |
| --- | ---: | --- |
| 后端主代码 | 6482 行 | 90 个 Java 文件 |
| 后端测试代码 | 3309 行 | 14 个测试类，**79 个用例全绿** |
| 前端代码 | 1335 行 | Vue 3 + Vite + ECharts |
| REST 端点 | 28 个 | 5 个 Controller |
| 数据表 | 7 张 | product / seckill_activity / seckill_order / stock_log / idempotent_token / mq_dead_letter / mq_consume_log |
| **峰值 QPS** | **2649** | 300 并发线程秒杀场景 |
| **缓存命中率** | **99.33%** | L1 本地缓存，压测实测 |
| **超卖数** | **0** | 300 线程抢 1000 件，`orderSuccess=1000`、`orderStockEmpty=15388` |

压测口径见 `com.flashdeal.bench.LoadTestHarness` 的 Javadoc，
每个数字都能用下面「压测复现」里的命令重新跑出来。

---

## 快速开始

```bash
# 1. 后端（JDK 21 + Maven）
mvn spring-boot:run          # 启动在 http://127.0.0.1:8080

# 2. 前端
cd frontend && npm install && npm run dev    # http://localhost:5173

# 3. 全部测试（79 个用例）
mvn test
```

首次启动会自动初始化 H2 数据库（`data/flashdeal.mv.db`），并执行 `schema.sql` 建表。

---

## 压测复现

自研压测器（只用 JDK 21 的 `HttpClient`，零依赖），带**预热—计时—统计**三阶段，
预热请求不计入统计，避免把 JIT 预热算进结果。

```bash
# 秒杀下单（先建商品与活动，再预热缓存）
java -cp target/classes com.flashdeal.bench.LoadTestHarness \
     --url http://127.0.0.1:8080 --scenario seckill \
     --activity-id 1 --stock 1000 \
     --threads 300 --requests 15000 --warmup 1500

# 高并发读（库存看板，走多级缓存）
java -cp target/classes com.flashdeal.bench.LoadTestHarness \
     --url http://127.0.0.1:8080 --scenario stock-board \
     --threads 200 --requests 20000 --warmup 2000
```

### 实测结果（本机，Windows / JDK 21.0.5）

**秒杀下单**（300 线程 / 15000 请求 / 库存 1000）：

| 指标 | 数值 |
| --- | ---: |
| QPS | 1918 |
| 峰值 QPS（服务端 1 秒窗口） | 2649 |
| P50 | 127 ms |
| P95 | 364 ms |
| P99 | 545 ms |
| P999 | 789 ms |

**高并发读**（200 线程 / 20000 请求）：

| 指标 | 数值 |
| --- | ---: |
| QPS | 2422 |
| P50 | 65 ms |
| P95 | 171 ms |
| P99 | 239 ms |
| 缓存命中率 | 99.33% |

### 业务正确性（这是重点，不是压测数字）

300 个并发线程抢 1000 件库存，服务端指标：

```
orderSuccess        = 1000     ← 恰好卖完
orderStockEmpty     = 15388    ← 其余请求拿到「库存不足」
orderIdempotentReplay = 0
超卖数              = 0        ← 数据库剩余库存与内存一致
```

**不多不少正好 1000 件**。这就是「防超卖」的可验证含义，
不是一句形容词。幂等测试里，同一个 `idempotentToken` 提交 5 次
返回**同一个订单号**，库存只扣一次。

---

## 架构

```
   Vue 3 管理台 / 秒杀页
          │
          ▼
   TraceFilter（traceId）→ 限流（令牌桶 / 滑动窗口，双维度）
          │
          ▼
   SeckillController ──► SeckillService
                            │
            ┌───────────────┼────────────────┐
            ▼               ▼                ▼
      IdempotentService  StockService    InMemoryMessageQueue
        （幂等去重）     （防超卖扣减）    （异步削峰落库）
            │               │                │
            └───────┬───────┴────────────────┘
                    ▼
            MultiLevelCache（L1 LRU + L2 可插拔）
                    │
                    ▼
              H2（文件模式，7 张表）
```

### 库存防超卖：两种策略对比

| 策略 | 实现 | 适用 |
| --- | --- | --- |
| `db-cas` | `UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?` + 影响行数校验 | 数据库兜底，绝对不超卖 |
| `memory` | 内存原子预扣 + 异步批量落库 | 极高并发，最终一致 |

压测用的 `db-cas`：**靠数据库行级原子性保证，不靠应用层判断**，
所以「并发下不超卖」是可以被测试证明的性质，不是愿望。
`MemoryStockOversellTest` 用 1000 线程抢 100 件验证了这一点。

### 多级缓存

- **L1**：自研 `ConcurrentLruCache`（TTL + LRU 淘汰 + 命中率统计），压测命中率 99.33%
- **L2**：`L2Cache` 接口，本机默认 `EmbeddedL2Cache`，另提供 `RedisL2Cache` 实现
- 三大经典问题都有对应处理：穿透（空值缓存）、击穿（互斥回源）、雪崩（TTL 抖动）

### 限流

自研**令牌桶**与**滑动窗口**两种算法，按「接口 + 用户」双维度限流，
触发时返回 `429` 并带 `Retry-After` 头。
`RateLimitIntegrationTest` 用真实 HTTP 请求验证：容量 20 时并发 200 请求，
**必然出现 429，且放行数不超过配额**。

### 内存消息队列

仿环形缓冲，支持批量消费、失败重试、死信队列（`mq_dead_letter`）与消费记录。
压测里 `queueBacklog = 0`，说明削峰消费跟得上生产速度。

### 幂等

`idempotent_token` 表 + 唯一约束 + 状态机。重复提交返回同一订单，
不重复扣库存。这是「用户双击下单按钮」这类真实场景的解法。

### 分布式锁

`DistributedLock` 接口双实现：`local`（`ReentrantLock`，本机默认）与
`redis`（Lua 脚本语义，带看门狗续期）。接口一致，可配置切换。

---

## API 一览

| Controller | 端点数 | 路径前缀 | 职责 |
| --- | ---: | --- | --- |
| SeckillController | 3 | `/api/seckill` | 下单、支付回调、取消回补 |
| AdminController | 9 | `/api/admin` | 商品 / 活动 / 预热 / 状态 |
| DashboardController | 4 | `/api/dashboard` | 库存看板、订单、队列 |
| MetricsController | 5 | `/api/metrics` | 指标、端点延迟、死信 |
| LabController | 7 | `/api/lab` | 缓存 / 锁 / 限流实验端点 |

统一响应体 `ApiResponse<T>`，全局异常处理，业务失败映射到 4xx，
未处理异常映射到 500 且**不泄漏堆栈**，同时打日志含 traceId。

---

## 可插拔边界（诚实说明）

本机没有 Redis / MySQL / Docker，所以这些**接口和实现都写了，但压测跑的是本机实现**：

| 能力 | 本机默认（已压测） | 可插拔实现（已写，未连真服务） |
| --- | --- | --- |
| 缓存 L2 | `EmbeddedL2Cache` | `RedisL2Cache` |
| 分布式锁 | `local`（ReentrantLock） | `redis`（Lua 脚本语义） |
| 数据库 | H2 文件模式 | MySQL 只需换驱动与 URL |
| 容器化 | — | `Dockerfile` / `docker-compose.yml` 已提供 |

**没有假装连过 Redis**。简历上写的是「自研 + 可插拔适配」，不是「生产 Redis 集群」。

---

## 已知边界（不夸大）

- **单机架构**。不是分布式部署，没有真正的跨节点锁与缓存一致性验证。
- **压测数据是本机数据**。Windows / JDK 21 / H2 文件库，换机器数字会不同。
- **内存预扣策略**在进程崩溃时可能丢失未落库的扣减，适合最终一致场景。
- **没有接入真实支付**。支付回调是模拟第三方平台的本地端点。
- **没有做多副本**。`/api/metrics` 是进程内统计，多副本下各自独立。
- **没有用户体系**。`X-User-Id` 由调用方声明，用于限流与幂等维度，不做认证。

## License

MIT
