-- pgvector 扩展（A3 初始化脚本，仅在数据目录首次创建时执行）
-- Flyway 迁移（B1）负责建表和 HNSW 索引 DDL；此处只安装扩展。
CREATE EXTENSION IF NOT EXISTS vector;
