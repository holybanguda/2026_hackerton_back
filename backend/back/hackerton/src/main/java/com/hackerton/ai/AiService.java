package com.hackerton.ai;

import com.fasterxml.jackson.databind.*;
import com.hackerton.common.ApiException;
import com.hackerton.recommendation.RecommendationDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
public class AiService {
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public AiService(@Value("${ai.server.url}") String url,
                     @Value("${ai.connect-timeout-ms:5000}") int connectTimeout,
                     @Value("${ai.read-timeout-ms:60000}") int readTimeout) {
        // 개선: 공유 작업의 외부 호출 제한은 각 요청의 대기 timeout과 독립적이다.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public RecommendationDto.Response getAiRecommendation(RecommendationDto.Request request) {
        return recommend("/ai/recommend", HttpMethod.POST, request);
    }
    public RecommendationDto.Response getAiReRecommendation(RecommendationDto.ReRecommendRequest request) {
        return recommend("/ai/re-recommend", HttpMethod.PUT, request);
    }
    private RecommendationDto.Response recommend(String path, HttpMethod method, Object body) {
        try {
            String json = client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)).retrieve().body(String.class);
            return mapper.readValue(json, RecommendationDto.Response.class);
        } catch (RestClientResponseException e) {
            try {
                JsonNode detail = mapper.readTree(e.getResponseBodyAsString()).path("detail");
                String code = detail.isObject() ? detail.path("code").asText() : detail.asText();
                if ("NO_ALTERNATIVE_COMBINATION".equals(code))
                    throw new ApiException(409, code, "동일 조건에서 새로운 메뉴 조합이 없습니다.");
            } catch (ApiException known) { throw known; }
            catch (Exception ignored) { /* 외부 응답 형식과 내부 오류 형식을 분리한다. */ }
            throw new ApiException(502, "AI_REQUEST_FAILED", "AI 추천 요청에 실패했습니다.");
        } catch (Exception e) {
            // 개선: 임의 추천 fallback으로 기존 결과를 덮어쓰지 않는다.
            throw new ApiException(502, "AI_REQUEST_FAILED", "AI 추천 결과를 받아오지 못했습니다.");
        }
    }
    public String parseUrlFromAi(String url) {
        try {
            String json = client.get().uri(b -> b.path("/ai/parse-url").queryParam("url", "{url}").build(url))
                    .retrieve().body(String.class);
            JsonNode menus = mapper.readTree(json).path("menus");
            if (!menus.isArray() || menus.isEmpty())
                throw new ApiException(422, "MENU_ANALYSIS_EMPTY", "메뉴판을 분석하지 못했습니다. 다시 분석하세요.");
            return menus.toString();
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw new ApiException(502, "MENU_ANALYSIS_FAILED", "메뉴판 분석 서버 호출에 실패했습니다.");
        }
    }
}
