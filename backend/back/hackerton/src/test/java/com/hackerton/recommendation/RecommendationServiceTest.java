package com.hackerton.recommendation;

import com.hackerton.ai.AiService;
import com.hackerton.common.ApiException;
import com.hackerton.menuscan.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hackerton.recommendation.RecommendationDto.*;

class RecommendationServiceTest {
    RecommendationRepository repository;
    MenuRepository menus;
    AiService ai;
    RecommendationService service;
    AtomicReference<RecommendationEntity> saved;
    @BeforeEach void setup() {
        repository=mock(RecommendationRepository.class);
        menus=mock(MenuRepository.class);
        ai=mock(AiService.class);
        service=new RecommendationService(repository,menus,ai);
        saved=new AtomicReference<>();
        when(repository.save(any())).thenAnswer(inv -> {
            RecommendationEntity entity=inv.getArgument(0);
            entity.setId(1L); saved.set(entity); return entity;
        });
        when(repository.findById(1L)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        when(menus.findByRestaurantUrl(any())).thenReturn(List.of(menu("A",100),menu("B",200),menu("C",300)));
    }
    MenuEntity menu(String name,int price) {
        MenuEntity m=new MenuEntity(); m.setMenuName(name); m.setPrice(price); m.setCategory("메인"); return m;
    }
    Request request(String url) {
        return Request.builder().restaurantUrl(url).menuList(List.of(
            MenuItemDto.builder().menuName("A").price(100).category("메인").build(),
            MenuItemDto.builder().menuName("B").price(200).category("메인").build(),
            MenuItemDto.builder().menuName("C").price(300).category("메인").build()))
            .peopleCount(2).budget(1000).meetingType("친구").excludedFoods(List.of("새우"))
            .bigEaterCount(1).dietCount(0).spicyLevel(2).todayPreference("고기").build();
    }
    Response result(String name,int price,int count) {
        return Response.builder().recommendedMenus(List.of(name+(count>1 ? " x "+count : "")))
            .recommendedItems(List.of(RecommendedItem.builder().menuName(name).price(price).count(count)
                .totalItemPrice(price*count).category("메인").build()))
            .totalPrice(price*count).reason("stub").engineType("stub").build();
    }
    Response confirmed(String url) {
        when(ai.getAiRecommendation(any())).thenReturn(result("A",100,2));
        return service.confirmRecommendation(service.createRecommendation(request(url)));
    }

    @Test void detailedSnapshotAndEveryConditionSurviveCreateConfirmUpdateAndRead() {
        Response first=confirmed(" https://menu.test/a?q=1 ");
        assertEquals(2,first.getPeopleCount());
        assertEquals(2,first.getRecommendedItems().getFirst().getCount());
        assertEquals(200,first.getTotalPrice());
        when(ai.getAiReRecommendation(any())).thenReturn(result("B",200,1));
        ReRecommendRequest patch=new ReRecommendRequest();
        patch.setExcludedFoods(List.of()); // 빈 목록은 기존 제외 조건 해제
        patch.setTodayPreference("");
        Response updated=service.updateRecommendation(1L,patch);
        assertEquals(List.of(),updated.getExcludedFoods());
        assertEquals("",updated.getTodayPreference());
        assertEquals(2,updated.getPeopleCount());
        assertEquals(1000,updated.getBudget());
        assertEquals("친구",updated.getMeetingType());
        assertEquals(1,updated.getBigEaterCount());
        assertEquals(0,updated.getDietCount());
        assertEquals(2,updated.getSpicyLevel());
        Response restored=service.getRecommendation(1L);
        assertEquals(updated,restored);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(saved.get()));
        assertEquals(List.of(restored),service.getAllRecommendations());
        verify(menus,times(2)).findByRestaurantUrl("https://menu.test/a?q=1");
        // 이력 조회는 현재 메뉴 가격을 참조하지 않는다.
        when(menus.findByRestaurantUrl(any())).thenReturn(List.of(menu("B",999)));
        assertEquals(200,service.getRecommendation(1L).getRecommendedItems().getFirst().getPrice());
    }

    @Test void directMenusReusedAndAllPreviousCombinationsSentWithQuantities() {
        confirmed(null);
        when(ai.getAiReRecommendation(any())).thenReturn(result("B",200,1));
        service.updateRecommendation(1L,new ReRecommendRequest());
        var capture=ArgumentCaptor.forClass(ReRecommendRequest.class);
        verify(ai).getAiReRecommendation(capture.capture());
        assertEquals(List.of(List.of("A","A")),capture.getValue().getExcludedCombos());
        assertEquals(3,capture.getValue().getMenuList().size());
        when(ai.getAiReRecommendation(any())).thenReturn(result("C",300,1));
        service.updateRecommendation(1L,new ReRecommendRequest());
        verify(ai,times(2)).getAiReRecommendation(capture.capture());
        assertEquals(List.of(List.of("A","A"),List.of("B")),capture.getValue().getExcludedCombos());
        verifyNoInteractions(menus);
    }

    @Test void conditionScopeResumesHistoryWhenConditionsAreRestored() {
        confirmed(null);
        when(ai.getAiReRecommendation(any())).thenReturn(result("B",200,1));
        ReRecommendRequest change=new ReRecommendRequest(); change.setBudget(900);
        service.updateRecommendation(1L,change);
        when(ai.getAiReRecommendation(any())).thenReturn(result("C",300,1));
        ReRecommendRequest revert=new ReRecommendRequest(); revert.setBudget(1000);
        service.updateRecommendation(1L,revert);
        var capture=ArgumentCaptor.forClass(ReRecommendRequest.class);
        verify(ai,times(2)).getAiReRecommendation(capture.capture());
        assertEquals(List.of(List.of("A","A")),capture.getValue().getExcludedCombos());
    }

