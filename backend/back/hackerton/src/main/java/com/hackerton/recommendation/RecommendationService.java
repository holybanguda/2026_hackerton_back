package com.hackerton.recommendation;

import com.hackerton.ai.AiService;
import com.hackerton.menuscan.MenuEntity;
import com.hackerton.menuscan.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RecommendationService {

    private final RecommendationRepository recommendationRepository;
    private final MenuRepository menuRepository;
    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();


    //최초 메뉴 추천 요청 결과 조회
    public RecommendationDto.Response createRecommendation(RecommendationDto.Request request) {
        log.info("최초 메뉴 추천 서비스 실행: 식당={}", request.getRestaurantUrl());

        if (request.getRestaurantUrl() != null && !request.getRestaurantUrl().isBlank()) {

            List<MenuEntity> dbMenus = menuRepository.findByRestaurantUrl(request.getRestaurantUrl());

            List<RecommendationDto.MenuItemDto> menuDtoList = dbMenus.stream()
                    .map(entity -> RecommendationDto.MenuItemDto.builder()
                            .menuName(entity.getMenuName())
                            .price(entity.getPrice())
                            .build())
                    .toList();

            request.setMenuList(menuDtoList);
        }

        RecommendationDto.Response aiResponse = aiService.getAiRecommendation(request);

        aiResponse.setRestaurantUrl(request.getRestaurantUrl());
        aiResponse.setBudget(request.getBudget());
        aiResponse.setMeetingType(request.getMeetingType());
        aiResponse.setExcludedFoods(request.getExcludedFoods());
        aiResponse.setBigEaterCount(request.getBigEaterCount());
        aiResponse.setSpicyLevel(request.getSpicyLevel());
        aiResponse.setDietCount(request.getDietCount());
        aiResponse.setTodayPreference(request.getTodayPreference());

        return aiResponse;
    }


    //최초 메뉴 추천 요청 히스토리 DB 저장
    public RecommendationDto.Response confirmRecommendation(RecommendationDto.Response responseDto) {
        log.info("추천 결과 확정 저장 실행: 식당={}", responseDto.getRestaurantUrl());

        String excludedFoodsJson = toJsonString(responseDto.getExcludedFoods());
        String recommendedMenusJson = toJsonString(responseDto.getRecommendedMenus());

        RecommendationEntity entity = RecommendationEntity.builder()
                .restaurantUrl(responseDto.getRestaurantUrl())
                .peopleCount(responseDto.getPeopleCount())
                .budget(responseDto.getBudget())
                .meetingType(responseDto.getMeetingType())
                .excludedFoods(excludedFoodsJson)
                .bigEaterCount(responseDto.getBigEaterCount())
                .spicyLevel(responseDto.getSpicyLevel())
                .dietCount(responseDto.getDietCount())
                .todayPreference(responseDto.getTodayPreference())
                .recommendedMenus(recommendedMenusJson)
                .totalPrice(responseDto.getTotalPrice())
                .reason(responseDto.getReason())
                .engineType(responseDto.getEngineType())
                .createdAt(LocalDateTime.now())
                .build();

        RecommendationEntity savedEntity = recommendationRepository.save(entity);

        responseDto.setRecommendationId(savedEntity.getId());

        return responseDto;
    }


    //조건 수정 및 경과 시간 반영 재추천 및 DB 업데이트
    public RecommendationDto.Response updateRecommendation(Long recommendationId, RecommendationDto.ReRecommendRequest request) {

        RecommendationEntity existing = recommendationRepository.findById(recommendationId)
                .orElse(null);

        if (existing != null) {
            if (existing.getCreatedAt() != null && (request.getElapsedMinutes() == null || request.getElapsedMinutes() == 0)) {
                long elapsedMin = java.time.Duration.between(existing.getCreatedAt(), LocalDateTime.now()).toMinutes();
                request.setElapsedMinutes((int) elapsedMin);
            }

            if (existing.getBudget() != null && request.getBudget() != null) {
                int delta = request.getBudget() - existing.getBudget();
                request.setBudgetDelta(delta);
            }
        }

        log.info("메뉴 재추천 서비스 실행: ID={}, 경과시간={}분, 예산변동={}원",
                recommendationId, request.getElapsedMinutes(), request.getBudgetDelta());

        RecommendationDto.Response aiResponse = aiService.getAiReRecommendation(request);

        String excludedFoodsJson = toJsonString(request.getExcludedFoods());
        String recommendedMenusJson = toJsonString(aiResponse.getRecommendedMenus());

        if (existing != null) {
            existing.setPeopleCount(request.getPeopleCount());
            existing.setBudget(request.getBudget());
            existing.setMeetingType(request.getMeetingType());
            existing.setExcludedFoods(excludedFoodsJson);
            existing.setBigEaterCount(request.getBigEaterCount());
            existing.setSpicyLevel(request.getSpicyLevel());
            existing.setDietCount(request.getDietCount());
            existing.setTodayPreference(request.getTodayPreference());
            existing.setRecommendedMenus(recommendedMenusJson);
            existing.setTotalPrice(aiResponse.getTotalPrice());
            existing.setReason(aiResponse.getReason());
            existing.setEngineType(aiResponse.getEngineType());

            recommendationRepository.save(existing);
            aiResponse.setRecommendationId(existing.getId());
        } else {
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
            return createRecommendation(baseReq);
        }

        return aiResponse;
    }


    //추천 내역 상세 조회
    @Transactional(readOnly = true)
    public RecommendationDto.Response getRecommendation(Long recommendationId) {
        RecommendationEntity entity = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 추천 ID입니다: " + recommendationId));

        List<String> menus = fromJsonStringList(entity.getRecommendedMenus());

        return RecommendationDto.Response.builder()
                .recommendationId(entity.getId())
                .restaurantUrl(entity.getRestaurantUrl())
                .peopleCount(entity.getPeopleCount())
                .budget(entity.getBudget())
                .meetingType(entity.getMeetingType())
                .excludedFoods(fromJsonStringList(entity.getExcludedFoods()))
                .bigEaterCount(entity.getBigEaterCount())
                .spicyLevel(entity.getSpicyLevel())
                .dietCount(entity.getDietCount())
                .todayPreference(entity.getTodayPreference())
                .recommendedMenus(menus)
                .totalPrice(entity.getTotalPrice())
                .reason(entity.getReason())
                .engineType(entity.getEngineType())
                .build();
    }

    private String toJsonString(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJsonStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Arrays.asList(json.replace("[", "").replace("]", "").replace("\"", "").split(","));
        }
    }


    //히스토리 전체 목록 조회
    @Transactional(readOnly=true)
    public List<RecommendationDto.Response> getAllRecommendations() {
        List<RecommendationEntity> recommendations = recommendationRepository.findAllByOrderByCreatedAtDesc();

        return recommendations.stream()
                .map(entity -> RecommendationDto.Response.builder()
                        .recommendationId(entity.getId())
                        .restaurantUrl(entity.getRestaurantUrl())
                        .peopleCount(entity.getPeopleCount())
                        .budget(entity.getBudget())
                        .meetingType(entity.getMeetingType())
                        .excludedFoods(fromJsonStringList(entity.getExcludedFoods()))
                        .bigEaterCount(entity.getBigEaterCount())
                        .spicyLevel(entity.getSpicyLevel())
                        .dietCount(entity.getDietCount())
                        .todayPreference(entity.getTodayPreference())
                        .recommendedMenus(fromJsonStringList(entity.getRecommendedMenus()))
                        .totalPrice(entity.getTotalPrice())
                        .reason(entity.getReason())
                        .engineType(entity.getEngineType())
                        .build())
                .toList();
    }
}
