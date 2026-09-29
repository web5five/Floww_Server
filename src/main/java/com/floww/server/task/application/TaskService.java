package com.floww.server.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.merchant.MerchantRegistry;
import com.floww.server.merchant.PharmacySimulator;
import com.floww.server.merchant.SettlementAsset;
import com.floww.server.task.application.TaskCommands.AttemptInput;
import com.floww.server.task.application.TaskCommands.ConfirmInput;
import com.floww.server.task.application.TaskCommands.MandateInput;
import com.floww.server.task.application.TaskCommands.RevisionInput;
import com.floww.server.task.application.TaskViews.ApprovalRequestView;
import com.floww.server.task.application.TaskViews.ApprovalView;
import com.floww.server.task.application.TaskViews.AttemptView;
import com.floww.server.task.application.TaskViews.Created;
import com.floww.server.task.application.TaskViews.EventPage;
import com.floww.server.task.application.TaskViews.EventView;
import com.floww.server.task.application.TaskViews.MandateView;
import com.floww.server.task.application.TaskViews.OrderView;
import com.floww.server.task.application.TaskViews.PaymentView;
import com.floww.server.task.application.TaskViews.PolicyView;
import com.floww.server.task.application.TaskViews.ProposalAsset;
import com.floww.server.task.application.TaskViews.ProposalContext;
import com.floww.server.task.application.TaskViews.ProposalPair;
import com.floww.server.task.application.TaskViews.ProposalQuote;
import com.floww.server.task.application.TaskViews.QuoteList;
import com.floww.server.task.application.TaskViews.QuoteView;
import com.floww.server.task.application.TaskViews.TaskView;
import com.floww.server.task.approval.PurchaseApproval;
import com.floww.server.task.domain.AttemptStatus;
import com.floww.server.task.domain.BaseUnits;
import com.floww.server.task.domain.MandateStatus;
import com.floww.server.task.domain.PolicyDecision;
import com.floww.server.task.domain.TaskModel.AllowedRecipient;
import com.floww.server.task.domain.TaskModel.Approval;
import com.floww.server.task.domain.TaskModel.ApprovalRequest;
import com.floww.server.task.domain.TaskModel.Attempt;
import com.floww.server.task.domain.TaskModel.Mandate;
import com.floww.server.task.domain.TaskModel.Order;
import com.floww.server.task.domain.TaskModel.Quote;
import com.floww.server.task.domain.TaskModel.Task;
import com.floww.server.task.domain.TaskStatus;
import com.floww.server.task.infrastructure.TaskRepository;
import com.floww.server.task.policy.TaskPolicy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task · Mandate · 견적 · 정책 · EIP-712 승인 · 주문 유스케이스 — Issue #34, #35 (#32).
 *
 * <p>흐름 (정책과 결정 3장): Task/Mandate 초안 → 약국 견적 수집 → (AI/사용자) quote 제안 → 결정론적 정책
 * (ALLOW/DENY) → 사용자가 정확한 제안에 EIP-712 서명 → 서버 검증·nonce 소비 → mandate CONFIRMED →
 * 멱등 주문 생성. 주문은 결제·서명을 실행하지 않는다. 지급(payment)은 이 서비스 범위 밖이다.
 *
 * <p>모든 변경은 Task 행을 {@code FOR UPDATE}로 잠근 트랜잭션 안에서 한다. 소유자가 아니면 404
 * {@code TASK_NOT_FOUND}로 존재 여부를 숨긴다.
 */
@Service
public class TaskService {
    public static final int MAX_ATTEMPTS = 5;
    static final String ACTION_PURCHASE = "PURCHASE";
    static final String BUDGET_SCOPE = "TASK_CUMULATIVE";
    static final String METHOD_EIP712 = "EIP712";

    private final TaskRepository repo;
    private final MerchantRegistry registry;
    private final PharmacySimulator pharmacies;
    private final SettlementAsset asset;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public TaskService(TaskRepository repo, MerchantRegistry registry, PharmacySimulator pharmacies,
                       SettlementAsset asset, ObjectMapper json) {
        this(repo, registry, pharmacies, asset, json, Clock.systemUTC(), new SecureRandom());
    }

