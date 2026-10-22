-- P8a: varchar -> jsonb IMPLICIT CAST（MyBatis-Plus String 字段写 jsonb 列；幂等可重跑）
-- 注意：函数参数必须用 TEXT 而非 VARCHAR —— 若用 VARCHAR，函数体内 v::jsonb 会命中
-- 本 CAST 自身造成无限递归（栈溢出）；varchar→text 二进制兼容，cast 函数允许 TEXT 形参
DROP CAST IF EXISTS (VARCHAR AS jsonb);
DROP FUNCTION IF EXISTS varchar_to_jsonb(VARCHAR);
+
CREATE OR REPLACE FUNCTION varchar_to_jsonb(v TEXT) RETURNS jsonb AS $fn$
  SELECT v::jsonb
$fn$ LANGUAGE SQL IMMUTABLE STRICT;

CREATE CAST (VARCHAR AS jsonb) WITH FUNCTION varchar_to_jsonb(v TEXT) AS IMPLICIT;

SELECT 'varchar->jsonb cast ready (text param, no recursion)' AS status;
