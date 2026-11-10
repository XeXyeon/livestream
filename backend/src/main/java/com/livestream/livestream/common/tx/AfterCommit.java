package com.livestream.livestream.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DB 밖의 부수 효과(외부 호출 · 파일 삭제 · 이벤트)를 커밋 뒤에 실행한다. 롤백되면 실행하지 않는다.
 * 실패는 로그만 남긴다(이미 커밋된 변경을 되돌리지 않는다). 트랜잭션 밖이면 바로 실행한다.
 */
@Slf4j
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void run(String what, Runnable action) {
		Runnable safe = () -> {
			try {
				action.run();
			} catch (RuntimeException e) {
				log.warn("{} 실패(무시): {}", what, e.toString());
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safe.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safe.run();
			}
		});
	}
}
