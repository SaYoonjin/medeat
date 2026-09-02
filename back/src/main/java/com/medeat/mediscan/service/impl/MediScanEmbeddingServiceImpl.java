package com.medeat.mediscan.service.impl;

import com.medeat.mediscan.service.MediScanEmbeddingService;

import org.springframework.http.HttpEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class MediScanEmbeddingServiceImpl implements MediScanEmbeddingService {

    // ⚠️ final_embedding.json dim과 반드시 맞춰야 함
    private static final int DIM = 768; // ← 네 파일에서 확인해서 수정 가능

    private final String embeddingServerUrl;

    public MediScanEmbeddingServiceImpl(
            @Value("${pill.embed.server-url}") String embeddingServerUrl
    ) {
        this.embeddingServerUrl = embeddingServerUrl;
    }

    @Override
    public float[] createEmbedding(MultipartFile image) {

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", image.getResource());

        HttpEntity<MultiValueMap<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        embeddingServerUrl,
                        request,
                        Map.class
                );

        List<Double> emb = (List<Double>) response.getBody().get("embedding");

        float[] result = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) {
            result[i] = emb.get(i).floatValue();
        }
        return result;
    }

}
