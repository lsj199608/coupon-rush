-- 이벤트 설명 (7단계). 관리자가 직접 쓰거나, LLM 이 만든 후보를 골라 확인·수정한 뒤 이벤트 생성 시 저장한다.
-- nullable: 기존 이벤트와 설명 없이 만드는 이벤트를 그대로 허용. 길이는 LLM 계약(EventCopyCandidate.DESCRIPTION_MAX)과 같다.
-- 빈 문자열은 저장하지 않는다 (앱이 공백만 있으면 NULL 로 바꾸고, DB 도 한 번 더 막는다).
ALTER TABLE coupons ADD COLUMN description VARCHAR(500);

ALTER TABLE coupons ADD CONSTRAINT ck_coupons_description_not_blank
    CHECK (description IS NULL OR length(btrim(description)) > 0);
