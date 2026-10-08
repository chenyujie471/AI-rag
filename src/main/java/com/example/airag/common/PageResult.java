package com.example.airag.common;

import java.util.List;

/**
 * 分页结果。
 */
public record PageResult<T>(long total, int page, int size, List<T> items) {

    public static <T> PageResult<T> of(long total, int page, int size, List<T> items) {
        return new PageResult<>(total, page, size, items);
    }
}
