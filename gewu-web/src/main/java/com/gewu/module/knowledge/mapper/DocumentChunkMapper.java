package com.gewu.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.knowledge.dto.KnowledgeSearchResult;
import com.gewu.module.knowledge.entity.DocumentChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档分块 Mapper（对齐 java-ai-native DocumentChunkMapper）
 *
 * embedding 字段在 SQL 内显式 CAST(... AS vector)，绕过 BaseMapper 默认按 varchar
 * 发送导致的类型不匹配。传入的 embedding 必须是 pgvector 合法文本，如 "[0.1,-0.2,...]"
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Select("SELECT * FROM document_chunk WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<DocumentChunk> selectByDocumentId(@Param("documentId") Long documentId);

    /**
     * 自定义插入：embedding 显式 CAST AS vector
     */
    @Insert("INSERT INTO document_chunk " +
            "  (document_id, chunk_index, content, embedding, dimension, create_time, update_time) " +
            "VALUES (" +
            "  #{documentId}, #{chunkIndex}, #{content}, CAST(#{embedding} AS vector), " +
            "  #{dimension}, " +
            "  COALESCE(#{createTime}, CURRENT_TIMESTAMP), " +
            "  COALESCE(#{updateTime}, CURRENT_TIMESTAMP)" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertChunk(DocumentChunk chunk);

    /**
     * 删除指定文档的全部分块
     */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 基于 pgvector 的余弦相似度检索（对齐 native searchSimilar）
     *
     * {@code <=>} 是 pgvector 余弦距离操作符，值域 [0, 2]；相似度 = 1 - 距离。
     * 已过滤软删除文档（kd.deleted = 0）。
     *
     * @param queryVec  查询向量的 pgvector 文本形式，如 "[0.1,-0.2,...]"
     * @param threshold 最低相似度阈值
     * @param topK      返回前 K 条
     */
    @Select("SELECT dc.id AS chunk_id, " +
            "       dc.document_id AS document_id, " +
            "       dc.chunk_index AS chunk_index, " +
            "       dc.content AS content, " +
            "       kd.file_name AS document_name, " +
            "       1 - (dc.embedding <=> CAST(#{queryVec} AS vector)) AS similarity " +
            "FROM document_chunk dc " +
            "JOIN knowledge_document kd ON kd.id = dc.document_id " +
            "WHERE kd.deleted = 0 " +
            "  AND (1 - (dc.embedding <=> CAST(#{queryVec} AS vector))) >= #{threshold} " +
            "ORDER BY dc.embedding <=> CAST(#{queryVec} AS vector) ASC " +
            "LIMIT #{topK}")
    List<KnowledgeSearchResult> searchSimilar(@Param("queryVec") String queryVec,
                                              @Param("threshold") double threshold,
                                              @Param("topK") int topK);

    /**
     * P8b：限定单文档的相似度检索（编排层 RAG 注入用）
     * 与 searchSimilar 同构，增加 document_id 精确过滤
     */
    @Select("SELECT dc.id AS chunk_id, " +
            "       dc.document_id AS document_id, " +
            "       dc.chunk_index AS chunk_index, " +
            "       dc.content AS content, " +
            "       kd.file_name AS document_name, " +
            "       1 - (dc.embedding <=> CAST(#{queryVec} AS vector)) AS similarity " +
            "FROM document_chunk dc " +
            "JOIN knowledge_document kd ON kd.id = dc.document_id " +
            "WHERE kd.deleted = 0 " +
            "  AND dc.document_id = #{documentId} " +
            "  AND (1 - (dc.embedding <=> CAST(#{queryVec} AS vector))) >= #{threshold} " +
            "ORDER BY dc.embedding <=> CAST(#{queryVec} AS vector) ASC " +
            "LIMIT #{topK}")
    List<KnowledgeSearchResult> searchSimilarInDocument(@Param("queryVec") String queryVec,
                                                        @Param("documentId") Long documentId,
                                                        @Param("threshold") double threshold,
                                                        @Param("topK") int topK);
}
