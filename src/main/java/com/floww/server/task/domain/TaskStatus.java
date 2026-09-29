package com.floww.server.task.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Task lifecycle 상태 9개 — 개발 문서 "TaskStatus" (#16 결정, Issue #34).
 *
 * <p>정책 판정(ALLOW/DENY)은 여기에 넣지 않는다. {@link PolicyDecision}으로 attempt마다 따로 기록한다.
 * 허용 전이는 개발 문서 표를 따르고, 아래 두 가지를 추가했다 (docs/TASK_API_KO_EN.md에 기록).
 * <ul>
 *   <li>ACTIVE → AWAITING_APPROVAL: mandate 조건을 수정하면 새 버전을 다시 승인받아야 한다.</li>
 *   <li>AWAITING_APPROVAL/ACTIVE → DECLINED: 유효 후보 소진·시도 한도 도달(NO_VALID_CANDIDATE).</li>
 * </ul>
 */
public enum TaskStatus {
    DRAFT, AWAITING_APPROVAL, ACTIVE, EXECUTING,
    COMPLETED, DECLINED, FAILED, EXPIRED, CANCELLED;

    private static final Map<TaskStatus, Set<TaskStatus>> NEXT = Map.of(
            DRAFT, EnumSet.of(AWAITING_APPROVAL, FAILED),
            AWAITING_APPROVAL, EnumSet.of(ACTIVE, DECLINED, EXPIRED),
            ACTIVE, EnumSet.of(EXECUTING, AWAITING_APPROVAL, DECLINED, EXPIRED, CANCELLED),
            EXECUTING, EnumSet.of(COMPLETED, DECLINED, FAILED, CANCELLED),
            COMPLETED, EnumSet.noneOf(TaskStatus.class),
            DECLINED, EnumSet.noneOf(TaskStatus.class),
            FAILED, EnumSet.noneOf(TaskStatus.class),
            EXPIRED, EnumSet.noneOf(TaskStatus.class),
            CANCELLED, EnumSet.noneOf(TaskStatus.class));

    public boolean isTerminal() {
        return this == COMPLETED || this == DECLINED || this == FAILED
                || this == EXPIRED || this == CANCELLED;
    }

    public boolean canMoveTo(TaskStatus next) {
        return NEXT.get(this).contains(next);
    }
}
