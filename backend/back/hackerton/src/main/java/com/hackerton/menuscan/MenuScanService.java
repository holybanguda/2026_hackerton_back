package com.hackerton.menuscan;

import com.hackerton.ai.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor
@Service
public class MenuScanService {

    private final MenuRepository menuRepository;
    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<MenuEntity> scanMenu(String url) {
        List<MenuEntity> savedMenus = menuRepository.findByRestaurantUrl(url);

        if (!savedMenus.isEmpty()) { return savedMenus; }

        try {
            String aiResponseJson = aiService.parseUrlFromAi(url);

            List<MenuEntity> newMenus = objectMapper.readValue(
                        aiResponseJson,
                        new TypeReference<List<MenuEntity>>() {}
            );

            if (newMenus == null || newMenus.isEmpty()) {
                    return Collections.emptyList();
            }

            for (MenuEntity menu : newMenus) {
                menu.setRestaurantUrl(url);
                menu.setCachedAt(LocalDateTime.now());
            }

            menuRepository.saveAll(newMenus);

            return newMenus;

        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}