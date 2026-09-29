package com.floww.server.task.domain;

/**
 * Mandate 버전 상태 (데이터 명세서 mandate_versions.status).
 * DRAFT: 초안, 아직 지출 권한 없음 · CONFIRMED: EIP-712 승인 검증 통과 ·
 * REVOKED: 새 버전으로 대체되었거나 사용자가 거절 · EXPIRED: 기한 경과.
 */
public enum MandateStatus { DRAFT, CONFIRMED, REVOKED, EXPIRED }