    TaskService(TaskRepository repo, MerchantRegistry registry, PharmacySimulator pharmacies, SettlementAsset asset,
                ObjectMapper json, Clock clock, SecureRandom random) {
        this.repo = repo;
        this.registry = registry;
        this.pharmacies = pharmacies;
        this.asset = asset;
        this.json = json;
        this.clock = clock;
        this.random = random;
    }

    public int tokenDecimals() { return asset.decimals(); }

    public Instant now() { return clock.instant(); }

    // ───────────────────────── Task · Mandate ─────────────────────────

    /** POST /api/v1/tasks. 같은 Idempotency-Key + 같은 본문이면 같은 Task, 본문이 다르면 409. */
    @Transactional
    public Created<TaskView> create(UUID owner, String key, MandateInput input) {
        List<AllowedRecipient> allowed = allowedRecipients(input.allowedMerchantIds());
        String requestHash = sha256(String.join("\n", input.goal(), input.itemId(),
                input.maxAmountBaseUnits().toString(), input.expiresAt().toString(),
                allowed.stream().map(AllowedRecipient::merchantId).collect(Collectors.joining(","))));
        UUID taskId = UUID.randomUUID();
        Task task = new Task(taskId, owner, key, requestHash, TaskStatus.AWAITING_APPROVAL, null, input.goal(), 1,
                null, null, null);
        if (!repo.insertTask(task)) {
            Task existing = repo.taskByKey(owner, key).orElseThrow();
            if (!existing.requestHash().equals(requestHash)) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return new Created<>(view(expireIfNeeded(lock(owner, existing.id()))), false);
        }
        Mandate mandate = newMandate(taskId, 1, input, allowed);
        repo.insertMandate(mandate);
        repo.appendEvent(taskId, null, "MANDATE_DRAFTED", TaskStatus.AWAITING_APPROVAL.name(), null, "user",
                mandateEvent(mandate));
        repo.appendEvent(taskId, null, "TASK_STATUS_CHANGED", TaskStatus.AWAITING_APPROVAL.name(), null, "server",
                Map.of("from", TaskStatus.DRAFT.name(), "to", TaskStatus.AWAITING_APPROVAL.name()));
        return new Created<>(view(lock(owner, taskId)), true);
    }

    @Transactional
    public TaskView get(UUID owner, UUID taskId) {
        return view(locked(owner, taskId));
    }

    @Transactional
    public List<TaskView> list(UUID owner, int limit) {
        List<TaskView> views = new ArrayList<>();
        for (Task task : repo.tasks(owner, limit)) views.add(view(locked(owner, task.id())));
        return views;
    }

