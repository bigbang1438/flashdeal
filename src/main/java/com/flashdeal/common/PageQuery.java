package com.flashdeal.common;

/**
 * 分页请求参数（带边界保护，防止 size=1000000 拖垮数据库）。
 */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 200;

    public PageQuery {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }

    public int offset() {
        return (page - 1) * size;
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 1 : page, size == null ? 20 : size);
    }
}
