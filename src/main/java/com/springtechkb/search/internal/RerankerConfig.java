package com.springtechkb.search.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.springtechkb.search.RerankerService;

/**
 * B4：Reranker Bean 配置。
 *
 * <p>{@code springtechkb.reranker.enabled=true}（需 TEI reranker 实例运行）时注册
 * {@link TeiRerankerService}；默认 false 时注册无操作 pass-through，E1 调用链不受影响。
 *
 * <h2>启用方式</h2>
 * <ol>
 *   <li>在 {@code infra/} 目录启动 reranker 服务：
 *       {@code docker compose --profile rerank up -d}</li>
 *   <li>设置 {@code RERANKER_ENABLED=true} 和
 *       {@code RERANKER_BASE_URL=http://localhost:8082}</li>
 * </ol>
 */
@Configuration
class RerankerConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankerConfig.class);

    @Bean
    @ConditionalOnProperty(name = "springtechkb.reranker.enabled", havingValue = "true")
    RerankerService teiRerankerService(
            @Value("${springtechkb.reranker.base-url}") String baseUrl) {
        log.info("Reranker enabled → TEI endpoint: {}", baseUrl);
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        return new TeiRerankerService(restClient);
    }

    /** 默认 pass-through：reranker 未启用时原样返回原始顺序。 */
    @Bean
    @ConditionalOnProperty(
            name = "springtechkb.reranker.enabled",
            havingValue = "false",
            matchIfMissing = true)
    RerankerService noopRerankerService() {
        log.info("Reranker disabled → using no-op pass-through");
        return new PassThroughRerankerService();
    }
}
