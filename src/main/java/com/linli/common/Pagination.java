package com.linli.common;

/**
 * 分页参数收敛：页码从 1 起、每页条数取默认值或上限。
 *
 * 同一段 {@code page < 1 ? 1 : page} + {@code pageSize < 1 ? DEFAULT : Math.min(...)}
 * 原来在 8 个地方各写一遍。收敛成一处有两个理由：
 * ① 上限这件事是**接口契约**的一部分（"最多 50 条"），散在八个类里迟早出现"只有领券那处忘了加"；
 * ② 深翻页的防护要在这里改一次，而不是八个类各改各的。
 *
 * 注意这里只管"归一"，不管"哪一页存在不存在"——越界翻到空页是正常返回空列表，
 * 由 {@link PageResult} 带着 total 让前端自己决定要不要显示"加载更多"。
 */
public record Pagination(int page, int size) {

    /**
     * @param page        客户端要的页码（可能为 0、负数）
     * @param size        客户端要的每页条数（可能为 0、负数、或大到想拖库）
     * @param defaultSize 没传或传非法值时的条数
     * @param maxSize     条数上限，超过就压到上限而不是报错：多要点是兼容问题，不是攻击
     */
    public static Pagination of(int page, int size, int defaultSize, int maxSize) {
        return new Pagination(Math.max(page, 1), size < 1 ? defaultSize : Math.min(size, maxSize));
    }

    /** MySQL 的 {@code limit offset, size} 里那个偏移量。 */
    public int offset() {
        return (page - 1) * size;
    }
}
