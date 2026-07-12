package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RagDocumentDao {

    private static final String FIND_ACTIVE_SQL = """
            SELECT
                rag_document_id,
                item_seq,
                drug_name,
                section_type,
                content,
                content_hash,
                document_version,
                source,
                fetched_at,
                lifecycle_status
            FROM rag_document
            WHERE item_seq = :itemSeq
              AND section_type = :sectionType
              AND lifecycle_status = 'ACTIVE'
            ORDER BY document_version DESC
            LIMIT 1
            """;

    private static final String FIND_MAX_VERSION_SQL = """
            SELECT COALESCE(MAX(document_version), 0)
            FROM rag_document
            WHERE item_seq = :itemSeq
              AND section_type = :sectionType
            """;

    private static final String INSERT_DOCUMENT_SQL = """
            INSERT INTO rag_document (
                item_seq,
                drug_name,
                section_type,
                content,
                content_hash,
                document_version,
                source,
                fetched_at,
                lifecycle_status
            )
            VALUES (
                :itemSeq,
                :drugName,
                :sectionType,
                :content,
                :contentHash,
                :documentVersion,
                :source,
                :fetchedAt,
                :lifecycleStatus
            )
            """;

    private static final String INSERT_CHUNK_SQL = """
            INSERT INTO rag_chunk (
                rag_document_id,
                chunk_index,
                content,
                chunk_hash
            )
            VALUES (
                :documentId,
                :chunkIndex,
                :content,
                :chunkHash
            )
            """;

    private static final String INSERT_INDEX_JOB_SQL = """
            INSERT INTO rag_index_job (
                rag_chunk_id,
                job_status,
                attempt_count
            )
            VALUES (
                :chunkId,
                'PENDING',
                0
            )
            ON DUPLICATE KEY UPDATE
                rag_index_job_id = rag_index_job_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RagDocumentDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RagDocument> findActiveDocument(Long itemSeq, DrugInfoSection sectionType) {
        MapSqlParameterSource params = itemSectionParams(itemSeq, sectionType);
        List<RagDocument> documents = jdbcTemplate.query(FIND_ACTIVE_SQL, params, this::toDocument);
        return documents.stream().findFirst();
    }

    public int findMaxDocumentVersion(Long itemSeq, DrugInfoSection sectionType) {
        Integer version = jdbcTemplate.queryForObject(
                FIND_MAX_VERSION_SQL,
                itemSectionParams(itemSeq, sectionType),
                Integer.class
        );
        return version == null ? 0 : version;
    }

    public Long insertDocument(
            Long itemSeq,
            String drugName,
            DrugInfoSection sectionType,
            String content,
            String contentHash,
            int documentVersion,
            String source,
            LocalDateTime fetchedAt,
            RagDocumentLifecycleStatus lifecycleStatus
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("itemSeq", itemSeq)
                .addValue("drugName", drugName)
                .addValue("sectionType", sectionType.name())
                .addValue("content", content)
                .addValue("contentHash", contentHash)
                .addValue("documentVersion", documentVersion)
                .addValue("source", source)
                .addValue("fetchedAt", fetchedAt)
                .addValue("lifecycleStatus", lifecycleStatus.name());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(INSERT_DOCUMENT_SQL, params, keyHolder, new String[] {"rag_document_id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated rag_document_id");
        }
        return key.longValue();
    }

    public Long insertChunk(
            Long documentId,
            int chunkIndex,
            String content,
            String chunkHash
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("chunkIndex", chunkIndex)
                .addValue("content", content)
                .addValue("chunkHash", chunkHash);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(INSERT_CHUNK_SQL, params, keyHolder, new String[] {"rag_chunk_id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated rag_chunk_id");
        }
        return key.longValue();
    }

    public int insertPendingIndexJob(Long chunkId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chunkId", chunkId);
        return jdbcTemplate.update(INSERT_INDEX_JOB_SQL, params);
    }

    private MapSqlParameterSource itemSectionParams(Long itemSeq, DrugInfoSection sectionType) {
        return new MapSqlParameterSource()
                .addValue("itemSeq", itemSeq)
                .addValue("sectionType", sectionType.name());
    }

    private RagDocument toDocument(ResultSet rs, int rowNum) throws SQLException {
        return new RagDocument(
                rs.getLong("rag_document_id"),
                rs.getLong("item_seq"),
                rs.getString("drug_name"),
                DrugInfoSection.valueOf(rs.getString("section_type")),
                rs.getString("content"),
                rs.getString("content_hash"),
                rs.getInt("document_version"),
                rs.getString("source"),
                toLocalDateTime(rs.getTimestamp("fetched_at")),
                RagDocumentLifecycleStatus.valueOf(rs.getString("lifecycle_status"))
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
