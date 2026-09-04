package com.hackerton.recommendation;

import lombok.*;

import java.util.List;

public class RecommendationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MenuItemDto {
        private String menuName;
        private Integer price;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request {
        private String restaurantUrl;
        private List<MenuItemDto> menuList;
        private Integer peopleCount;
        private Integer budget;
        private String meetingType;
        private List<String> excludedFoods;
        private Integer bigEaterCount;
        private Integer spicyLevel;
        private Integer dietCount;
        private String todayPreference;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReRecommendRequest {
        private String restaurantUrl;
        private List<MenuItemDto> menuList;
        private Integer peopleCount;
        private Integer budget;
        private String meetingType;
        private List<String> excludedFoods;
        private Integer bigEaterCount;
        private Integer spicyLevel;
        private Integer dietCount;
        private String todayPreference;
        private Integer elapsedMinutes;
        private Integer budgetDelta;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        //다른 응답dto 부분
        private Integer peopleCount;
        private Integer budget;
        private String meetingType;
        private List<String> excludedFoods;
        private Integer bigEaterCount;
        private Integer spicyLevel;
        private Integer dietCount;
        private String todayPreference;

        //ai 추천 결과 부분
        private Long recommendationId;
        private String restaurantUrl;
        List<String> recommendedMenus;
        private Integer totalPrice;
        private String reason;
        private String engineType;
    }
}
