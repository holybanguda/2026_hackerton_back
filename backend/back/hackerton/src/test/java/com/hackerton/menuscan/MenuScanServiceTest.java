package com.hackerton.menuscan;

import com.hackerton.ai.AiService;
import com.hackerton.common.ApiException;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuScanServiceTest {
    MenuRepository repository;
    AiService ai;
    MenuCacheWriter writer;
    MenuScanService service;
    ExecutorService clients;
    final String json="[{\"menuName\":\"A\",\"price\":100,\"category\":\"메인\"}]";
    @BeforeEach void setup() {
        repository=mock(MenuRepository.class); ai=mock(AiService.class); writer=mock(MenuCacheWriter.class);
        when(repository.findByRestaurantUrl(any())).thenReturn(List.of());
        when(writer.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service=new MenuScanService(repository,ai,writer,3000);
        clients=Executors.newVirtualThreadPerTaskExecutor();
    }
    @AfterEach void cleanup() { service.close(); clients.shutdownNow(); }
    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3,TimeUnit.SECONDS)); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void waitClean() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(service.pendingCount()!=0 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(0,service.pendingCount());
    }
    @Test void tenRequestsShareOneAnalysisAndOneCommittedSave() throws Exception {
        CountDownLatch allReads=new CountDownLatch(11); // 10 최초 조회 + 소유자의 재확인
        CountDownLatch release=new CountDownLatch(1);
        var cache=new java.util.concurrent.atomic.AtomicReference<List<MenuEntity>>(List.of());
        when(repository.findByRestaurantUrl(any())).thenAnswer(inv -> { allReads.countDown(); return cache.get(); });
        when(writer.save(any())).thenAnswer(inv -> { List<MenuEntity> rows=inv.getArgument(0); cache.set(rows); return rows; });
        when(ai.parseUrlFromAi(any())).thenAnswer(inv -> { await(release); return json; });
        List<Future<List<MenuEntity>>> futures=new ArrayList<>();
        for(int n=0;n<10;n++) futures.add(clients.submit(() -> service.scanMenu(" https://menu.test?a=1 ")));
        await(allReads); release.countDown();
        for(var f:futures) assertEquals("A",f.get(3,TimeUnit.SECONDS).getFirst().getMenuName());
        verify(ai,times(1)).parseUrlFromAi("https://menu.test?a=1");
        verify(writer,times(1)).save(any());
        waitClean();
    }
    @Test void differentUrlsRunIndependently() throws Exception {
        CountDownLatch both=new CountDownLatch(2);
        when(ai.parseUrlFromAi(any())).thenAnswer(inv -> { both.countDown(); await(both); return json; });
        var a=clients.submit(() -> service.scanMenu("https://menu.test?a=1"));
        var b=clients.submit(() -> service.scanMenu("https://menu.test?a=2"));
        assertEquals(1,a.get(3,TimeUnit.SECONDS).size());
        assertEquals(1,b.get(3,TimeUnit.SECONDS).size());
        verify(writer,times(2)).save(any());
    }
    @Test void cachedResultNeverCallsAi() {
        MenuEntity cached=new MenuEntity(); cached.setMenuName("cached");
        when(repository.findByRestaurantUrl(any())).thenReturn(List.of(cached));
        assertSame(cached,service.scanMenu("url").getFirst());
        verifyNoInteractions(ai,writer);
    }
    @Test void doubleCheckCacheSkipsAnalysis() {
        MenuEntity cached=new MenuEntity();
        when(repository.findByRestaurantUrl(any())).thenReturn(List.of(),List.of(cached));
        assertSame(cached,service.scanMenu("url").getFirst());
        verifyNoInteractions(ai,writer);
    }
    @Test void failureIsSharedAndNextRequestRetries() throws Exception {
        CountDownLatch reads=new CountDownLatch(3), release=new CountDownLatch(1);
        when(repository.findByRestaurantUrl(any())).thenAnswer(inv -> { reads.countDown(); return List.of(); });
        when(ai.parseUrlFromAi(any())).thenAnswer(inv -> { await(release); throw new ApiException(502,"MENU_ANALYSIS_FAILED","stub"); });
        var a=clients.submit(() -> service.scanMenu("url"));
        var b=clients.submit(() -> service.scanMenu("url"));
        await(reads); release.countDown();
        for(var f:List.of(a,b)) {
            ExecutionException e=assertThrows(ExecutionException.class,() -> f.get(3,TimeUnit.SECONDS));
            assertEquals("MENU_ANALYSIS_FAILED",((ApiException)e.getCause()).getCode());
        }
        waitClean();
        doReturn(json).when(ai).parseUrlFromAi(any());
        assertEquals(1,service.scanMenu("url").size());
        verify(ai,times(2)).parseUrlFromAi("url");
        verify(writer,times(1)).save(any());
    }
    @Test void oneWaiterTimeoutDoesNotCancelSharedWork() throws Exception {
        service.close(); service=new MenuScanService(repository,ai,writer,600);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.parseUrlFromAi(any())).thenAnswer(inv -> { started.countDown(); await(release); return json; });
        var early=clients.submit(() -> service.scanMenu("url"));
        await(started);
        Thread.sleep(350); // 두 요청의 독립적인 deadline을 벌린다.
        var later=clients.submit(() -> service.scanMenu("url"));
        ExecutionException e=assertThrows(ExecutionException.class,() -> early.get(2,TimeUnit.SECONDS));
        assertEquals("MENU_WAIT_TIMEOUT",((ApiException)e.getCause()).getCode());
        release.countDown();
        assertEquals(1,later.get(2,TimeUnit.SECONDS).size());
        verify(ai,times(1)).parseUrlFromAi("url");
        verify(writer,times(1)).save(any());
    }
    @Test void emptyOrFailedSaveIsNeverPublishedAsSuccess() throws Exception {
        when(ai.parseUrlFromAi(any())).thenReturn("[]");
        assertEquals("MENU_ANALYSIS_EMPTY",assertThrows(ApiException.class,() -> service.scanMenu("url")).getCode());
        verifyNoInteractions(writer); waitClean();
        when(ai.parseUrlFromAi(any())).thenReturn(json);
        when(writer.save(any())).thenThrow(new RuntimeException("commit failed"));
        assertEquals("MENU_ANALYSIS_FAILED",assertThrows(ApiException.class,() -> service.scanMenu("url")).getCode());
        waitClean();
    }
}
