package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoSection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private static final String FIND_LATEST_SQL = """
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
              AND lifecycle_status IN ('INDEXING', 'ACTIVE')
            ORDER BY document_version DESC
            LIMIT 1
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

    private static final String FIND_PENDING_VECTOR_CHUNKS_SQL = """
            SELECT
                c.rag_chunk_id,
                c.rag_document_id,
                c.chunk_index,
                c.content,
                d.item_seq,
                d.drug_name,
                d.section_type,
                d.document_version,
                d.source,
                d.fetched_at
            FROM rag_index_job j
            INNER JOIN rag_chunk c
                ON j.rag_chunk_id = c.rag_chunk_id
            INNER JOIN rag_document d
                ON c.rag_document_id = d.rag_document_id
            WHERE j.job_status = 'PENDING'
              AND d.lifecycle_status = 'INDEXING'
              AND c.vector_id IS NULL
            ORDER BY j.rag_index_job_id ASC
            LIMIT :limit
            """;

    private static final String UPDATE_CHUNK_VECTOR_ID_SQL = """
            UPDATE rag_chunk
            SET vector_id = :vectorId
            WHERE rag_chunk_id = :chunkId
            """;

    private static final String MARK_INDEX_JOB_COMPLETED_SQL = """
            UPDATE rag_index_job
            SET job_status = 'COMPLETED',
                completed_at = :completedAt
            WHERE rag_chunk_id = :chunkId
              AND job_status = 'PENDING'
            """;

    private static final String COUNT_INCOMPLETE_JOBS_SQL = """
            SELECT COUNT(*)
            FROM rag_index_job j
            INNER JOIN rag_chunk c
                ON j.rag_chunk_id = c.rag_chunk_id
            WHERE c.rag_document_id = :documentId
              AND j.job_status <> 'COMPLETED'
            """;

    private static final String MARK_PREVIOUS_ACTIVE_OBSOLETE_SQL = """
            UPDATE rag_document previous
            INNER JOIN rag_document current
                ON current.rag_document_id = :documentId
               AND previous.item_seq = current.item_seq
               AND previous.section_type = current.section_type
            SET previous.lifecycle_status = 'OBSOLETE'
            WHERE previous.lifecycle_status = 'ACTIVE'
              AND previous.rag_document_id <> current.rag_document_id
            """;

    private static final String MARK_DOCUMENT_ACTIVE_SQL = """
            UPDATE rag_document
            SET lifecycle_status = 'ACTIVE'
            WHERE rag_document_id = :documentId
              AND lifecycle_status = 'INDEXING'
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

    public Optional<RagDocument> findLatestIndexableDocument(Long itemSeq, DrugInfoSection sectionType) {
        MapSqlParameterSource params = itemSectionParams(itemSeq, sectionType);
        List<RagDocument> documents = jdbcTemplate.query(FIND_LATEST_SQL, params, this::toDocument);
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

    public List<RagVectorChunk> findPendingVectorChunks(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit);
        return jdbcTemplate.query(FIND_PENDING_VECTOR_CHUNKS_SQL, params, this::toVectorChunk);
    }

    public int updateChunkVectorId(Long chunkId, String vectorId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chunkId", chunkId)
                .addValue("vectorId", vectorId);
        return jdbcTemplate.update(UPDATE_CHUNK_VECTOR_ID_SQL, params);
    }

    public int markIndexJobCompleted(Long chunkId, LocalDateTime completedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chunkId", chunkId)
                .addValue("completedAt", completedAt);
        return jdbcTemplate.update(MARK_INDEX_JOB_COMPLETED_SQL, params);
    }

    @Transactional
    public boolean activateDocumentIfReady(Long documentId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentId", documentId);
        Integer incompleteCount = jdbcTemplate.queryForObject(
                COUNT_INCOMPLETE_JOBS_SQL,
                params,
                Integer.class
        );
        if (incompleteCount == null || incompleteCount > 0) {
            return false;
        }

        jdbcTemplate.update(MARK_PREVIOUS_ACTIVE_OBSOLETE_SQL, params);
        return jdbcTemplate.update(MARK_DOCUMENT_ACTIVE_SQL, params) == 1;
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

    private RagVectorChunk toVectorChunk(ResultSet rs, int rowNum) throws SQLException {
        return new RagVectorChunk(
                rs.getLong("rag_chunk_id"),
                rs.getLong("rag_document_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getLong("item_seq"),
                rs.getString("drug_name"),
                DrugInfoSection.valueOf(rs.getString("section_type")),
                rs.getInt("document_version"),
                rs.getString("source"),
                toLocalDateTime(rs.getTimestamp("fetched_at"))
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
