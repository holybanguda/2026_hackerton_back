package com.hackerton.menuscan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@Entity
@Table(
        name = "menu",
        indexes = {
                @Index(name = "idx_restaurant_url", columnList = "restaurantUrl")
        }
)

public class MenuEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long menuId;

    @Column(length = 500)
    private String restaurantUrl;

    private LocalDateTime cachedAt;

    private String menuName;

    private Integer price;

    private String category;

}
