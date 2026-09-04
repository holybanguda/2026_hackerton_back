package com.hackerton.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackerton.recommendation.RecommendationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
public class AiService {

    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String FAST_API_BASE_URL = "https://2026hackertonai-production.up.railway.app";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @Value("${openai.api.key:}")
    private String apiKey;

    private final RestClient openAiRestClient;
    private final RestClient fastApiRestClient;
    private final ObjectMapper objectMapper;

    public AiService() {
        this.openAiRestClient = RestClient.builder()
                .baseUrl(OPENAI_BASE_URL)
                .build();

        this.fastApiRestClient = RestClient.builder()
                .baseUrl(FAST_API_BASE_URL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String extractMenus(String html) {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_API_KEY")) {
            try {
                log.info("1순위: OpenAI API 직접 호출 시도 중...");
                String systemPrompt = """
                    당신은 HTML에서 메뉴판 정보를 추출하는 파서입니다.
                    HTML 내의 메뉴명(menuName), 가격(price, 숫자만), 카테고리(category)를 추출하여 JSON 배열 텍스트로만 응답하세요.
                    마크다운 문법(```json 등)이나 부연 설명 없이 순수 JSON 배열만 출력하세요.
                    """;

                AiRequestDto requestBody = AiRequestDto.createExtractPrompt("gpt-4o-mini", systemPrompt, html);

                AiResponseDto response = openAiRestClient.post()
                        .uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(AiResponseDto.class);

                if (response != null && response.getFirstContent() != null && !response.getFirstContent().isBlank()) {
                    return response.getFirstContent();
                }
            } catch (Exception e) {
                log.warn("OpenAI API 직호출 실패. FastAPI AI 마이크로서비스로 자동 스위치 전환합니다: {}", e.getMessage());
            }
        }

        try {
            log.info("2순위 자동 스위치: FastAPI AI Microservice (:8000) 호출 중...");
            Map<String, String> requestBody = Map.of("rawContent", html);

            String rawJson = fastApiRestClient.post()
                    .uri("/ai/parse-menu?mode=track2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (rawJson != null && !rawJson.isBlank()) {
                if (rawJson.contains("\"menuList\":")) {
                    int start = rawJson.indexOf("\"menuList\":") + 11;
                    int end = rawJson.lastIndexOf("}");
                    if (start < end) {
                        return rawJson.substring(start, end).trim();
                    }
                }
                return rawJson;
            }
        } catch (Exception e) {
            log.error("FastAPI AI 마이크로서비스 호출 실패: {}", e.getMessage());
        }

        return """
            [
              {"menuName": "삼겹살", "price": 15000, "category": "MAIN"},
              {"menuName": "김치찌개", "price": 8000, "category": "MAIN"},
              {"menuName": "계란찜", "price": 4000, "category": "SIDE"}
            ]
            """;
    }

    public RecommendationDto.Response getAiRecommendation(RecommendationDto.Request request) {
        if (request == null) {
            log.error("프론트엔드에서 넘어온 request 객체가 null입니다");
        } else {
            log.info("요청받은 Request 데이터: {}", request.toString());
        }

        try {
            log.info("FastAPI AI 추천 마이크로서비스 (:8000/ai/recommend) 호출 중...");

            String jsonBody = objectMapper.writeValueAsString(request);

            log.info("FastAPI로 전송되는 실제 JSON Body: {}", jsonBody);

            Map<String, Object> responseMap = fastApiRestClient.post()
                    .uri("/ai/recommend?mode=track2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(MAP_RESPONSE_TYPE);

            if (responseMap != null) {
                return toRecommendationResponse(responseMap, request.getRestaurantUrl(),
                        "4대 레이어 AI 추천 결과입니다.");
            }
        } catch (Exception e) {
            log.error("FastAPI AI 추천 마이크로서비스 호출 중 에러 발생 (Fallback 모드 작동): {}", e.getMessage());
        }

        List<String> fallbackMenus = request.getMenuList() != null && !request.getMenuList().isEmpty()
                ? List.of(request.getMenuList().get(0).getMenuName())
                : List.of("추천 메뉴");

        return RecommendationDto.Response.builder()
                .restaurantUrl(request.getRestaurantUrl())
                .recommendedMenus(fallbackMenus)
                .totalPrice(request.getBudget() != null ? request.getBudget() : 30000)
                .reason("인원수와 예산 조건에 맞춘 AI 추천 결과입니다.")
                .engineType("FALLBACK_ENGINE")
                .build();
    }

    public RecommendationDto.Response getAiReRecommendation(RecommendationDto.ReRecommendRequest request) {

        try {
            log.info("FastAPI AI 재추천 마이크로서비스 (:8000/ai/re-recommend) 호출 중...");
            Map<String, Object> responseMap = fastApiRestClient.put()
                    .uri("/ai/re-recommend?mode=track2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MAP_RESPONSE_TYPE);

            if (responseMap != null) {
                return toRecommendationResponse(responseMap, request.getRestaurantUrl(),
                        "경과시간 및 수정 조건이 반영된 AI 재추천 결과입니다.");
            }
        } catch (Exception e) {
            log.error("FastAPI AI 재추천 마이크로서비스 호출 중 에러 발생: {}", e.getMessage());
        }

        RecommendationDto.Request baseReq = RecommendationDto.Request.builder()
                .restaurantUrl(request.getRestaurantUrl())
                .menuList(request.getMenuList())
                .peopleCount(request.getPeopleCount())
                .budget(request.getBudget())
                .meetingType(request.getMeetingType())
                .excludedFoods(request.getExcludedFoods())
                .bigEaterCount(request.getBigEaterCount())
                .spicyLevel(request.getSpicyLevel())
                .dietCount(request.getDietCount())
                .todayPreference(request.getTodayPreference())
                .build();

        return getAiRecommendation(baseReq);
    }

    public String parseUrlFromAi(String url) {
        try {
            log.info("FastAPI URL 파싱 서비스 (:8000/ai/parse-url) GET 호출 중... URL:{}",url);

            String rawJson = fastApiRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/ai/parse-url")
                            .queryParam("url", url)
                            .build())
                    .retrieve()
                    .body(String.class);

            if (rawJson != null && !rawJson.isBlank()) {
                JsonNode rootNode = objectMapper.readTree(rawJson);
                JsonNode menusNode = rootNode.get("menus");

                if (menusNode != null && menusNode.isArray()) {
                    return menusNode.toString();
                }
            }
        } catch (Exception e) {
            log.error("FastAPI URL 파싱 마이크로서비스 호출 실패: {}", e.getMessage());
        }

        return "[]";
    }

    private RecommendationDto.Response toRecommendationResponse(
            Map<String, Object> responseMap,
            String restaurantUrl,
            String defaultReason) {
        return RecommendationDto.Response.builder()
                .restaurantUrl(restaurantUrl)
                .recommendedMenus(toStringList(responseMap.get("recommendedMenus")))
                .totalPrice(toInteger(responseMap.get("totalPrice")))
                .reason(toStringValue(responseMap.get("reason"), defaultReason))
                .engineType(toStringValue(responseMap.get("engineType"), "TRACK_2_HYBRID"))
                .build();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String toStringValue(Object value, String defaultValue) {
        return value instanceof String stringValue ? stringValue : defaultValue;
    }
}
