package com.gewu.module.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.prompt.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Prompt 模板 Mapper（对齐 java-ai-native PromptTemplateMapper）
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    /**
     * 查询模板所有版本（按名称，未删除，按版本倒序）
     */
    default List<PromptTemplate> selectVersionsByName(@Param("name") String name) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getName, name)
                .eq(PromptTemplate::getIsDeleted, 0)
                .orderByDesc(PromptTemplate::getVersion));
    }

    /**
     * 获取模板最新版本（未删除）
     */
    default PromptTemplate selectLatestVersion(@Param("name") String name) {
        List<PromptTemplate> list = selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getName, name)
                .eq(PromptTemplate::getIsDeleted, 0)
                .orderByDesc(PromptTemplate::getVersion)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 获取模板指定版本（未删除）
     */
    default PromptTemplate selectByVersion(@Param("name") String name, @Param("version") Integer version) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getName, name)
                .eq(PromptTemplate::getVersion, version)
                .eq(PromptTemplate::getIsDeleted, 0)
                .last("LIMIT 1"));
    }
}
