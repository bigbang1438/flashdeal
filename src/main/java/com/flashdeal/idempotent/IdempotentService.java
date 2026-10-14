package com.flashdeal.idempotent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flashdeal.repository.IdempotentTokenRepository;

/**
 * 幂等服务：基于「唯一索引抢占 + 结果缓存」实现接口级幂等。
 *
 * <h2>状态机</h2>
 * <pre>
 *            INSERT 成功                 markSuccess
 *   (无记录) ──────────► PROCESSING ──────────────► SUCCESS(缓存响应)
 *                          │  │
 *              业务失败    │  │ 进程崩溃 / 超时
 *                          ▼  ▼
 *                       FAILED(可重试)
 * </pre>
 *
 * <h2>并发重复提交怎么处理</h2>
 * <p>两个线程同时带同一个 token 进来：{@code INSERT} 依赖唯一索引 {@code uk_idem_token}，
 * 只有一个成功（ACQUIRED），另一个拿到 DuplicateKey → 读到 status=0（PROCESSING）
 * → 返回 {@link IdempotentOutcome#PROCESSING}，接口返回 409 让客户端重试，
 * <b>绝不会重复扣库存</b>。这是本项目「幂等性」测试要断言的核心行为。</p>
 *
 * <h2>请求指纹</h2>
 * <p>同一个 token 被用来提交不同参数（例如换了 activityId）时，{@code request_hash}
 * 不一致 → 返回 CONFLICT。防止客户端复用 token 造成「A 的响应返回给了 B」。</p>
 */
@Service
public class IdempotentService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentService.class);

    private final IdempotentTokenRepository repository;
    private final long processingTimeoutMillis;

    private final LongAdder acquiredCount = new LongAdder();
    private final LongAdder replayCount = new LongAdder();
    private final LongAdder conflictCount = new LongAdder();
    private final LongAdder processingCount = new LongAdder();

    public IdempotentService(IdempotentTokenRepository repository,
                             @Value("${flashdeal.idempotent.processing-timeout-millis:30000}")
                             long processingTimeoutMillis) {
        this.repository = repository;
        this.processingTimeoutMillis = processingTimeoutMillis;
    }

    /**
     * 尝试获取执行权。
     *
     * @param token       幂等令牌
     * @param userId      用户 ID
     * @param requestHash 请求指纹（由调用方计算）
     */
    public IdempotentResult begin(String token, long userId, String requestHash) {
        if (repository.tryAcquire(token, userId, requestHash)) {
            acquiredCount.increment();
            return new IdempotentResult(IdempotentOutcome.ACQUIRED, null, null);
        }

        Optional<IdempotentToken> found = repository.findByToken(token);
        if (found.isEmpty()) {
            // 极小概率：令牌刚被清理任务删除，重新抢占
            if (repository.tryAcquire(token, userId, requestHash)) {
                acquiredCount.increment();
                return new IdempotentResult(IdempotentOutcome.ACQUIRED, null, null);
            }
            conflictCount.increment();
            return new IdempotentResult(IdempotentOutcome.CONFLICT, null, "令牌状态异常");
        }

        IdempotentToken record = found.get();
        if (!record.requestHash().equals(requestHash)) {
            conflictCount.increment();
            return new IdempotentResult(IdempotentOutcome.CONFLICT, null, "同一令牌提交了不同参数");
        }

        if (record.success()) {
            replayCount.increment();
            return new IdempotentResult(IdempotentOutcome.REPLAY, record.resultJson(), null);
        }

        if (record.processing()) {
            // 处理中：可能是并发重复提交，也可能是上次进程崩溃留下的僵尸令牌
            LocalDateTime createdAt = record.createdAt();
            boolean stale = createdAt != null
                    && createdAt.plusNanos(processingTimeoutMillis * 1_000_000L).isBefore(LocalDateTime.now());
            if (stale && repository.tryReacquire(token)) {
                // 状态从「处理中」CAS 回「处理中」并刷新时间戳，只有抢到的线程能继续
                log.warn("幂等令牌处理超时，重置后允许重试 token={}", token);
                acquiredCount.increment();
                return new IdempotentResult(IdempotentOutcome.ACQUIRED, null, null);
            }
            processingCount.increment();
            return new IdempotentResult(IdempotentOutcome.PROCESSING, null, "相同请求正在处理中");
        }

        // FAILED：允许重试。用状态 CAS（2 -> 0）保证只有一个线程能接管
        if (repository.tryReacquire(token)) {
            acquiredCount.increment();
            return new IdempotentResult(IdempotentOutcome.ACQUIRED, null, null);
        }
        processingCount.increment();
        return new IdempotentResult(IdempotentOutcome.PROCESSING, null, "相同请求正在处理中");
    }

    /** 标记成功并缓存响应体（下次重复请求直接返回它）。 */
    public void complete(String token, String resultJson) {
        repository.markSuccess(token, resultJson);
    }

    /** 标记失败，允许同 token 重试。 */
    public void fail(String token) {
        repository.markFailed(token);
    }

    /** 清理历史令牌。 */
    public int cleanup(int retentionHours) {
        return repository.deleteExpired(LocalDateTime.now().minusHours(retentionHours));
    }

    /** 幂等统计快照。 */
    public IdempotentStats stats() {
        return new IdempotentStats(acquiredCount.sum(), replayCount.sum(),
                conflictCount.sum(), processingCount.sum(), repository.count());
    }

    /**
     * 幂等判定结果。
     *
     * @param outcome     判定结论
     * @param cachedResult 命中重放时的缓存响应体（JSON）
     * @param message     附加说明
     */
    public record IdempotentResult(IdempotentOutcome outcome, String cachedResult, String message) {
    }
}
