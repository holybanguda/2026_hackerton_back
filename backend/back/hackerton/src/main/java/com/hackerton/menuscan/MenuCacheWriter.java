package com.hackerton.menuscan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuCacheWriter {
    private final MenuRepository repository;

    // 개선: 별도 빈의 트랜잭션 프록시가 반환한 시점에는 커밋까지 완료되어 있다.
    @Transactional(timeout = 10)
    public List<MenuEntity> save(List<MenuEntity> menus) {
        return repository.saveAll(menus);
    }
}

