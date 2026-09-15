package com.hackerton.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.hackerton.ai.AiService;
import com.hackerton.common.ApiException;
import com.hackerton.menuscan.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import static com.hackerton.recommendation.RecommendationDto.*;

@Service
@RequiredArgsConstructor
public class RecommendationService {
    private final RecommendationRepository repository;
    private final MenuRepository menus;
    private final AiService ai;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public Response createRecommendation(Request request) {
        defaults(request);
        resolveMenus(request);
        validateConditions(request);
        Response response = assemble(request, ai.getAiRecommendation(request));
        validateResult(response, true);
        return response;
    }

    public Response confirmRecommendation(Response response) {
        // 개선: 구버전의 이름/총액만 있는 확정도 허용하되 상세 가격/수량을 추정하지 않는다.
        defaults(response);
        validateConditions(response);
        if (response.getMenuList() != null) validateMenus(response.getMenuList());
        validateResult(response, false);
        RecommendationEntity entity = new RecommendationEntity();
        entity.setCreatedAt(LocalDateTime.now());
        Map<String,List<List<String>>> history = new LinkedHashMap<>();
        if (response.getMenuList() != null && !response.getMenuList().isEmpty())
            history.put(scope(response), new ArrayList<>(List.of(combo(response))));
        entity.setCombinationHistory(json(history));
        write(entity, response);
        RecommendationEntity saved = repository.save(entity);
        response.setRecommendationId(saved.getId());
        return response;
    }

    public Response updateRecommendation(Long id, ReRecommendRequest patch) {
        RecommendationEntity entity = require(id);
        Response old = toResponse(entity);
        ReRecommendRequest request = mapper.convertValue(old, ReRecommendRequest.class);
        // 개선: 생략은 보존, []/""는 초기화. null은 모호한 초기화로 해석하지 않고 400으로 거부한다.
        JsonNode values = mapper.valueToTree(patch);
        var merged = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(request);
        for (String field : patch.getSupplied()) {
            if (values.get(field) == null || values.get(field).isNull())
                throw error(400, "INVALID_REQUEST", field + "는 null 대신 빈 목록/문자열 또는 유효한 값을 보내세요.");
            merged.set(field, values.get(field));
        }
        request = mapper.convertValue(merged, ReRecommendRequest.class);
        defaults(request);
        resolveMenus(request);
        validateConditions(request);
        request.setElapsedMinutes(patch.getElapsedMinutes());
        if (request.getElapsedMinutes() == null && entity.getCreatedAt()!=null) {
            request.setElapsedMinutes((int)Math.min(Integer.MAX_VALUE,
                Math.max(0, java.time.Duration.between(entity.getCreatedAt(), LocalDateTime.now()).toMinutes())));
        }
        request.setBudgetDelta(old.getBudget() == null ? 0 : request.getBudget() - old.getBudget());

        Map<String,List<List<String>>> history = readHistory(entity.getCombinationHistory());
        String key = scope(request);
        List<List<String>> previous = new ArrayList<>(history.getOrDefault(key, List.of()));
        // 구버전 이력은 메뉴 목록이 없을 수 있다. 같은 저장 URL이면 현재 캐시를 비교 기준으로 사용한다.
        // 이력 응답의 상세 가격/수량 자체를 복원하거나 덮어쓰지는 않는다.
        if (old.getMenuList() == null && Objects.equals(old.getRestaurantUrl(), request.getRestaurantUrl())) {
            old.setMenuList(request.getMenuList());
            defaults(old);
        }
        // 기존 데이터는 기록이 없을 수 있다. 동일한 스냅샷 조건인 경우 현재 결과부터 기록한다.
        if (old.getMenuList() != null && key.equals(scope(old)) && !previous.contains(combo(old)))
            previous.add(combo(old));
        request.setExcludedCombos(List.copyOf(previous));
        Response result = assemble(request, ai.getAiReRecommendation(request));
        validateResult(result, true);
        List<String> combination = combo(result);
        if (previous.contains(combination))
            throw error(409, "NO_ALTERNATIVE_COMBINATION", "이미 제시한 조합입니다.");
        previous.add(combination);
        history.put(key, previous);

        // 개선: AI 호출/검증 성공 전에는 엔티티를 수정하지 않는다. @Version으로 동시 덮어쓰기도 방지한다.
        result.setRecommendationId(id);
        write(entity, result);
        entity.setCombinationHistory(json(history));
        repository.save(entity);
        return result;
    }