    /** Mandate 조건 수정 → 새 버전(DRAFT). 이전 버전은 REVOKED, 미주문 시도·승인은 무효가 된다. */
    @Transactional
    public TaskView revise(UUID owner, UUID taskId, RevisionInput input) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL && task.status() != TaskStatus.ACTIVE) {
            throw invalidState(task);
        }
        Mandate current = currentMandate(task);
        if (current.version() != input.baseVersion()) {
            throw new ApiException(ErrorCode.MANDATE_VERSION_MISMATCH, taskId);
        }
        List<AllowedRecipient> allowed = allowedRecipients(input.mandate().allowedMerchantIds());
        repo.updateMandateStatus(current.id(), MandateStatus.REVOKED);
        int superseded = repo.supersedeOpenAttempts(taskId);
        Mandate next = newMandate(taskId, current.version() + 1, input.mandate(), allowed);
        repo.insertMandate(next);
        repo.updateCurrentMandateVersion(taskId, next.version());
        Map<String, Object> payload = new LinkedHashMap<>(mandateEvent(next));
        payload.put("previousVersion", current.version());
        payload.put("supersededAttempts", superseded);
        repo.appendEvent(taskId, null, "MANDATE_REVISED", TaskStatus.AWAITING_APPROVAL.name(), null, "user", payload);
        if (task.status() == TaskStatus.ACTIVE) transition(task, TaskStatus.AWAITING_APPROVAL, null);
        return view(lock(owner, taskId));
    }

    /** 사용자가 위임을 최종 거절 → DECLINED(USER_REJECTED). */
    @Transactional
    public TaskView reject(UUID owner, UUID taskId) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL) throw invalidState(task);
        Mandate mandate = currentMandate(task);
        repo.updateMandateStatus(mandate.id(), MandateStatus.REVOKED);
        repo.supersedeOpenAttempts(taskId);
        transition(task, TaskStatus.DECLINED, ErrorCode.USER_REJECTED.name());
        return view(lock(owner, taskId));
    }

    /** 앞으로의 실행만 멈춘다. 이미 만든 주문·송신된 거래는 되돌렸다고 표시하지 않는다 (SA 6장). */
    @Transactional
    public TaskView cancel(UUID owner, UUID taskId) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.ACTIVE && task.status() != TaskStatus.EXECUTING) throw invalidState(task);
        Mandate mandate = currentMandate(task);
        repo.updateMandateStatus(mandate.id(), MandateStatus.REVOKED);
        repo.supersedeOpenAttempts(taskId);
        transition(task, TaskStatus.CANCELLED, ErrorCode.USER_CANCELLED.name());
        return view(lock(owner, taskId));
    }

    // ───────────────────────── 견적 ─────────────────────────

    /**
     * 현재 mandate 상품의 약국 견적을 모은다. 아직 만료되지 않은 스냅샷이 있으면 그대로 돌려준다(같은 quoteId).
     * 수취 주소는 서버 레지스트리 값으로 매핑하고, 판매자가 말한 payTo는 증거로만 함께 저장한다.
     */
    @Transactional
    public QuoteList collectQuotes(UUID owner, UUID taskId) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL && task.status() != TaskStatus.ACTIVE) {
            throw invalidState(task);
        }
        Mandate mandate = currentMandate(task);
        List<Quote> quotes = liveQuotes(task, mandate);
        return new QuoteList(taskId, mandate.version(), quotes.stream()
                .map(q -> QuoteView.of(q, merchantName(q.merchantId()))).toList());
    }

    /**
     * AI(#18) 입력. {@code aiproposal.MerchantProposal.Context/Quote}와 필드가 1:1이다.
     * mandateState는 "제안을 받을 수 있는가"를 뜻한다: AWAITING_APPROVAL이고 초안이 유효하면 {@code ACTIVE}.
     * 견적의 recipient는 판매자가 말한 payTo다 — AI 필터도 레지스트리 쌍과 비교해 한 번 더 거른다.
     */
    @Transactional
    public ProposalContext proposalContext(UUID owner, UUID taskId) {
        Task task = locked(owner, taskId);
        Mandate mandate = currentMandate(task);
        boolean proposable = task.status() == TaskStatus.AWAITING_APPROVAL
                && mandate.status() == MandateStatus.DRAFT && mandate.expiresAt().isAfter(clock.instant());
        List<Quote> quotes = proposable ? liveQuotes(task, mandate) : List.of();
        BigInteger remaining = mandate.budgetBaseUnits().subtract(repo.consumedBaseUnits(taskId)).max(BigInteger.ZERO);
        ProposalAsset proposalAsset = new ProposalAsset(Long.toString(mandate.chainId()), mandate.tokenAddress(),
                mandate.tokenDecimals());
        return new ProposalContext(taskId.toString(), mandate.id().toString(), Integer.toString(mandate.version()),
                mandate.itemId(), remaining.toString(), proposalAsset, mandate.expiresAt(),
                mandate.allowedRecipients().stream()
                        .map(r -> new ProposalPair(r.merchantId(), r.recipientAddress())).toList(),
                mandate.expiresAt(), null, null, proposable ? "ACTIVE" : task.status().name(),
                quotes.stream().map(q -> new ProposalQuote(q.externalQuoteId(), q.merchantId(), q.quotedPayToAddress(),
                        q.itemId(), new ProposalAsset(Long.toString(q.chainId()), q.tokenAddress(), q.tokenDecimals()),
                        q.totalAmountBaseUnits().toString(), q.expiresAt(), q.inStock(), q.promisedFulfillmentAt(),
                        false, false)).toList());
    }

    // ───────────────────────── 제안 · 정책 ─────────────────────────

    /**
     * quote 제안을 정책으로 판정하고 attempt로 기록한다. DENY는 attempt에만 기록하고 Task를 곧바로 끝내지 않는다.
     * 시도 5회에 도달했거나 살아 있는 모든 견적이 DENY되면 Task를 DECLINED(NO_VALID_CANDIDATE)로 끝낸다.
     */
    @Transactional
    public AttemptView propose(UUID owner, UUID taskId, AttemptInput input) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL) throw invalidState(task);
        if (repo.countAttempts(taskId) >= MAX_ATTEMPTS) throw new ApiException(ErrorCode.ATTEMPT_LIMIT_REACHED, taskId);
        Mandate mandate = currentMandate(task);
        Instant now = clock.instant();
        Optional<Quote> quote = repo.quoteByExternalId(taskId, input.quoteId());
        TaskPolicy.Decision decision = TaskPolicy.evaluate(mandate, quote, input.recipientAddress(), registry,
                repo.consumedBaseUnits(taskId), now);
        String recipient = quote.map(Quote::registryRecipientAddress).orElse(null);
        BigInteger amount = quote.map(Quote::totalAmountBaseUnits).orElse(null);
        Attempt attempt = new Attempt(UUID.randomUUID(), taskId, mandate.id(), quote.map(Quote::id).orElse(null),
                input.quoteId(), input.proposedBy(), input.recipientAddress(),
                decision.allowed() ? AttemptStatus.POLICY_ALLOWED : AttemptStatus.BLOCKED, decision.decision(),
                decision.allowed() ? null : decision.reasonCode().name(), TaskPolicy.VERSION,
                sha256(String.join("|", taskId.toString(), mandate.id().toString(), Integer.toString(mandate.version()),
                        quote.map(Quote::merchantId).orElse(""), input.quoteId(), String.valueOf(recipient),
                        mandate.tokenAddress(), String.valueOf(amount), mandate.budgetBaseUnits().toString())),
                amount, recipient, now, decision.allowed() ? null : now);
        repo.insertAttempt(attempt);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attempt.id().toString());
        payload.put("quoteId", input.quoteId());
        payload.put("merchantId", quote.map(Quote::merchantId).orElse(null));
        payload.put("proposedBy", input.proposedBy());
        payload.put("decision", decision.decision().name());
        payload.put("reasonCode", attempt.reasonCode());
        payload.put("amountBaseUnits", BaseUnits.format(amount));
        payload.put("policyVersion", TaskPolicy.VERSION);
        repo.appendEvent(taskId, attempt.id(), "POLICY_DECIDED", task.status().name(), attempt.reasonCode(),
                "server", payload);
        if (!decision.allowed() && candidatesExhausted(task, mandate)) {
            repo.updateMandateStatus(mandate.id(), MandateStatus.REVOKED);
            repo.supersedeOpenAttempts(taskId);
            transition(task, TaskStatus.DECLINED, ErrorCode.NO_VALID_CANDIDATE.name());
        }
        return attemptViews(lock(owner, taskId)).stream().filter(a -> a.attemptId().equals(attempt.id()))
                .findFirst().orElseThrow();
    }

    // ───────────────────────── EIP-712 승인 ─────────────────────────

    /** ALLOW된 attempt에 대한 typed data와 1회용 nonce 발급. 열린 요청이 있으면 같은 요청을 돌려준다. */
    @Transactional
    public ApprovalRequestView requestApproval(UUID owner, UUID taskId, UUID attemptId) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL) throw invalidState(task);
        Mandate mandate = currentMandate(task);
        Attempt attempt = attempt(task, attemptId);
        if (!attempt.mandateId().equals(mandate.id())) throw new ApiException(ErrorCode.MANDATE_VERSION_MISMATCH,
                taskId.toString(), attemptId.toString());
        if (attempt.status() != AttemptStatus.POLICY_ALLOWED) throw invalidState(task);
        Quote quote = repo.quote(attempt.quoteId()).orElseThrow();
        Instant now = clock.instant();
        requireStillAllowed(task, mandate, quote, attempt, now);

        Optional<ApprovalRequest> open = repo.openApprovalRequest(attemptId, mandate.id(), now);
        if (open.isPresent()) return approvalView(open.get(), mandate);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = "0x" + HexFormat.of().formatHex(bytes);
        Instant expiresAt = (quote.expiresAt().isBefore(mandate.expiresAt()) ? quote.expiresAt() : mandate.expiresAt())
                .truncatedTo(ChronoUnit.SECONDS);
        PurchaseApproval.Message message = message(task, mandate, quote, nonce, expiresAt);
        String typedData = write(PurchaseApproval.typedData(mandate.chainId(), message));
        String digest = PurchaseApproval.digestHex(mandate.chainId(), message);
        ApprovalRequest request = new ApprovalRequest(nonce, taskId, attemptId, mandate.id(), typedData, digest,
                expiresAt, now, null);
        repo.insertApprovalRequest(request);
        repo.appendEvent(taskId, attemptId, "APPROVAL_REQUESTED", task.status().name(), null, "server",
                Map.of("attemptId", attemptId.toString(), "mandateVersion", mandate.version(), "digest", digest,
                        "expiresAt", expiresAt.toString()));
        return approvalView(request, mandate);
    }

    /**
     * POST /mandate/confirm. 저장된 typed data로 digest를 다시 계산해 서명자를 복구하고, 소유자 지갑·최신 버전·
     * 견적·수취인·한도를 재검증한 뒤 nonce를 원자적으로 소비한다. 성공하면 mandate CONFIRMED, Task ACTIVE.
     */
    @Transactional
    public TaskView confirm(UUID owner, UUID taskId, ConfirmInput input) {
        Task task = locked(owner, taskId);
        if (task.status() != TaskStatus.AWAITING_APPROVAL) throw invalidState(task);
        Mandate mandate = currentMandate(task);
        if (!mandate.id().equals(input.mandateId()) || mandate.version() != input.version()) {
            throw new ApiException(ErrorCode.MANDATE_VERSION_MISMATCH, taskId);
        }
        ApprovalRequest request = repo.approvalRequest(input.nonce())
                .filter(r -> r.taskId().equals(taskId) && r.attemptId().equals(input.attemptId()))
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_NONCE_INVALID, taskId));
        if (!request.mandateId().equals(mandate.id())) throw new ApiException(ErrorCode.MANDATE_VERSION_MISMATCH, taskId);
        if (request.consumedAt() != null) throw new ApiException(ErrorCode.APPROVAL_NONCE_INVALID, taskId);
        Instant now = clock.instant();
        if (!request.expiresAt().isAfter(now)) throw new ApiException(ErrorCode.APPROVAL_EXPIRED, taskId);
        Attempt attempt = attempt(task, input.attemptId());
        if (attempt.status() != AttemptStatus.POLICY_ALLOWED) throw invalidState(task);
        Quote quote = repo.quote(attempt.quoteId()).orElseThrow();

        // 서명 대상은 저장된 사실로 다시 만든다. 그 사이 레지스트리·견적이 바뀌었으면 digest가 달라진다.
        PurchaseApproval.Message message = message(task, mandate, quote, request.nonce(), request.expiresAt());
        byte[] digest = PurchaseApproval.digest(mandate.chainId(), message);
        String digestHex = "0x" + HexFormat.of().formatHex(digest);
        if (!digestHex.equals(request.digest())) {
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED, taskId.toString(), attempt.id().toString());
        }
        String signer = PurchaseApproval.recoverSigner(digest, input.signature())
                .orElseThrow(() -> new ApiException(ErrorCode.SIGNATURE_INVALID, taskId));
        if (!repo.ownerHasWallet(owner, signer)) throw new ApiException(ErrorCode.SIGNER_NOT_TASK_OWNER, taskId);
        requireStillAllowed(task, mandate, quote, attempt, now);
        if (!repo.consumeNonce(request.nonce())) throw new ApiException(ErrorCode.APPROVAL_NONCE_INVALID, taskId);

        Approval approval = new Approval(UUID.randomUUID(), taskId, mandate.id(), attempt.id(), request.nonce(),
                METHOD_EIP712, digestHex, input.signature().toLowerCase(Locale.ROOT), signer, now);
        repo.insertApproval(approval, request.typedDataJson());
        repo.confirmMandate(mandate.id(), METHOD_EIP712, digestHex);
        repo.updateAttemptStatus(attempt.id(), AttemptStatus.APPROVED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandateId", mandate.id().toString());
        payload.put("version", mandate.version());
        payload.put("attemptId", attempt.id().toString());
        payload.put("method", METHOD_EIP712);
        payload.put("digest", digestHex);
        payload.put("signerAddress", signer);
        repo.appendEvent(taskId, attempt.id(), "MANDATE_CONFIRMED", TaskStatus.ACTIVE.name(), null, "user", payload);
        transition(task, TaskStatus.ACTIVE, null);
        return view(lock(owner, taskId));
    }

    // ───────────────────────── 주문 ─────────────────────────

    /**
     * 승인된 attempt의 판매자 주문을 만든다. 같은 Idempotency-Key·같은 본문이면 같은 주문을 돌려준다.
     * 주문 직전에 mandate·견적·수취인·누적 한도를 다시 검증한다. 결제·서명은 실행하지 않는다.
     */
    @Transactional
    public Created<OrderView> createOrder(UUID owner, UUID taskId, String key, UUID attemptId) {
        Task task = locked(owner, taskId);
        String requestHash = sha256("order|" + attemptId);
        Optional<Order> existing = repo.orderByKey(taskId, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, taskId);
            }
            return new Created<>(orderView(existing.get()), false);
        }
        if (task.status() != TaskStatus.ACTIVE) throw invalidState(task);
        Mandate mandate = currentMandate(task);
        Attempt attempt = attempt(task, attemptId);
        if (attempt.status() != AttemptStatus.APPROVED) throw invalidState(task);
        if (!attempt.mandateId().equals(mandate.id()) || mandate.status() != MandateStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED, taskId.toString(), attemptId.toString());
        }
        Approval approval = repo.approvalForAttempt(attemptId).orElseThrow(
                () -> new ApiException(ErrorCode.APPROVAL_INVALIDATED, taskId.toString(), attemptId.toString()));
        Quote quote = repo.quote(attempt.quoteId()).orElseThrow();
        Instant now = clock.instant();
        requireStillAllowed(task, mandate, quote, attempt, now);

        PharmacySimulator.Order merchantOrder = pharmacies.placeOrder(quote.merchantId(), quote.externalQuoteId());
        Order order = new Order(UUID.randomUUID(), taskId, attemptId, quote.id(), approval.id(), quote.merchantId(),
                merchantOrder.orderId(), key, requestHash, merchantOrder.status(), PaymentView.NOT_ATTEMPTED.status(),
                quote.totalAmountBaseUnits(), attempt.recipientAddress(), now);
        repo.insertOrder(order);
        repo.updateAttemptStatus(attemptId, AttemptStatus.ORDERED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id().toString());
        payload.put("merchantOrderId", order.externalOrderId());
        payload.put("quoteId", quote.externalQuoteId());
        payload.put("merchantId", quote.merchantId());
        payload.put("amountBaseUnits", order.amountBaseUnits().toString());
        payload.put("recipientAddress", order.recipientAddress());
        payload.put("paymentStatus", order.paymentStatus());
        payload.put("evidenceMode", pharmacies.evidenceMode());
        repo.appendEvent(taskId, attemptId, "ORDER_CREATED", TaskStatus.EXECUTING.name(), null, "user", payload);
        transition(task, TaskStatus.EXECUTING, null);
        return new Created<>(orderView(repo.orderByKey(taskId, key).orElseThrow()), true);
    }

    // ───────────────────────── 이벤트 ─────────────────────────

    @Transactional(readOnly = true)
    public EventPage events(UUID owner, UUID taskId, long after, int limit) {
        repo.task(owner, taskId).orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, taskId));
        var rows = repo.events(taskId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<EventView> page = rows.subList(0, Math.min(limit, rows.size())).stream()
                .map(e -> new EventView(e.seq(), e.kind(), e.state(), e.reasonCode(), e.attemptId(), e.actor(),
                        e.payload(), e.createdAt())).toList();
        return new EventPage(page, page.isEmpty() ? after : page.getLast().seq(), more);
    }

    // ───────────────────────── 내부 ─────────────────────────

    private Task lock(UUID owner, UUID taskId) {
        return repo.lockTask(owner, taskId).orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, taskId));
    }

    /** 잠그고, 기한이 지난 mandate면 먼저 EXPIRED로 바꾼다. */
    private Task locked(UUID owner, UUID taskId) {
        return expireIfNeeded(lock(owner, taskId));
    }

    private Task expireIfNeeded(Task task) {
        if (task.status() != TaskStatus.AWAITING_APPROVAL && task.status() != TaskStatus.ACTIVE) return task;
        Mandate mandate = currentMandate(task);
        if (mandate.expiresAt().isAfter(clock.instant())) return task;
        repo.updateMandateStatus(mandate.id(), MandateStatus.EXPIRED);
        repo.supersedeOpenAttempts(task.id());
        transition(task, TaskStatus.EXPIRED, ErrorCode.MANDATE_EXPIRED.name());
        return lock(task.ownerId(), task.id());
    }

    private void transition(Task task, TaskStatus to, String reasonCode) {
        if (!task.status().canMoveTo(to)) throw invalidState(task);
        repo.updateTaskStatus(task.id(), to, reasonCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", task.status().name());
        payload.put("to", to.name());
        repo.appendEvent(task.id(), null, "TASK_STATUS_CHANGED", to.name(), reasonCode, "server", payload);
    }

    private ApiException invalidState(Task task) {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, task.id());
    }

    private Mandate currentMandate(Task task) {
        return repo.mandate(task.id(), task.currentMandateVersion()).orElseThrow();
    }

    private Attempt attempt(Task task, UUID attemptId) {
        return repo.attempt(task.id(), attemptId).orElseThrow(
                () -> new ApiException(ErrorCode.ATTEMPT_NOT_FOUND, task.id().toString(), attemptId.toString()));
    }

    /** 승인·주문 직전 재검증. attempt 당시 금액·수취인과 지금 정책 결과가 모두 같아야 한다. */
    private void requireStillAllowed(Task task, Mandate mandate, Quote quote, Attempt attempt, Instant now) {
        TaskPolicy.Decision decision = TaskPolicy.evaluate(mandate, Optional.of(quote), attempt.proposedRecipient(),
                registry, repo.consumedBaseUnits(task.id()), now);
        if (!decision.allowed() || !quote.totalAmountBaseUnits().equals(attempt.amountBaseUnits())
                || !quote.registryRecipientAddress().equals(attempt.recipientAddress())) {
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED, task.id().toString(), attempt.id().toString());
        }
    }

    private boolean candidatesExhausted(Task task, Mandate mandate) {
        List<Attempt> attempts = repo.attempts(task.id());
        if (attempts.size() >= MAX_ATTEMPTS) return true;
        List<Quote> live = repo.liveQuotes(task.id(), mandate.itemId(), clock.instant());
        if (live.isEmpty()) return false;
        for (Quote quote : live) {
            boolean denied = attempts.stream().anyMatch(a -> a.mandateId().equals(mandate.id())
                    && quote.id().equals(a.quoteId()) && a.decision() == PolicyDecision.DENY);
            if (!denied) return false;
        }
        return true;
    }

    private List<Quote> liveQuotes(Task task, Mandate mandate) {
        Instant now = clock.instant();
        List<Quote> live = repo.liveQuotes(task.id(), mandate.itemId(), now);
        if (!live.isEmpty()) return live;
        List<PharmacySimulator.Quote> fetched = pharmacies.quotes(task.id().toString(), mandate.itemId(), now);
        for (PharmacySimulator.Quote q : fetched) {
            String trusted = registry.find(q.merchantId()).map(MerchantRegistry.Merchant::recipientAddress)
                    .orElseThrow(() -> new IllegalStateException("simulator merchant missing from registry"));
            repo.insertQuote(new Quote(UUID.randomUUID(), task.id(), q.merchantId(), q.quoteId(), q.itemId(),
                    q.itemName(), q.quantity(), q.inStock(), q.chainId(), q.tokenAddress(), q.tokenDecimals(),
                    q.itemAmountBaseUnits(), q.deliveryFeeBaseUnits(), q.totalAmountBaseUnits(), q.payToAddress(),
                    trusted, pharmacies.evidenceMode(), q.quotedAt(), q.expiresAt(), q.promisedFulfillmentAt()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandateVersion", mandate.version());
        payload.put("itemId", mandate.itemId());
        payload.put("quoteIds", fetched.stream().map(PharmacySimulator.Quote::quoteId).toList());
        payload.put("evidenceMode", pharmacies.evidenceMode());
        repo.appendEvent(task.id(), null, "QUOTES_COLLECTED", task.status().name(), null, "server", payload);
        return repo.liveQuotes(task.id(), mandate.itemId(), now);
    }

    private List<AllowedRecipient> allowedRecipients(List<String> merchantIds) {
        if (merchantIds == null) {
            return registry.active().stream()
                    .map(m -> new AllowedRecipient(m.merchantId(), m.recipientAddress())).toList();
        }
        List<AllowedRecipient> allowed = new ArrayList<>();
        for (String id : merchantIds) {
            MerchantRegistry.Merchant merchant = registry.find(id).filter(MerchantRegistry.Merchant::active)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_INPUT));
            allowed.add(new AllowedRecipient(merchant.merchantId(), merchant.recipientAddress()));
        }
        return allowed;
    }

    private Mandate newMandate(UUID taskId, int version, MandateInput input, List<AllowedRecipient> allowed) {
        UUID id = UUID.randomUUID();
        String hash = sha256(String.join("\n", taskId.toString(), Integer.toString(version), input.goal(),
                input.itemId(), input.maxAmountBaseUnits().toString(), BUDGET_SCOPE, Long.toString(asset.chainId()),
                asset.tokenAddress(), Integer.toString(asset.decimals()),
                allowed.stream().map(r -> r.merchantId() + "=" + r.recipientAddress()).collect(Collectors.joining(",")),
                ACTION_PURCHASE, input.expiresAt().toString()));
        return new Mandate(id, taskId, version, MandateStatus.DRAFT, input.goal(), input.itemId(),
                input.maxAmountBaseUnits(), BUDGET_SCOPE, asset.chainId(), asset.tokenAddress(), asset.decimals(),
                allowed, List.of(ACTION_PURCHASE), input.expiresAt(), null, null, null, hash, null);
    }

    private static Map<String, Object> mandateEvent(Mandate m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandateId", m.id().toString());
        payload.put("version", m.version());
        payload.put("itemId", m.itemId());
        payload.put("maxAmountBaseUnits", m.budgetBaseUnits().toString());
        payload.put("expiresAt", m.expiresAt().toString());
        payload.put("mandateHash", m.mandateHash());
        return payload;
    }

    private PurchaseApproval.Message message(Task task, Mandate mandate, Quote quote, String nonce, Instant expiresAt) {
        String trusted = registry.find(quote.merchantId()).map(MerchantRegistry.Merchant::recipientAddress)
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_INVALIDATED, task.id()));
        return new PurchaseApproval.Message(task.id().toString(), mandate.id().toString(), mandate.version(),
                quote.merchantId(), quote.externalQuoteId(), trusted, mandate.tokenAddress(),
                quote.totalAmountBaseUnits(), mandate.budgetBaseUnits(), expiresAt.getEpochSecond(), nonce);
    }

    private ApprovalRequestView approvalView(ApprovalRequest request, Mandate mandate) {
        return new ApprovalRequestView(request.taskId(), request.attemptId(), request.mandateId(), mandate.version(),
                request.nonce(), request.digest(), request.expiresAt(), read(request.typedDataJson()));
    }

    private TaskView view(Task task) {
        Mandate mandate = currentMandate(task);
        BigInteger consumed = repo.consumedBaseUnits(task.id());
        return new TaskView(task.id(), task.ownerId(), task.status().name(), task.statusReasonCode(), task.goal(),
                MandateView.of(mandate, consumed), attemptViews(task), task.createdAt(), task.updatedAt(),
                task.completedAt());
    }

    private List<AttemptView> attemptViews(Task task) {
        Map<UUID, Mandate> mandates = repo.mandates(task.id()).stream()
                .collect(Collectors.toMap(Mandate::id, Function.identity()));
        Map<UUID, Order> orders = repo.orders(task.id()).stream()
                .collect(Collectors.toMap(Order::attemptId, Function.identity()));
        List<AttemptView> views = new ArrayList<>();
        for (Attempt a : repo.attempts(task.id())) {
            Optional<Quote> quote = repo.quote(a.quoteId());
            Order order = orders.get(a.id());
            views.add(new AttemptView(a.id(), a.mandateId(), mandates.get(a.mandateId()).version(),
                    a.proposedQuoteRef(), quote.map(Quote::merchantId).orElse(null), a.proposedBy(), a.status().name(),
                    BaseUnits.format(a.amountBaseUnits()), a.recipientAddress(), PolicyView.of(a),
                    ApprovalView.of(repo.approvalForAttempt(a.id()).orElse(null)),
                    order == null ? null : OrderView.of(order, a.proposedQuoteRef()), PaymentView.NOT_ATTEMPTED,
                    a.startedAt()));
        }
        return views;
    }

    private OrderView orderView(Order order) {
        String externalQuoteId = repo.quote(order.quoteId()).map(Quote::externalQuoteId).orElse(null);
        return OrderView.of(order, externalQuoteId);
    }

    private String merchantName(String merchantId) {
        return registry.find(merchantId).map(MerchantRegistry.Merchant::name).orElse(merchantId);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("typed data serialization failed", e);
        }
    }

    private Map<String, Object> read(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored typed data is invalid", e);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