    @Test void noAlternativeOrDuplicatePreservesExistingSnapshot() {
        confirmed(null);
        String before=saved.get().getSnapshot();
        when(ai.getAiReRecommendation(any())).thenThrow(new ApiException(409,"NO_ALTERNATIVE_COMBINATION","none"));
        assertEquals("NO_ALTERNATIVE_COMBINATION",assertThrows(ApiException.class,
            () -> service.updateRecommendation(1L,new ReRecommendRequest())).getCode());
        assertEquals(before,saved.get().getSnapshot());
        doReturn(result("A",100,2)).when(ai).getAiReRecommendation(any());
        assertEquals("NO_ALTERNATIVE_COMBINATION",assertThrows(ApiException.class,
            () -> service.updateRecommendation(1L,new ReRecommendRequest())).getCode());
        verify(repository,times(1)).save(any());
    }

    @Test void missingIdOrMenusGivesExplicitErrorAndDoesNotCallAi() {
        assertEquals("RECOMMENDATION_NOT_FOUND",assertThrows(ApiException.class,
            () -> service.updateRecommendation(2L,new ReRecommendRequest())).getCode());
        confirmed("https://menu.test");
        reset(ai);
        when(menus.findByRestaurantUrl(any())).thenReturn(List.of());
        assertEquals("MENU_REANALYSIS_REQUIRED",assertThrows(ApiException.class,
            () -> service.updateRecommendation(1L,new ReRecommendRequest())).getCode());
        verifyNoInteractions(ai);
    }

    @Test void legacyDataHasNoFabricatedDetails() {
        RecommendationEntity old=RecommendationEntity.builder().id(1L).peopleCount(2).budget(1000)
            .recommendedMenus("[\"old\"]").totalPrice(777).excludedFoods("[]").build();
        saved.set(old);
        Response r=service.getRecommendation(1L);
        assertEquals(List.of(),r.getRecommendedItems());
        assertEquals(List.of("old"),r.getRecommendedMenus());
        assertEquals(777,r.getTotalPrice());
        assertNull(r.getMenuList());
        service.confirmRecommendation(r);
        assertEquals(List.of(),service.getRecommendation(1L).getRecommendedItems());
    }

    @Test void rejectsBadQuantityPriceTotalsAndOverflow() {
        Response response=result("A",100,1);
        response.setBudget(1000);
        response.getRecommendedItems().getFirst().setCount(0);
        assertThrows(ApiException.class,() -> service.confirmRecommendation(response));
        response.getRecommendedItems().getFirst().setCount(1);
        response.setTotalPrice(99);
        assertThrows(ApiException.class,() -> service.confirmRecommendation(response));
        response.getRecommendedItems().getFirst().setPrice(Integer.MAX_VALUE);
        response.getRecommendedItems().getFirst().setCount(2);
        assertThrows(ApiException.class,() -> service.confirmRecommendation(response));
        verify(repository,never()).save(any());
    }

    @Test void reorderedMenusAndResultStillCountAsSameCombination() {
        Response first = result("A",100,1);
        first.setRecommendedItems(List.of(first.getRecommendedItems().getFirst(),
            RecommendedItem.builder().menuName("B").price(200).count(1).totalItemPrice(200).category("메인").build()));
        first.setRecommendedMenus(List.of("A","B"));
        first.setTotalPrice(300);
        when(ai.getAiRecommendation(any())).thenReturn(first);
        service.confirmRecommendation(service.createRecommendation(request(null)));
        Response reordered = result("B",200,1);
        reordered.setRecommendedItems(List.of(first.getRecommendedItems().get(1),first.getRecommendedItems().get(0)));
        reordered.setRecommendedMenus(List.of("B","A"));
        reordered.setTotalPrice(300);
        when(ai.getAiReRecommendation(any())).thenReturn(reordered);
        ReRecommendRequest patch = new ReRecommendRequest();
        patch.setMenuList(request(null).getMenuList().reversed());
        assertEquals("NO_ALTERNATIVE_COMBINATION",assertThrows(ApiException.class,
            () -> service.updateRecommendation(1L,patch)).getCode());
    }

    @Test void legacyFormattedQuantityIsExcludedWithoutInventingResponseDetails() {
        RecommendationEntity old=RecommendationEntity.builder().id(1L).restaurantUrl("https://legacy.test")
            .peopleCount(2).budget(1000).recommendedMenus("[\"A x 2\"]").totalPrice(200).excludedFoods("[]").build();
        saved.set(old);
        when(ai.getAiReRecommendation(any())).thenReturn(result("B",200,1));
        service.updateRecommendation(1L,new ReRecommendRequest());
        var capture=ArgumentCaptor.forClass(ReRecommendRequest.class);
        verify(ai).getAiReRecommendation(capture.capture());
        assertEquals(List.of(List.of("A","A")),capture.getValue().getExcludedCombos());
    }

    @Test void actualSpringJacksonDistinguishesOmittedEmptyAndNull() {
        var mapper=new tools.jackson.databind.ObjectMapper();
        ReRecommendRequest absent=mapper.readValue("{}",ReRecommendRequest.class);
        ReRecommendRequest empty=mapper.readValue("{\"excludedFoods\":[]}",ReRecommendRequest.class);
        ReRecommendRequest nil=mapper.readValue("{\"excludedFoods\":null}",ReRecommendRequest.class);
        assertFalse(absent.getSupplied().contains("excludedFoods"));
        assertTrue(empty.getSupplied().contains("excludedFoods"));
        assertTrue(nil.getSupplied().contains("excludedFoods"));
        confirmed(null);
        assertEquals("INVALID_REQUEST",assertThrows(ApiException.class,
            () -> service.updateRecommendation(1L,nil)).getCode());
    }
}
