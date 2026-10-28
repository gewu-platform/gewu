package com.gewu.common.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL jsonb &harr; String TypeHandler（MyBatis-Plus String 字段直写 jsonb 列）。
 *
 * <p>写入：{@code setObject(i, json, Types.OTHER)} —— pgjdbc 以「类型未知」参数发送，
 * 服务端按目标列的 jsonb 类型解析校验，无需任何 IMPLICIT CAST 兜底。</p>
 *
 * <p>读取：{@code rs.getString} 直接返回 jsonb 的文本表示。</p>
 *
 * <p><b>为什么不用 MP 自带的 JacksonTypeHandler：</b>它会把 String 字段再走一次
 * JSON 序列化，库中落成字符串字面量（{@code "\"[{...}]\""}）——双重编码。</p>
 *
 * <p><b>用法：</b>实体字段加
 * {@code @TableField(typeHandler = JsonbTypeHandler.class)}，同时
 * {@code @TableName(autoResultMap = true)} 保证 MP 内建查询的结果映射生效；
 * 手写 @Select 的自动映射走 StringTypeHandler（rs.getString 对 jsonb 同样成立），互不影响。</p>
 */
@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // Types.OTHER：不指定参数类型，由 PG 服务端按目标列（jsonb）推断并校验
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
