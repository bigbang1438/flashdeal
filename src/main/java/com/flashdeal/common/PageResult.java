package com.flashdeal.common;

import java.util.List;

/**
 * 统一分页响应体（与具体持久化框架解耦，只依赖 JDK 集合）。
 *
 * <p>本项目持久层使用 JdbcTemplate + 手写 SQL 分页（{@code LIMIT ? OFFSET ?}），
 * 因此不引入 Spring Data 的 {@code Page} 抽象，保持依赖最小化。</p>
 *
 * @param page       当前页（从 1 开始）
 * @param size       每页条数
 * @param total      总记录数
 * @param totalPages 总页数
 * @param hasNext    是否有下一页
 * @param list       数据列表
 */
public record PageResult<T>(int page, int size, long total, int totalPages, boolean hasNext, List<T> list) {

    /** 由「总数 + 当前页数据」构造。 */
    public static <T> PageResult<T> of(long total, int page, int size, List<T> list) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new PageResult<>(page, size, total, totalPages, (long) page * size < total, list);
    }

    /** 由完整内存列表构造分页体（用于非数据库分页场景，如死信队列查询）。 */
    public static <T> PageResult<T> ofList(List<T> all, int page, int size) {
        int from = Math.min(Math.max(0, (page - 1) * size), all.size());
        int to = Math.min(from + size, all.size());
        return of(all.size(), page, size, all.subList(from, to));
    }
}
