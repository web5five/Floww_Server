package com.floww.server.task.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.task.application.TaskService;
import com.floww.server.task.application.TaskViews.ApprovalRequestView;
import com.floww.server.task.application.TaskViews.AttemptView;
import com.floww.server.task.application.TaskViews.Created;
import com.floww.server.task.application.TaskViews.EventPage;
import com.floww.server.task.application.TaskViews.OrderView;
import com.floww.server.task.application.TaskViews.ProposalContext;
import com.floww.server.task.application.TaskViews.QuoteList;
import com.floww.server.task.application.TaskViews.TaskView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/tasks — Issue #34, #35 (#32). 모든 경로는 client JWT(USER)가 필요하다.
 * 소유자는 토큰의 sub로만 정하고 요청 본문의 ownerId·role은 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    public ResponseEntity<TaskView> create(@RequestAttribute(name = "owner", required = false) String owner,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        Created<TaskView> result = tasks.create(ownerId, TaskInputs.idempotency(key),
                TaskInputs.mandate(body, tasks.tokenDecimals(), tasks.now()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping
    public List<TaskView> list(@RequestAttribute(name = "owner", required = false) String owner,
            @RequestParam(defaultValue = "20") int limit) {
        return tasks.list(TaskInputs.owner(owner), Math.min(TaskInputs.limit(limit), 50));
    }

    @GetMapping("/{taskId}")
    public TaskView get(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId) {
        return tasks.get(TaskInputs.owner(owner), taskId);
    }

    @PostMapping("/{taskId}/mandate/revisions")
    public TaskView revise(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestBody JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        return tasks.revise(ownerId, taskId, TaskInputs.revision(body, tasks.tokenDecimals(), tasks.now()));
    }

    @PostMapping("/{taskId}/quotes")
    public QuoteList quotes(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId) {
        return tasks.collectQuotes(TaskInputs.owner(owner), taskId);
    }

    @GetMapping("/{taskId}/proposal-context")
    public ProposalContext proposalContext(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId) {
        return tasks.proposalContext(TaskInputs.owner(owner), taskId);
    }

    @PostMapping("/{taskId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptView propose(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestBody JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        return tasks.propose(ownerId, taskId, TaskInputs.attempt(body));
    }

    @PostMapping("/{taskId}/attempts/{attemptId}/approval")
    public ApprovalRequestView approval(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @PathVariable UUID attemptId) {
        return tasks.requestApproval(TaskInputs.owner(owner), taskId, attemptId);
    }

    @PostMapping("/{taskId}/mandate/confirm")
    public TaskView confirm(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestBody JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        return tasks.confirm(ownerId, taskId, TaskInputs.confirm(body));
    }

    @PostMapping("/{taskId}/mandate/reject")
    public TaskView reject(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestBody(required = false) JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        TaskInputs.optionalReason(body);
        return tasks.reject(ownerId, taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public TaskView cancel(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestBody(required = false) JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        TaskInputs.optionalReason(body);
        return tasks.cancel(ownerId, taskId);
    }

    @PostMapping("/{taskId}/orders")
    public ResponseEntity<OrderView> order(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        UUID ownerId = TaskInputs.owner(owner);
        Created<OrderView> result = tasks.createOrder(ownerId, taskId, TaskInputs.idempotency(key),
                TaskInputs.order(body));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/{taskId}/events")
    public EventPage events(@RequestAttribute(name = "owner", required = false) String owner,
            @PathVariable UUID taskId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return tasks.events(TaskInputs.owner(owner), taskId, TaskInputs.cursor(after), TaskInputs.limit(limit));
    }
}
