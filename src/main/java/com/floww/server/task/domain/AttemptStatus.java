package com.floww.server.task.domain;

/**
 * 구매 시도 상태. Task 상태와 별도다 — attempt의 BLOCKED(DENY)가 곧 Task 종료가 아니다.
 * POLICY_ALLOWED: 정책 ALLOW, 사용자 승인 대기 · BLOCKED: 정책 DENY ·
 * APPROVED: EIP-712 승인 검증 통과 · ORDERED: 판매자 주문 생성 · SUPERSEDED: mandate 수정으로 무효.
 */
public enum AttemptStatus { POLICY_ALLOWED, BLOCKED, APPROVED, ORDERED, SUPERSEDED }
