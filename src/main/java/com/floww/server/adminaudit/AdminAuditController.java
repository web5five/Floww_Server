package com.floww.server.adminaudit;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-audience, GET-only audit namespace; the JWT filter remains the primary boundary. */
@RestController
@RequestMapping("/api/v1/admin/audit/tasks")
public class AdminAuditController {
    private static final Set<String> STATUSES = Set.of("DRAFT", "AWAITING_APPROVAL", "ACTIVE", "EXECUTING",
            "COMPLETED", "DECLINED", "FAILED", "EXPIRED", "CANCELLED");
    private final AdminAuditRepository repository;

    public AdminAuditController(AdminAuditRepository repository) { this.repository = repository; }

    private static void admin(String role, String audience) {
        if (!"ADMIN".equals(role) || !"admin".equals(audience)) throw new ApiException(ErrorCode.FORBIDDEN);
    }
    private static <T> ResponseEntity<T> safe(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    private static int limit(int value) {
        if (value < 1 || value > 50) throw new ApiException(ErrorCode.INVALID_LIMIT);
        return value;
    }

    @GetMapping
    public ResponseEntity<AdminAuditRepository.TaskPage> list(
            @RequestAttribute(name = "role", required = false) String role,
            @RequestAttribute(name = "aud", required = false) String audience,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        admin(role, audience);
        if (page < 0 || page > 100000 || (status != null && !STATUSES.contains(status)))
            throw new ApiException(ErrorCode.INVALID_INPUT);
        return safe(repository.list(status, ownerId, page, limit(limit)));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<AdminAuditRepository.Detail> detail(
            @RequestAttribute(name = "role", required = false) String role,
            @RequestAttribute(name = "aud", required = false) String audience,
            @PathVariable UUID taskId) {
        admin(role, audience);
        return safe(repository.detail(taskId));
    }

    @GetMapping("/{taskId}/events")
    public ResponseEntity<AdminAuditRepository.EventPage> events(
            @RequestAttribute(name = "role", required = false) String role,
            @RequestAttribute(name = "aud", required = false) String audience,
            @PathVariable UUID taskId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        admin(role, audience);
        if (after < 0) throw new ApiException(ErrorCode.INVALID_CURSOR);
        return safe(repository.events(taskId, after, limit(limit)));
    }

    @GetMapping("/{taskId}/account")
    public ResponseEntity<AdminAuditRepository.AccountRow> account(
            @RequestAttribute(name = "role", required = false) String role,
            @RequestAttribute(name = "aud", required = false) String audience,
            @PathVariable UUID taskId) {
        admin(role, audience);
        return safe(repository.account(taskId));
    }
}
