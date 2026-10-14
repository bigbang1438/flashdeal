package com.flashdeal.idempotent;

/**
 * 幂等判定的四种结果。
 */
public enum IdempotentOutcome {
    /** 首次请求，可以继续执行业务。 */
    ACQUIRED,
    /** 重复请求且上次已成功：直接返回缓存的响应体，不再执行业务。 */
    REPLAY,
    /** 重复请求但上次失败了：允许重新执行（令牌被重置）。 */
    RETRY_ALLOWED,
    /** 同 token 携带了不同的请求参数：属于客户端错误，直接拒绝。 */
    CONFLICT,
    /** 首次请求还在处理中（并发重复提交）：提示稍后重试，不重复扣库存。 */
    PROCESSING
}
