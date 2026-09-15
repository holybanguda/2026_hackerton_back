package com.hackerton.menuscan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.hackerton.ai.AiService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MenuCacheCommitTest {
    @Autowired MenuCacheWriter writer;
    @Autowired MenuRepository repository;
    @MockitoBean AiService ai;

    @Test void writerReturnsAfterIndependentTransactionCommits() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        MenuEntity menu=new MenuEntity();
        menu.setRestaurantUrl("commit-test");
        menu.setMenuName("committed");
        menu.setPrice(100);
        writer.save(List.of(menu));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        // 테스트 자체는 트랜잭션이 없으므로 별도 repository 조회에서 커밋된 행이 보여야 한다.
        assertEquals("committed",repository.findByRestaurantUrl("commit-test").getFirst().getMenuName());
    }
}

