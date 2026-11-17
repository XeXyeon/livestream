-- 로컬 dev DB 전용 시드: 채널 17("게임하는곰")의 소개 글 · 커뮤니티 글 · 댓글 (INSERT/UPDATE만).
-- 실행: docker exec -i <mysql 컨테이너> mysql -uroot -p<루트 비밀번호> livestream --default-character-set=utf8mb4 < infra/seed/channel17-content.sql
-- 다시 실행해도 글이 중복되지 않는다(채널 17에 글이 하나라도 있으면 글 · 댓글은 건너뛴다). 댓글 작성자: member 19 · 20 · 22 · 29.
SET NAMES utf8mb4;

UPDATE channel SET description = '안녕하세요, 게임하는곰입니다. 게임 대신 요즘은 SQL을 방송합니다. 데이터베이스를 처음 만지는 분도 따라올 수 있게 SELECT부터 JOIN까지 천천히, 실습 위주로 진행해요. 평일 저녁에 라이브로 만나고, 지난 방송은 다시보기에서 언제든 복습할 수 있습니다.'
WHERE id = 17;

DROP PROCEDURE IF EXISTS seed_channel17;
DELIMITER //
CREATE PROCEDURE seed_channel17()
BEGIN
  DECLARE p1, p2, p3, p4, p5 BIGINT;
  DECLARE c1, c2, c3 BIGINT;
  IF (SELECT COUNT(*) FROM post WHERE channel_id = 17) = 0 THEN
    INSERT INTO post (created_at, updated_at, channel_id, title, content) VALUES
      (UTC_TIMESTAMP(6) - INTERVAL 6 DAY, UTC_TIMESTAMP(6) - INTERVAL 6 DAY, 17, '채널 소개와 방송 일정 안내',
       '처음 오신 분들 환영합니다!\n\n방송 일정은 이렇게 잡고 있어요.\n- 월 · 수 · 금 저녁 8시: SQL 기초 강의 라이브\n- 토요일 오후 3시: 질문 받는 날\n\n일정이 바뀌면 이 게시판에 먼저 올릴게요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 4 DAY, UTC_TIMESTAMP(6) - INTERVAL 4 DAY, 17, '이번 주 강의 예고: WHERE 와 정렬',
       '이번 주에는 SELECT 다음 단계인 WHERE 조건, ORDER BY 정렬, LIMIT 으로 결과 줄이기를 다룹니다.\n\n실습용 products 표를 그대로 쓰니까 지난 방송 다시보기를 한 번 훑고 오시면 훨씬 편해요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 3 DAY, UTC_TIMESTAMP(6) - INTERVAL 3 DAY, 17, '복습 자료 안내',
       '방송에서 쓴 예제 쿼리를 정리해 두었습니다. 방송이 끝나면 다시보기 설명란 대신 이 게시판에 쿼리 모음을 올릴 예정이에요.\n\n1회차: SELECT 기본 / 2회차: WHERE · 정렬 / 3회차: 집계 함수와 GROUP BY\n\n복습할 때는 쿼리를 눈으로만 보지 말고 직접 쳐 보는 걸 추천합니다.'),
      (UTC_TIMESTAMP(6) - INTERVAL 2 DAY, UTC_TIMESTAMP(6) - INTERVAL 2 DAY, 17, '질문 받습니다 (토요일 질문 방송)',
       '토요일 질문 방송에서 다룰 질문을 댓글로 미리 받아요. 에러 메시지가 나왔다면 쿼리와 메시지를 같이 남겨 주세요. 방송에서 같이 읽으면서 원인을 찾아봅시다.\n\n답하기 어려운 질문은 다음 강의 주제로 가져올게요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 1 DAY, UTC_TIMESTAMP(6) - INTERVAL 1 DAY, 17, '다음 주제 투표: JOIN 먼저? 서브쿼리 먼저?',
       'GROUP BY 까지 끝나면 다음은 두 가지 중에 고민 중입니다.\n1) 테이블 두 개를 합치는 JOIN\n2) 쿼리 안의 쿼리, 서브쿼리\n\n댓글로 1 또는 2 를 남겨 주세요. 많이 나온 쪽부터 갑니다.');
    SET p1 = LAST_INSERT_ID(), p2 = p1 + 1, p3 = p1 + 2, p4 = p1 + 3, p5 = p1 + 4;

    INSERT INTO comment (created_at, updated_at, member_id, post_id, content) VALUES
      (UTC_TIMESTAMP(6) - INTERVAL 5 DAY, UTC_TIMESTAMP(6) - INTERVAL 5 DAY, 20, p1, '평일 저녁 시간이라 퇴근하고 보기 딱 좋네요. 구독하고 갑니다!');
    INSERT INTO comment (created_at, updated_at, member_id, post_id, content) VALUES
      (UTC_TIMESTAMP(6) - INTERVAL 3 DAY, UTC_TIMESTAMP(6) - INTERVAL 3 DAY, 29, p2, 'LIMIT 은 지난번에 살짝 나왔는데 이번에 제대로 다뤄 주시나요?');
    SET c1 = LAST_INSERT_ID();
    INSERT INTO comment (created_at, updated_at, member_id, post_id, parent_id, content) VALUES
      (UTC_TIMESTAMP(6) - INTERVAL 3 DAY + INTERVAL 1 HOUR, UTC_TIMESTAMP(6) - INTERVAL 3 DAY + INTERVAL 1 HOUR, 17, p2, c1, '네, 정렬과 묶어서 "상위 N개 뽑기" 예제로 다룰 예정입니다.');
    INSERT INTO comment (created_at, updated_at, member_id, post_id, content) VALUES
      (UTC_TIMESTAMP(6) - INTERVAL 2 DAY, UTC_TIMESTAMP(6) - INTERVAL 2 DAY, 22, p3, '쿼리 모음 정리해 주시는 거 정말 도움 됩니다. 직접 쳐 보니 훨씬 잘 외워지네요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 30 HOUR, UTC_TIMESTAMP(6) - INTERVAL 30 HOUR, 19, p4, 'GROUP BY 에서 SELECT 에 없는 컬럼을 쓰면 왜 에러가 나는지 궁금해요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 20 HOUR, UTC_TIMESTAMP(6) - INTERVAL 20 HOUR, 20, p5, '2번이요! 서브쿼리가 늘 헷갈렸어요.'),
      (UTC_TIMESTAMP(6) - INTERVAL 18 HOUR, UTC_TIMESTAMP(6) - INTERVAL 18 HOUR, 29, p5, '1번 JOIN 먼저 보고 싶어요.');
  END IF;
END //
DELIMITER ;
CALL seed_channel17();
DROP PROCEDURE seed_channel17;
