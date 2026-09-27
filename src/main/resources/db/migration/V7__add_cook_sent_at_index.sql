-- 행사일 콕 전송은 event_limit_lock을 쥔 채로 그날 전체 콕 수를 센다(CookService.enforceEventWideDailyLimit).
-- sent_at에 인덱스가 없어 이 세기가 cook 표 전체를 읽었다(운영 18,000행 기준 8ms, #139). 잠금을 쥐는 시간이
-- 곧 콕 전송의 초당 처리 한계라서, 그날 범위만 인덱스로 읽게 한다.
CREATE INDEX idx_cook_sent_at ON cook (sent_at);
