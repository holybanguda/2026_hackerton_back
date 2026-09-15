package com.hackerton.recommendation;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String restaurantUrl;

    private Integer peopleCount;

    private Integer budget;

    private String meetingType;

    @Column(length = 1000)
    private String excludedFoods; // JSON string or comma separated

    private Integer bigEaterCount;

    private Integer spicyLevel;

    private Integer dietCount;

    private String todayPreference;

    @Column(length = 2000)
    private String recommendedMenus; // JSON array string or comma separated

    private Integer totalPrice;

    @Column(length = 2000)
    private String reason;

    private String engineType;

    private LocalDateTime createdAt;

    // 개선: 메뉴 가격은 현재 캐시가 아닌 확정 당시 스냅샷에서 복원한다.
    @Column(columnDefinition = "LONGTEXT")
    private String snapshot;

    // 조건/메뉴판별로 과거 조합을 모두 유지하여 조건을 되돌려도 중복을 방지한다.
    @Column(columnDefinition = "LONGTEXT")
    private String combinationHistory;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version;
}
