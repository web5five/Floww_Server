package com.floww.server;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {
    private final ExecutionStore store;
    private final ExecutionService service;

    public ExecutionController(ExecutionStore store, ExecutionService service) {
        this.store = store;
        this.service = service;
    }

    @PostMapping
    public ExecutionStore.Execution create(@RequestAttribute("owner") String owner,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        return store.create(owner, Inputs.idempotency(key), Inputs.mandate(body));
    }

    @PostMapping("/{id}/run")
    public ExecutionStore.Execution run(@RequestAttribute("owner") String owner, @PathVariable UUID id) {
        return service.run(owner, id);
    }

    @GetMapping
    public List<ExecutionStore.Execution> history(@RequestAttribute("owner") String owner,
            @RequestParam(defaultValue = "50") int limit) {
        pageLimit(limit);
        return store.history(owner, limit);
    }

    @GetMapping("/history")
    public ExecutionStore.HistoryPage historyPage(@RequestAttribute("owner") String owner,
            @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "50") int limit) {
        pageLimit(limit);
        return store.historyPage(owner, before, limit);
    }

    @GetMapping("/{id}")
    public ExecutionStore.Execution detail(@RequestAttribute("owner") String owner, @PathVariable UUID id) {
        return store.get(owner, id);
    }

    @GetMapping("/{id}/events")
    public ExecutionStore.EventPage events(@RequestAttribute("owner") String owner, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
        page(after, limit);
        return store.events(owner, id, after, limit);
    }

    @GetMapping(value = "/{id}/evidence.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(@RequestAttribute("owner") String owner,
            @PathVariable UUID id, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        page(after, limit);
        ExecutionStore.Execution execution = store.get(owner, id);
        ExecutionStore.EventPage events = store.events(owner, id, after, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", "floww-evidence-2");
        body.put("execution", execution);
        body.put("events", events);
        body.put("complete", after == 0 && !events.hasMore()
                && List.of("REVIEWED", "REJECTED", "FAILED").contains(execution.status()));
        body.put("pageComplete", !events.hasMore());
        body.put("nextCursor", events.nextCursor());
        body.put("evidenceMode", store.evidenceMode(owner, id));
        body.put("modelEvidenceMode", store.modelEvidenceMode(owner, id));
        body.put("modelUsage", store.modelUsage(owner, id));
        body.put("progressLabel", progress(execution.status()));
        body.put("paymentStatus", "NOT_AVAILABLE");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=floww-evidence-" + id + ".json")
                .body(body);
    }

    private static String progress(String status) {
        return switch (status) {
            case "CREATED" -> "Mandate recorded";
            case "RUNNING" -> "Checking offer and quote";
            case "REVIEWED" -> "Quote ready for human review";
            case "REJECTED" -> "Proposal blocked by policy";
            default -> "Check unavailable; review evidence";
        };
    }

    private static void page(long after, int limit) {
        if (after < 0) throw new ApiException(ErrorCode.INVALID_CURSOR);
        pageLimit(limit);
    }

    private static void pageLimit(int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_LIMIT);
    }
}
