package com.floww.server.aiproposal;

import com.floww.server.aiproposal.MerchantProposal.Asset;
import com.floww.server.aiproposal.MerchantProposal.Context;
import com.floww.server.aiproposal.MerchantProposal.Pair;
import com.floww.server.aiproposal.MerchantProposal.Result;
import com.floww.server.aiproposal.MerchantProposal.Status;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.integration.kiln.KilnClient;
import com.floww.server.task.application.TaskCommands.AttemptInput;
import com.floww.server.task.application.TaskService;
import com.floww.server.task.application.TaskViews.AttemptView;
import com.floww.server.task.domain.AttemptStatus;
import com.floww.server.task.domain.MandateStatus;
import com.floww.server.task.domain.TaskModel.Attempt;
import com.floww.server.task.domain.TaskModel.Mandate;
import com.floww.server.task.domain.TaskModel.Quote;
import com.floww.server.task.domain.TaskModel.Task;
import com.floww.server.task.domain.TaskStatus;
import com.floww.server.task.infrastructure.TaskRepository;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Connects a persisted Task snapshot to an AI recommendation and the existing policy sink. */
@Service
public class AiTaskProposalService {
    public record Response(Result proposal, AttemptView attempt, boolean reusedAttempt) { }

    private record Snapshot(Task task, Mandate mandate, List<Quote> quotes, BigInteger consumed,
                            Context context, List<MerchantProposal.Quote> proposalQuotes) { }

    private final TaskService tasks;
    private final TaskRepository repo;
    private final AiMerchantProposal proposal;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AiTaskProposalService(TaskService tasks, TaskRepository repo, KilnClient kiln,
                                 org.springframework.transaction.PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.repo = repo;
        this.clock = Clock.systemUTC();
        this.proposal = new AiMerchantProposal(kiln, clock);
        this.transaction = new TransactionTemplate(manager);
    }

    public Response propose(UUID owner, UUID taskId) {
        // Quote collection and the first immutable read share one short task-row lock.
        Snapshot initial = transaction.execute(status -> {
            Snapshot current = snapshot(owner, taskId);
            if (eligible(current) && current.quotes().isEmpty()) {
                tasks.collectQuotes(owner, taskId);
                return snapshot(owner, taskId);
            }
            return current;
        });
        if (!eligible(initial)) {
            return new Response(reject(initial, "TASK_OR_MANDATE_NOT_DRAFT"), null, false);
        }

        // No DB transaction or task-row lock is held while Kiln is called.
        Result model = proposal.proposePreapproval(initial.context(), initial.proposalQuotes());
        return transaction.execute(status -> finish(owner, taskId, initial, model));
    }

    private Response finish(UUID owner, UUID taskId, Snapshot initial, Result model) {
        Snapshot current = snapshot(owner, taskId); // Holds the same FOR UPDATE lock through TaskService.propose.
        Result returned = model;
        AttemptView attempt = null;
        boolean reused = false;
        if (model.status() == Status.PROPOSED) {
            if (!initial.equals(current)) returned = reject(model, "SNAPSHOT_STALE");
            else if (!eligible(current) || !fresh(current, model.proposedQuote()))
                returned = reject(model, "SNAPSHOT_EXPIRED");
            else {
                String quoteId = model.proposedQuote().quoteId();
                Quote selected = current.quotes().stream()
                        .filter(q -> q.externalQuoteId().equals(quoteId)).findFirst().orElseThrow();
                for (Attempt previous : repo.attempts(taskId)) {
                    if (previous.mandateId().equals(current.mandate().id())
                            && previous.quoteId() != null && previous.quoteId().equals(selected.id())
                            && previous.status() == AttemptStatus.POLICY_ALLOWED
                            && "AI".equals(previous.proposedBy())
                            && selected.totalAmountBaseUnits().equals(previous.amountBaseUnits())
                            && selected.registryRecipientAddress().equals(previous.recipientAddress())
                            && selected.quotedPayToAddress().equals(previous.proposedRecipient())) {
                        UUID priorId = previous.id();
                        attempt = tasks.get(owner, taskId).attempts().stream()
                                .filter(a -> a.attemptId().equals(priorId)).findFirst().orElseThrow();
                        reused = true;
                        break;
                    }
                }
                if (attempt == null) {
                    attempt = tasks.propose(owner, taskId,
                            new AttemptInput(quoteId, "AI", selected.quotedPayToAddress()));
                }
            }
        }
        // Only bounded model metadata is persisted; no prompt, free text, credential or raw arguments.
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("status", returned.status().name());
        evidence.put("reason", returned.reason());
        evidence.put("taskRef", safeId(returned.taskRef()));
        evidence.put("mandateRef", safeId(returned.mandateRef()));
        evidence.put("mandateRevision", safeId(returned.mandateRevision()));
        evidence.put("selectedQuoteId", returned.proposedQuote() == null ? null : returned.proposedQuote().quoteId());
        evidence.put("attemptId", attempt == null ? null : attempt.attemptId().toString());
        evidence.put("reusedAttempt", reused);
        evidence.put("findings", returned.findings().stream().limit(16).map(f -> Map.of(
                "quoteId", safeId(f.quoteId()) == null ? "invalid" : safeId(f.quoteId()),
                "reasons", f.reasons().stream().limit(16).map(AiTaskProposalService::safeId)
                        .filter(java.util.Objects::nonNull).toList())).toList());
        if (returned.provenance() != null) {
            var p = returned.provenance();
            evidence.put("modelId", safeId(p.modelId()));
            evidence.put("modelEvidenceMode", p.modelEvidenceMode());
            evidence.put("finishReason", safeId(p.finishReason()));
            evidence.put("toolCallId", safeId(p.toolCallId()));
            evidence.put("generationId", safeId(p.generationId()));
            evidence.put("usageStatus", p.usageStatus());
            evidence.put("usage", p.usage());
            evidence.put("cost", safeCost(p.cost()));
            evidence.put("attempts", p.attempts());
        }
        repo.appendEvent(taskId, attempt == null ? null : attempt.attemptId(), "AI_PROPOSAL_RESULT",
                current.task().status().name(), returned.reason(), "server", evidence);
        return new Response(returned, attempt, reused);
    }

