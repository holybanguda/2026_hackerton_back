package com.hackerton.menuscan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MenuRepository extends JpaRepository<MenuEntity, Long> {

    List<MenuEntity> findByRestaurantUrl(String restaurantUrl);

}