package com.hackerton.menuscan;

import com.hackerton.ai.AiService;
import com.hackerton.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class MenuScanService {
    private final MenuRepository repository;
    private final AiService ai;
    private final MenuCacheWriter writer;
    private final long waitTimeout;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ConcurrentHashMap<String,CompletableFuture<List<MenuEntity>>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public MenuScanService(MenuRepository repository, AiService ai, MenuCacheWriter writer,
                           @Value("${menu.scan.wait-timeout-ms:75000}") long waitTimeout) {
        this.repository=repository;
        this.ai=ai;
        this.writer=writer;
        this.waitTimeout=waitTimeout;
    }

    // 개선: DB 트랜잭션 없이 대기/AI 호출한다. URL별 future 소유권만 원자적으로 확보한다.
    public List<MenuEntity> scanMenu(String rawUrl) {
        if(rawUrl==null || rawUrl.isBlank())
            throw new ApiException(400,"INVALID_URL","메뉴판 URL이 필요합니다.");
        String url=rawUrl.trim(); // 쿼리 문자열, 경로, 대소문자는 보존한다.
        List<MenuEntity> cached=repository.findByRestaurantUrl(url);
        if(!cached.isEmpty()) return cached;

        CompletableFuture<List<MenuEntity>> mine=new CompletableFuture<>();
        CompletableFuture<List<MenuEntity>> shared=inFlight.putIfAbsent(url,mine);
        if(shared==null) {
            shared=mine;
            try { workers.execute(() -> analyze(url,mine)); }
            catch(RejectedExecutionException e) {
                mine.completeExceptionally(new ApiException(503,"MENU_ANALYSIS_UNAVAILABLE","분석 작업을 시작할 수 없습니다."));
                inFlight.remove(url,mine);
            }
        }
        try {
            // get(timeout)은 공유 future를 취소하거나 예외 완료시키지 않는다.
            return shared.get(waitTimeout,TimeUnit.MILLISECONDS);
        } catch(TimeoutException e) {
            throw new ApiException(504,"MENU_WAIT_TIMEOUT","분석 대기 시간이 초과되었습니다. 잠시 후 다시 조회하세요.");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503,"MENU_WAIT_INTERRUPTED","분석 대기가 중단되었습니다.");
        } catch(ExecutionException e) {
            if(e.getCause() instanceof ApiException known) throw known;
            throw new ApiException(502,"MENU_ANALYSIS_FAILED","메뉴판 분석에 실패했습니다.");
        }
    }
    private void analyze(String url,CompletableFuture<List<MenuEntity>> future) {
        try {
            // 소유권 확보 직전 다른 작업이 커밋했을 수 있으므로 캐시를 재확인한다.
            List<MenuEntity> result=repository.findByRestaurantUrl(url);
            if(result.isEmpty()) {
                result=mapper.readValue(ai.parseUrlFromAi(url),new TypeReference<List<MenuEntity>>(){});
                if(result==null || result.isEmpty())
                    throw new ApiException(422,"MENU_ANALYSIS_EMPTY","분석된 메뉴가 없습니다.");
                for(MenuEntity menu:result) {
                    if(menu==null || menu.getMenuName()==null || menu.getMenuName().isBlank()
                            || menu.getPrice()==null || menu.getPrice()<=0)
                        throw new ApiException(502,"INVALID_MENU_ANALYSIS","메뉴 분석 결과의 이름/가격이 올바르지 않습니다.");
                    menu.setMenuId(null);
                    menu.setRestaurantUrl(url);
                    menu.setCachedAt(LocalDateTime.now());
                }
                result=writer.save(result); // 커밋이 완료된 결과만 공유한다.
            }
            future.complete(List.copyOf(result));
        } catch(Throwable e) {
            future.completeExceptionally(e instanceof ApiException ? e
                    : new ApiException(502,"MENU_ANALYSIS_FAILED","메뉴판 분석 또는 저장에 실패했습니다."));
        } finally {
            // 개선: 오래된 작업이 새 작업을 제거하지 못하게 값까지 비교해 삭제한다.
            inFlight.remove(url,future);
        }
    }
    int pendingCount() { return inFlight.size(); }
    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
