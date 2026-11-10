package com.springtechkb.a1;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/** A1 测试替身：固定维度，不发网络请求。 */
class StubEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    StubEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return new float[dimensions];
    }

    @Override
    public float[] embed(Document document) {
        return new float[dimensions];
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            out.add(new Embedding(new float[dimensions], i));
        }
        return new EmbeddingResponse(out);
    }
}
