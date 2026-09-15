package com.hackerton.recommendation;

import com.hackerton.ai.AiService;
import com.hackerton.menuscan.MenuRepository;
import com.hackerton.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hackerton.recommendation.RecommendationDto.*;

@SpringBootTest
class RecommendationPersistenceTest {
    @Autowired RecommendationService service;
    @Autowired RecommendationRepository repository;
    @Autowired tools.jackson.databind.json.JsonMapper jsonMapper;
    @MockitoBean AiService ai;

    @BeforeEach void clear() { repository.deleteAll(); }
    @Test void httpMapperRejectsFractionalQuantity() {
        assertThrows(Exception.class, () -> jsonMapper.readValue(
            "{\"recommendedItems\":[{\"count\":1.5}]}", Response.class));
    }
    Response answer(String name,int price) {
        return Response.builder().recommendedMenus(List.of(name)).totalPrice(price).reason("stub").engineType("stub")
            .recommendedItems(List.of(RecommendedItem.builder().menuName(name).price(price).count(1)
                .totalItemPrice(price).category("메인").build())).build();
    }
    @Test void committedSnapshotIsRestoredAndFailedRerecommendDoesNotChangeDatabase() {
        Request request=Request.builder().peopleCount(1).budget(1000).menuList(List.of(
            MenuItemDto.builder().menuName("A").price(100).category("메인").build(),
            MenuItemDto.builder().menuName("B").price(200).category("메인").build())).build();
        when(ai.getAiRecommendation(any())).thenReturn(answer("A",100));
        Response response=service.confirmRecommendation(service.createRecommendation(request));
        long id=response.getRecommendationId();
        assertEquals(response,service.getRecommendation(id));
        when(ai.getAiReRecommendation(any())).thenReturn(answer("B",200));
        Response updated=service.updateRecommendation(id,new ReRecommendRequest());
        assertEquals(updated,service.getRecommendation(id));
        String before=repository.findById(id).orElseThrow().getCombinationHistory();
        when(ai.getAiReRecommendation(any())).thenThrow(new ApiException(409,"NO_ALTERNATIVE_COMBINATION","none"));
        assertThrows(ApiException.class,() -> service.updateRecommendation(id,new ReRecommendRequest()));
        assertEquals(updated,service.getRecommendation(id));
        assertEquals(before,repository.findById(id).orElseThrow().getCombinationHistory());
        assertTrue(repository.findById(id).orElseThrow().getVersion()>0);
    }
}