    private Snapshot snapshot(UUID owner, UUID taskId) {
        Task task = repo.lockTask(owner, taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, taskId));
        Mandate mandate = repo.mandate(taskId, task.currentMandateVersion()).orElseThrow();
        BigInteger consumed = repo.consumedBaseUnits(taskId);
        // Expired quotes disappear from this query, so expiry or removal during inference changes the snapshot.
        List<Quote> quotes = List.copyOf(repo.liveQuotes(taskId, mandate.itemId(), clock.instant()));
        Asset asset = new Asset(Long.toString(mandate.chainId()), mandate.tokenAddress(), mandate.tokenDecimals());
        Context context = new Context(taskId.toString(), mandate.id().toString(),
                Integer.toString(mandate.version()), mandate.itemId(),
                mandate.budgetBaseUnits().subtract(consumed).max(BigInteger.ZERO).toString(), asset,
                mandate.expiresAt(), mandate.allowedRecipients().stream()
                        .map(p -> new Pair(p.merchantId(), p.recipientAddress())).toList(),
                mandate.expiresAt(), null, null, mandate.status().name());
        List<MerchantProposal.Quote> mapped = quotes.stream().map(q -> new MerchantProposal.Quote(
                q.externalQuoteId(), q.merchantId(), q.quotedPayToAddress(), q.itemId(),
                new Asset(Long.toString(q.chainId()), q.tokenAddress(), q.tokenDecimals()),
                q.totalAmountBaseUnits().toString(), q.expiresAt(), q.inStock(),
                q.promisedFulfillmentAt(), false, false)).toList();
        return new Snapshot(task, mandate, quotes, consumed, context, mapped);
    }

    private boolean eligible(Snapshot snapshot) {
        return snapshot.task().status() == TaskStatus.AWAITING_APPROVAL
                && snapshot.mandate().status() == MandateStatus.DRAFT
                && snapshot.mandate().expiresAt().isAfter(clock.instant());
    }

    private boolean fresh(Snapshot current, MerchantProposal.Quote quote) {
        Instant now = clock.instant();
        return current.mandate().expiresAt().isAfter(now)
                && quote.expiresAt().isAfter(now)
                && quote.promisedFulfillmentAt().isAfter(now);
    }

    private static Result reject(Snapshot snapshot, String reason) {
        return new Result(Status.REJECTED, reason, snapshot.context().taskRef(),
                snapshot.context().mandateRef(), snapshot.context().mandateRevision(), null, List.of(), null);
    }

    private static Result reject(Result model, String reason) {
        return new Result(Status.REJECTED, reason, model.taskRef(), model.mandateRef(),
                model.mandateRevision(), null, model.findings(), model.provenance());
    }

    private static String safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : null;
    }

    private static String safeCost(String value) {
        return value != null && value.matches("[0-9]{1,20}(\\.[0-9]{1,12})?") ? value : null;
    }
}
