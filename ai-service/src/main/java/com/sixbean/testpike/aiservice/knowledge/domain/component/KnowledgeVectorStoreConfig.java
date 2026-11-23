package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;


@Configuration
public class KnowledgeVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStoreConfig.class);

    
    public static final String SCHEMA_NAME = "knowledge";
    public static final String TABLE_NAME = "tp_knowledge_chunk";

    @Bean
    public PgVectorStore knowledgeVectorStore(JdbcTemplate jdbcTemplate,
                                              EmbeddingModel embeddingModel,
                                              EmbeddingProperties embeddingProperties) {
        int dimension = embeddingProperties.getDimension();
        assertEmbeddingColumnDimension(jdbcTemplate, SCHEMA_NAME, TABLE_NAME, dimension);
        log.info("知识库向量存储装配 schema={} table={} dimension={}",
                SCHEMA_NAME, TABLE_NAME, dimension);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName(SCHEMA_NAME)
                .vectorTableName(TABLE_NAME)
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(dimension)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
    }

    
    static void assertEmbeddingColumnDimension(JdbcTemplate jdbcTemplate,
                                               String schemaName, String tableName,
                                               int expectedDimension) {
        Integer actual = null;
        try {
            actual = jdbcTemplate.queryForObject(
                    "select atttypmod from pg_attribute"
                            + " where attrelid = cast(? as regclass) and attname = 'embedding'",
                    Integer.class, schemaName + "." + tableName);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException(
                    "知识库向量表不存在：" + schemaName + "." + tableName
                            + "，请先执行 V64 迁移（db/migration/V64__knowledge_vector_base.sql）", e);
        }
        if (actual == null || actual != expectedDimension) {
            throw new IllegalStateException(
                    "embedding 模型维度与向量表列不一致：期望 " + expectedDimension
                            + "（llm.embedding.dimension），实际 vector(" + actual + ")——"
                            + "换 embedding 模型需重建向量表（" + schemaName + "." + tableName
                            + "）：向量可随源数据重建（PRD 6.6），重建后重跑入库管线（T177 全量重建入口）");
        }
    }
}
