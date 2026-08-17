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
}
