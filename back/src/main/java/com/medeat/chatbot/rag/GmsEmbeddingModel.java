package com.medeat.chatbot.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Component
@ConditionalOnProperty(name = "medeat.rag.enabled", havingValue = "true")
public class GmsEmbeddingModel implements EmbeddingModel {

    private final WebClient gmsWebClient;
    private final String model;

    public GmsEmbeddingModel(
            WebClient gmsWebClient,
            @Value("${gms.embedding-model:text-embedding-3-small}") String model
    ) {
        this.gmsWebClient = gmsWebClient;
        this.model = model;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        GmsEmbeddingResponse response = gmsWebClient.post()
                .uri("/api.openai.com/v1/embeddings")
                .bodyValue(Map.of(
                        "model", model,
                        "input", request.getInstructions()
                ))
                .retrieve()
                .bodyToMono(GmsEmbeddingResponse.class)
                .block();

        if (response == null || response.data() == null) {
            throw new IllegalStateException("Embedding API returned no data");
        }

        List<Embedding> embeddings = response.data().stream()
                .sorted(Comparator.comparingInt(GmsEmbeddingData::index))
                .map(data -> new Embedding(toFloatArray(data.embedding()), data.index()))
                .toList();
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        String content = getEmbeddingContent(document);
        if (content == null) {
            throw new IllegalArgumentException("Document has no embeddable content");
        }
        return embed(content);
    }

    private float[] toFloatArray(List<Double> values) {
        if (values == null) {
            return new float[0];
        }
        float[] result = new float[values.size()];
        IntStream.range(0, values.size())
                .forEach(index -> result[index] = values.get(index).floatValue());
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GmsEmbeddingResponse(List<GmsEmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GmsEmbeddingData(
            @JsonProperty("embedding") List<Double> embedding,
            @JsonProperty("index") int index
    ) {
    }
}
