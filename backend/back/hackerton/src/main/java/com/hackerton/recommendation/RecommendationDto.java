package com.hackerton.recommendation;

import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class RecommendationDto {
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MenuItemDto {
        private String menuName;
        private Integer price;
        private String category;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RecommendedItem {
        private String menuName;
        private Integer price;
        private Integer count;
        private Integer totalItemPrice;
        private String category;
        private String desc;
    }

    @Data @NoArgsConstructor @SuperBuilder
    public static class Conditions {
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

    @Data @EqualsAndHashCode(callSuper=true) @NoArgsConstructor @SuperBuilder
    public static class Request extends Conditions {
        private List<List<String>> excludedCombos;
    }

    @Data @EqualsAndHashCode(callSuper=true) @NoArgsConstructor @SuperBuilder
    public static class ReRecommendRequest extends Request {
        private Integer elapsedMinutes;
        private Integer budgetDelta;

        // 개선: setter 호출 여부로 생략과 null을 구분한다. []와 ""는 명시적인 초기화다.
        @JsonIgnore
        private final Set<String> supplied = new HashSet<>();
        public void setRestaurantUrl(String value) { supplied.add("restaurantUrl"); super.setRestaurantUrl(value); }
        public void setMenuList(List<MenuItemDto> value) { supplied.add("menuList"); super.setMenuList(value); }
        public void setPeopleCount(Integer value) { supplied.add("peopleCount"); super.setPeopleCount(value); }
        public void setBudget(Integer value) { supplied.add("budget"); super.setBudget(value); }
        public void setMeetingType(String value) { supplied.add("meetingType"); super.setMeetingType(value); }
        public void setExcludedFoods(List<String> value) { supplied.add("excludedFoods"); super.setExcludedFoods(value); }
        public void setBigEaterCount(Integer value) { supplied.add("bigEaterCount"); super.setBigEaterCount(value); }
        public void setSpicyLevel(Integer value) { supplied.add("spicyLevel"); super.setSpicyLevel(value); }
        public void setDietCount(Integer value) { supplied.add("dietCount"); super.setDietCount(value); }
        public void setTodayPreference(String value) { supplied.add("todayPreference"); super.setTodayPreference(value); }
    }

    @Data @EqualsAndHashCode(callSuper=true) @NoArgsConstructor @SuperBuilder
    public static class Response extends Conditions {
        private Long recommendationId;
        private List<String> recommendedMenus;
        private List<RecommendedItem> recommendedItems;
        private Integer totalPrice;
        private String reason;
        private String engineType;
    }
}
