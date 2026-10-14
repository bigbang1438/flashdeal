package com.flashdeal.idempotent;

import java.time.LocalDateTime;

/**
 * 幂等令牌记录。
 *
 * @param id          主键
 * @param token       令牌值（客户端生成，全局唯一）
 * @param userId      归属用户（防止令牌被他人复用）
 * @param requestHash 请求体指纹（防止同 token 提交不同参数）
 * @param status      0 处理中 / 1 成功 / 2 失败
 * @param resultJson  成功时缓存的响应体，重复请求直接返回它
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record IdempotentToken(
        Long id,
        String token,
        Long userId,
        String requestHash,
        int status,
        String resultJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    public boolean processing() {
        return status == STATUS_PROCESSING;
    }

    public boolean success() {
        return status == STATUS_SUCCESS;
    }
}