    public Response getRecommendation(Long id) { return toResponse(require(id)); }
    public List<Response> getAllRecommendations() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }
    private RecommendationEntity require(Long id) {
        return repository.findById(id).orElseThrow(() -> error(404,"RECOMMENDATION_NOT_FOUND","추천 이력이 없습니다."));
    }

    private void resolveMenus(Conditions request) {
        String url = request.getRestaurantUrl();
        if (url != null && !url.isBlank()) {
            request.setRestaurantUrl(url.trim()); // 쿼리 파라미터는 메뉴판 식별자일 수 있으므로 보존한다.
            request.setMenuList(menus.findByRestaurantUrl(url.trim()).stream()
                .map(m -> MenuItemDto.builder().menuName(m.getMenuName()).price(m.getPrice()).category(m.getCategory()).build()).toList());
        }
        if (request.getMenuList() == null || request.getMenuList().isEmpty())
            throw error(422,"MENU_REANALYSIS_REQUIRED","메뉴판을 다시 분석하거나 직접 메뉴 목록을 전달하세요.");
        validateMenus(request.getMenuList());
    }

    private void validateMenus(List<MenuItemDto> list) {
        Set<String> names = new HashSet<>();
        for (MenuItemDto m : list) {
            if (m == null || m.getMenuName() == null || m.getMenuName().isBlank() || m.getPrice() == null || m.getPrice() <= 0
                    || !names.add(m.getMenuName()))
                throw error(400,"INVALID_MENU","메뉴명은 고유해야 하며 가격은 양수 정수여야 합니다.");
        }
    }

    private void defaults(Conditions c) {
        if (c.getRestaurantUrl()!=null) c.setRestaurantUrl(c.getRestaurantUrl().trim().isEmpty() ? null : c.getRestaurantUrl().trim());
        if (c.getPeopleCount()==null) c.setPeopleCount(2);
        if (c.getBudget()==null) c.setBudget(60000);
        if (c.getMeetingType()==null) c.setMeetingType("친구 모임");
        if (c.getExcludedFoods()==null) c.setExcludedFoods(List.of());
        if (c.getBigEaterCount()==null) c.setBigEaterCount(0);
        if (c.getDietCount()==null) c.setDietCount(0);
        if (c.getSpicyLevel()==null) c.setSpicyLevel(3);
        if (c.getTodayPreference()==null) c.setTodayPreference("");
    }

    private void validateConditions(Conditions c) {
        if (c.getPeopleCount()<1 || c.getBudget()<1 || c.getBigEaterCount()<0 || c.getDietCount()<0
                || c.getBigEaterCount()>c.getPeopleCount() || c.getDietCount()>c.getPeopleCount()
                || c.getSpicyLevel()<0 || c.getSpicyLevel()>5
                || c.getExcludedFoods().stream().anyMatch(s -> s==null || s.isBlank()))
            throw error(400,"INVALID_CONDITIONS","인원, 예산, 제외 음식 및 매운맛 조건을 확인하세요.");
    }

    private Response assemble(Conditions c, Response ai) {
        if (ai == null) throw error(502,"INVALID_AI_RESULT","AI 응답이 없습니다.");
        // 개선: 최초/재추천에서 동일한 조건 매핑을 사용한다. AI가 조건을 생략해도 요청값이 보존된다.
        Response out = mapper.convertValue(c, Response.class);
        out.setRecommendedItems(ai.getRecommendedItems());
        out.setRecommendedMenus(ai.getRecommendedMenus());
        out.setTotalPrice(ai.getTotalPrice());
        out.setReason(ai.getReason());
        out.setEngineType(ai.getEngineType());
        return out;
    }

    private void validateResult(Response r, boolean requireDetails) {
        List<RecommendedItem> items = r.getRecommendedItems();
        if (items == null || items.isEmpty()) {
            if (requireDetails) throw error(502,"INVALID_AI_RESULT","AI 상세 항목이 누락됐습니다.");
            if (r.getRecommendedMenus()==null || r.getRecommendedMenus().isEmpty()
                    || r.getRecommendedMenus().stream().anyMatch(s -> s==null || s.isBlank())
                    || r.getTotalPrice()==null || r.getTotalPrice()<0)
                throw error(400,"INVALID_RESULT","기존 추천 메뉴와 총액을 확인하세요.");
            r.setRecommendedItems(List.of());
            return;
        }
        long total = 0;
        Set<String> names = new HashSet<>();
        List<String> formatted = new ArrayList<>();
        for (RecommendedItem item : items) {
            if (item==null || item.getMenuName()==null || item.getMenuName().isBlank()
                    || !names.add(item.getMenuName()) || item.getPrice()==null || item.getPrice()<=0
                    || item.getCount()==null || item.getCount()<=0 || item.getCount()>10000
                    || item.getTotalItemPrice()==null
                    || (long)item.getPrice()*item.getCount()!=item.getTotalItemPrice())
                throw error(requireDetails?502:400,"INVALID_RESULT","항목의 단가, 수량 및 합계가 일치하지 않습니다.");
            total += item.getTotalItemPrice();
            formatted.add(item.getMenuName() + (item.getCount()>1 ? " x " + item.getCount() : ""));
            if (r.getMenuList()!=null && !r.getMenuList().isEmpty()) {
                MenuItemDto original = r.getMenuList().stream().filter(m -> m.getMenuName().equals(item.getMenuName())).findFirst()
                    .orElseThrow(() -> error(400,"INVALID_RESULT","메뉴판에 없는 추천 항목입니다."));
                if (!original.getPrice().equals(item.getPrice()))
                    throw error(400,"INVALID_RESULT","메뉴판 단가와 추천 단가가 다릅니다.");
            }
        }
        if (total>Integer.MAX_VALUE || r.getTotalPrice()==null || total!=r.getTotalPrice() || total>r.getBudget())
            throw error(requireDetails?502:400,"INVALID_RESULT","항목 합계와 총액/예산이 일치하지 않습니다.");
        if (r.getRecommendedMenus()!=null && !r.getRecommendedMenus().equals(formatted))
            throw error(requireDetails?502:400,"INVALID_RESULT","메뉴명 목록과 상세 항목이 일치하지 않습니다.");
        r.setRecommendedMenus(formatted);
    }

    private List<String> combo(Response r) {
        // Python combinations의 수량 표현에 맞춰 이름을 count번 반복하고 정렬한다.
        List<String> result = new ArrayList<>();
        if (r.getRecommendedItems()!=null && !r.getRecommendedItems().isEmpty()) {
            for (RecommendedItem item : r.getRecommendedItems())
                for (int n=0;n<item.getCount();n++) result.add(item.getMenuName());
        } else if (r.getRecommendedMenus()!=null) {
            Set<String> known = new HashSet<>();
            if (r.getMenuList()!=null) r.getMenuList().forEach(m -> known.add(m.getMenuName()));
            for (String name : r.getRecommendedMenus()) {
                // 구버전의 "이름 x N"은 실제 메뉴명과 혼동하지 않을 때만 내부 비교용으로 해석한다.
                // 응답 상세 항목이나 가격을 추정해서 채우는 처리는 아니다.
                var formatted = java.util.regex.Pattern.compile("^(.*) x ([1-9][0-9]{0,3})$").matcher(name);
                if (!known.contains(name) && formatted.matches() && known.contains(formatted.group(1))) {
                    for (int n=0;n<Integer.parseInt(formatted.group(2));n++) result.add(formatted.group(1));
                } else result.add(name);
            }
        }
        Collections.sort(result);
        return result;
    }

    private String scope(Conditions c) {
        // 프로필/경과시간은 범위에서 제외한다. 메뉴 순서 및 제외 음식 순서는 의미가 없다.
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(mapper.convertValue(c, Conditions.class));
        tree.set("menuList", mapper.valueToTree(c.getMenuList().stream().sorted(Comparator.comparing(MenuItemDto::getMenuName)).toList()));
        tree.set("excludedFoods", mapper.valueToTree(new TreeSet<>(c.getExcludedFoods())));
        return tree.toString();
    }
    private Map<String,List<List<String>>> readHistory(String s) {
        if (s==null || s.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(s, new TypeReference<LinkedHashMap<String,List<List<String>>>>(){}); }
        catch (Exception e) { throw error(500,"CORRUPT_HISTORY","조합 기록을 읽을 수 없습니다."); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw error(500,"SERIALIZATION_FAILED","추천 정보를 저장할 수 없습니다."); }
    }

    private void write(RecommendationEntity e, Response r) {
        e.setSnapshot(json(r));
        e.setRestaurantUrl(r.getRestaurantUrl());
        e.setPeopleCount(r.getPeopleCount());
        e.setBudget(r.getBudget());
        e.setMeetingType(r.getMeetingType());
        e.setBigEaterCount(r.getBigEaterCount());
        e.setDietCount(r.getDietCount());
        e.setSpicyLevel(r.getSpicyLevel());
        e.setTodayPreference(r.getTodayPreference());
        e.setTotalPrice(r.getTotalPrice());
        e.setReason(r.getReason());
        e.setEngineType(r.getEngineType());
        e.setExcludedFoods(json(r.getExcludedFoods()));
        e.setRecommendedMenus(json(r.getRecommendedMenus()));
    }
    private Response toResponse(RecommendationEntity e) {
        Response r;
        if (e.getSnapshot()!=null) {
            try { r=mapper.readValue(e.getSnapshot(), Response.class); }
            catch (Exception ex) { throw error(500,"CORRUPT_HISTORY","추천 스냅샷을 읽을 수 없습니다."); }
        } else {
            // 개선: 기존 행에는 상세정보가 없으므로 빈 목록으로 반환하고 당시 이름/총액을 그대로 보존한다.
            r = new Response();
            r.setRestaurantUrl(e.getRestaurantUrl());
            r.setPeopleCount(e.getPeopleCount());
            r.setBudget(e.getBudget());
            r.setMeetingType(e.getMeetingType());
            r.setBigEaterCount(e.getBigEaterCount());
            r.setDietCount(e.getDietCount());
            r.setSpicyLevel(e.getSpicyLevel());
            r.setTodayPreference(e.getTodayPreference());
            r.setTotalPrice(e.getTotalPrice());
            r.setReason(e.getReason());
            r.setEngineType(e.getEngineType());
            r.setExcludedFoods(legacyList(e.getExcludedFoods()));
            r.setRecommendedMenus(legacyList(e.getRecommendedMenus()));
            r.setRecommendedItems(List.of());
        }
        r.setRecommendationId(e.getId());
        return r;
    }
    private List<String> legacyList(String s) {
        if(s==null || s.isBlank()) return List.of();
        try { return mapper.readValue(s, new TypeReference<List<String>>(){}); }
        catch(Exception e) { return Arrays.asList(s.replace("[","").replace("]","").replace("\"","").split(",")); }
    }
    private ApiException error(int status,String code,String message) { return new ApiException(status,code,message); }
}
