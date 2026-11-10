package com.springtechkb.b4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.springtechkb.search.RerankHit;
import com.springtechkb.search.RerankerService;
import com.springtechkb.search.internal.TeiRerankerService;

/**
 * B4 单元测试：TeiRerankerService HTTP 客户端。
 *
 * <p>使用 Spring 7 的 {@link MockRestServiceServer} 拦截 {@link RestClient} 请求，
 * 无需真实 TEI 实例，即可验证：
 * <ol>
 *   <li>向正确的 URL 发送 POST /rerank 请求</li>
 *   <li>响应按得分降序排列</li>
 *   <li>topN 截断正确</li>
 *   <li>空 passages 短路返回空列表</li>
 * </ol>
 */
class TeiRerankerServiceTest {

    private static final String BASE_URL = "http://localhost:8082";

    private RerankerService service;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        // MockRestServiceServer.bindTo(RestClient.Builder) 在 Spring 6.1+ 可用
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new TeiRerankerService(builder.build());
    }

    @Test
    @DisplayName("单次 rerank 请求发送到 POST /rerank，结果按得分降序")
    void rerankReturnsSortedByScore() {
        mockServer
                .expect(requestTo(BASE_URL + "/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        [
                          {"index": 0, "score": 0.3},
                          {"index": 1, "score": 0.92},
                          {"index": 2, "score": 0.55}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<RerankHit> hits = service.rerank("spring MVC", List.of("doc0", "doc1", "doc2"), 3);

        mockServer.verify();
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).originalIndex()).isEqualTo(1);
        assertThat(hits.get(0).score()).isCloseTo(0.92, within(0.001));
        assertThat(hits.get(1).originalIndex()).isEqualTo(2);
        assertThat(hits.get(2).originalIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("topN 截断：请求 3 条结果，topN=2 只返回 2 条")
    void topNTruncatesResults() {
        mockServer
                .expect(requestTo(BASE_URL + "/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        [
                          {"index": 0, "score": 0.8},
                          {"index": 1, "score": 0.6},
                          {"index": 2, "score": 0.4}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<RerankHit> hits = service.rerank("query", List.of("a", "b", "c"), 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    @DisplayName("空 passages 短路，不发起 HTTP 请求")
    void emptyPassagesSkipsHttpCall() {
        List<RerankHit> hits = service.rerank("query", List.of(), 5);

        // mockServer.verify() 会检查没有未匹配的期望 — 此处没有设置任何期望
        assertThat(hits).isEmpty();
    }
}
