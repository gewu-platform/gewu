package com.example.lc4j.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应结果（对齐 java-ai-native 分页形态）
 *
 * @param <T> 数据类型
 * @author Matilda
 * @since 2026-10-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 数据列表
     */
    private List<T> records;

    /**
     * 总条数
     */
    private Long total;

    /**
     * 当前页码（1 开始）
     */
    private Integer page;

    /**
     * 每页大小
     */
    private Integer size;

    /**
     * 总页数
     */
    private Integer pages;

    /**
     * 构建分页结果
     */
    public static <T> PageResult<T> of(List<T> records, Long total, Integer page, Integer size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size > 0 ? (int) Math.ceil((double) total / size) : 0);
        return result;
    }
}
