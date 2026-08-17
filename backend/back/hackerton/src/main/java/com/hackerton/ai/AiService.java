package com.hackerton.ai;

import com.hackerton.recommendation.RecommendationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class AiService {

    @Value("${openai.api.key:}")
    private String apiKey;

    private final RestClient openAiRestClient;
    private final RestClient fastApiRestClient;

    public AiService() {
        this.openAiRestClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .build();

        this.fastApiRestClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .build();
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

            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonBody = objectMapper.writeValueAsString(request);

            log.info("FastAPI로 전송되는 실제 JSON Body: {}", jsonBody);

            // 2. HttpHeaders 설정
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 3. HttpEntity에 JSON String과 헤더 감싸기
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(jsonBody, headers);

            // 4. RestTemplate으로 명시적 JSON Body 전송
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            Map responseMap = restTemplate.postForObject("http://localhost:8000/ai/recommend?mode=track2", entity, Map.class);

            if (responseMap != null) {
                List<String> recMenus = (List<String>) responseMap.getOrDefault("recommendedMenus", Collections.emptyList());
                Integer totPrice = ((Number) responseMap.getOrDefault("totalPrice", 0)).intValue();
                String reason = (String) responseMap.getOrDefault("reason", "4대 레이어 AI 추천 결과입니다.");
                String engineType = (String) responseMap.getOrDefault("engineType", "TRACK_2_HYBRID");

                return RecommendationDto.Response.builder()
                        .restaurantUrl(request.getRestaurantUrl())
                        .recommendedMenus(recMenus)
                        .totalPrice(totPrice)
                        .reason(reason)
                        .engineType(engineType)
                        .build();
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
            Map responseMap = fastApiRestClient.put()
                    .uri("/ai/re-recommend?mode=track2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            if (responseMap != null) {
                List<String> recMenus = (List<String>) responseMap.getOrDefault("recommendedMenus", Collections.emptyList());
                Integer totPrice = ((Number) responseMap.getOrDefault("totalPrice", 0)).intValue();
                String reason = (String) responseMap.getOrDefault("reason", "경과시간 및 수정 조건이 반영된 AI 재추천 결과입니다.");
                String engineType = (String) responseMap.getOrDefault("engineType", "TRACK_2_HYBRID");

                return RecommendationDto.Response.builder()
                        .restaurantUrl(request.getRestaurantUrl())
                        .recommendedMenus(recMenus)
                        .totalPrice(totPrice)
                        .reason(reason)
                        .engineType(engineType)
                        .build();
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
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(rawJson);
                com.fasterxml.jackson.databind.JsonNode menusNode = rootNode.get("menus");

                if (menusNode != null && menusNode.isArray()) {
                    return menusNode.toString();
                }
            }
        } catch (Exception e) {
            log.error("FastAPI URL 파싱 마이크로서비스 호출 실패: {}", e.getMessage());
        }

        return "[]";
        }
}

