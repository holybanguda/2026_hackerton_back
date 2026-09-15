-- 개선: 운영 스키마에 아직 없는 경우에만 수동 1회 적용한다.
-- 기존 이름/총액 컬럼은 보존하며 과거 상세 가격/수량을 추정해 backfill하지 않는다.
ALTER TABLE recommendations
    ADD COLUMN snapshot LONGTEXT NULL,
    ADD COLUMN combination_history LONGTEXT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
