package com.springtechkb.search.internal;

import com.springtechkb.search.RerankHit;
import com.springtechkb.search.RerankerService;

import java.util.ArrayList;
import java.util.List;

class PassThroughRerankerService implements RerankerService {

    @Override
    public List<RerankHit> rerank(String query, List<String> passages, int topN) {
        List<RerankHit> hits = new ArrayList<>();
        int limit = Math.min(topN, passages.size());
        for (int i = 0; i < limit; i++) {
            hits.add(new RerankHit(i, 1.0));
        }
        return List.copyOf(hits);
    }
}
