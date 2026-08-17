package com.hackerton.recommendation;


import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;


    //최초 AI 메뉴 추천 API
    // POST /api/recommendation
    @PostMapping
    public ResponseEntity<RecommendationDto.Response> createRecommendation(@RequestBody RecommendationDto.Request request) {
        RecommendationDto.Response response = recommendationService.createRecommendation(request);
        return ResponseEntity.ok(response);
    }

    //'분석 확정' 버튼 클릭 시 히스토리 DB에 저장 API
    // POST /api/recommendation/confirm
    @PostMapping("/confirm")
    public ResponseEntity<RecommendationDto.Response> confirmRecommendation(@RequestBody RecommendationDto.Response responseDto) {
        RecommendationDto.Response response = recommendationService.confirmRecommendation(responseDto);
        return ResponseEntity.ok(response);
    }

     //조건 수정 및 시간 경과 반영 AI 메뉴 재추천 API
     // PUT /api/recommendation/{id}
    @PutMapping("/{id}")
    public ResponseEntity<RecommendationDto.Response> updateRecommendation(
            @PathVariable("id") Long id,
            @RequestBody RecommendationDto.ReRecommendRequest request) {
        RecommendationDto.Response response = recommendationService.updateRecommendation(id, request);
        return ResponseEntity.ok(response);
    }


    //추천 상세 결과 조회 API
    // GET /api/recommendation/{id}
    @GetMapping("/{id}")
    public ResponseEntity<RecommendationDto.Response> getRecommendation(@PathVariable("id") Long id) {
        RecommendationDto.Response response = recommendationService.getRecommendation(id);
        return ResponseEntity.ok(response);
    }


     //히스토리 전체 목록 조회 API
     // GET /api/recommendation
    @GetMapping
    public ResponseEntity<List<RecommendationDto.Response>> getAllRecommendations() {
        List<RecommendationDto.Response> response = recommendationService.getAllRecommendations();
        return ResponseEntity.ok(response);
    }
}
