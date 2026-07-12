package com.medeat.chatbot.rag;

import com.medeat.medical.dto.DrugInfoDto;
import com.medeat.medical.dto.DrugInfoSection;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class DrugRagDocumentIndexingService {

    static final String MFDS_SOURCE = "MFDS_NEDRUG";

    private final RagDocumentDao ragDocumentDao;
    private final int chunkSize;
    private final int chunkOverlap;
    private final Clock clock;

    public DrugRagDocumentIndexingService(
            RagDocumentDao ragDocumentDao,
            @Value("${medeat.rag.chunk.size:500}") int chunkSize,
            @Value("${medeat.rag.chunk.overlap:50}") int chunkOverlap
    ) {
        this(ragDocumentDao, chunkSize, chunkOverlap, Clock.systemDefaultZone());
    }

    DrugRagDocumentIndexingService(
            RagDocumentDao ragDocumentDao,
            int chunkSize,
            int chunkOverlap,
            Clock clock
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be greater than or equal to 0 and less than chunkSize");
        }
        this.ragDocumentDao = ragDocumentDao;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.clock = clock;
    }

    public RagDocumentIndexingResult prepareIndexing(DrugInfoDto drugInfo) {
        if (drugInfo == null || drugInfo.getItemSeq() == null) {
            return RagDocumentIndexingResult.empty();
        }

        RagDocumentIndexingResult result = RagDocumentIndexingResult.empty();
        for (DrugInfoSection section : DrugInfoSection.values()) {
            result = result.plus(prepareSection(drugInfo, section));
        }
        return result;
    }

    private RagDocumentIndexingResult prepareSection(DrugInfoDto drugInfo, DrugInfoSection section) {
        String content = normalize(section.getValue(drugInfo));
        if (content.isBlank()) {
            return RagDocumentIndexingResult.empty();
        }

        String contentHash = sha256(content);
        Optional<RagDocument> activeDocument = ragDocumentDao.findActiveDocument(
                drugInfo.getItemSeq(),
                section
        );
        if (activeDocument.isPresent() && activeDocument.get().contentHash().equals(contentHash)) {
            return new RagDocumentIndexingResult(1, 1, 0, 0, 0);
        }

        int nextVersion = ragDocumentDao.findMaxDocumentVersion(drugInfo.getItemSeq(), section) + 1;
        Long documentId = ragDocumentDao.insertDocument(
                drugInfo.getItemSeq(),
                drugInfo.getItemName(),
                section,
                content,
                contentHash,
                nextVersion,
                MFDS_SOURCE,
                LocalDateTime.now(clock),
                RagDocumentLifecycleStatus.INDEXING
        );

        int createdChunks = 0;
        int createdJobs = 0;
        for (RagChunk chunk : splitIntoChunks(content)) {
            Long chunkId = ragDocumentDao.insertChunk(
                    documentId,
                    chunk.index(),
                    chunk.content(),
                    chunk.contentHash()
            );
            createdChunks++;
            createdJobs += ragDocumentDao.insertPendingIndexJob(chunkId);
        }

        return new RagDocumentIndexingResult(1, 0, 1, createdChunks, createdJobs);
    }

    List<RagChunk> splitIntoChunks(String rawContent) {
        String content = normalize(rawContent);
        if (content.isBlank()) {
            return List.of();
        }

        List<RagChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + chunkSize);
            String chunkContent = content.substring(start, end).strip();
            if (!chunkContent.isBlank()) {
                chunks.add(new RagChunk(index++, chunkContent, sha256(chunkContent)));
            }
            if (end == content.length()) {
                break;
            }
            start = end - chunkOverlap;
        }
        return chunks;
    }

    String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Jsoup.parse(value).text().replaceAll("\\s+", " ").strip();
    }

    String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
