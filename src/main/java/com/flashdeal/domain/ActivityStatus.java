package com.flashdeal.domain;

/**
 * 活动状态。
 */
public enum ActivityStatus {
    /** 未开始 */
    PENDING(0),
    /** 进行中 */
    RUNNING(1),
    /** 已结束 */
    ENDED(2),
    /** 已下线（人工） */
    OFFLINE(3);

    private final int code;

    ActivityStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ActivityStatus of(int code) {
        for (ActivityStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知活动状态: " + code);
    }
}
