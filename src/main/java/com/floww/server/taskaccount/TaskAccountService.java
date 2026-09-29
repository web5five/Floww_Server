package com.floww.server.taskaccount;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.merchant.PharmacySimulator;
import com.floww.server.task.application.TaskService;
import com.floww.server.task.domain.TaskModel.Order;
import com.floww.server.task.infrastructure.TaskRepository;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/** Opt-in Sepolia TaskAccount lifecycle. All externally sent operations are reserved durably first. */
@Service
public class TaskAccountService {
    private final TaskService tasks;
    private final TaskRepository taskRepo;
    private final TaskAccountRepository repo;
    private final TaskAccountConfig config;
    private final TaskAccountArtifact artifact;
    private final TaskAccountRpc rpc;
    private final PharmacySimulator pharmacies;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public TaskAccountService(TaskService tasks, TaskRepository taskRepo, TaskAccountRepository repo,
                              TaskAccountConfig config, TaskAccountArtifact artifact, TaskAccountRpc rpc,
                              PharmacySimulator pharmacies, ObjectMapper json, TransactionTemplate transactions) {
        this.tasks=tasks;this.taskRepo=taskRepo;this.repo=repo;this.config=config;this.artifact=artifact;
        this.rpc=rpc;this.pharmacies=pharmacies;this.json=json;this.transactions=transactions;
    }
    public record AccountView(UUID taskId,UUID attemptId,String state,String ownerAddress,String accountAddress,
                              String deployTxHash,String chainTaskId,String reviewSnapshotDigest,String amountBaseUnits,
                              String tokenAddress,String recipientAddress,String executorAddress,String fulfillmentReporter,
                              Instant quoteExpiresAt,Instant expiresAt,String deploymentData,String approvalDigest,
                              String approvalTxHash,String approvalOperationState,String paymentId,String paymentTxHash,
                              String paymentOperationState,Instant paymentVerifiedAt,String fulfillmentId,
                              String fulfillmentEvidenceMode,String fulfillmentEvidenceHash,String fulfillmentTxHash,
                              String fulfillmentOperationState,Instant fulfillmentVerifiedAt) { }
    public record ApprovalView(Map<String,Object> typedData,String digest,String nonce,Instant expiresAt) { }
    public record FundingView(String accountAddress,String tokenAddress,String amountBaseUnits,String tokenApproveData,
                              String accountFundData,String accountTokenBalanceBaseUnits,boolean mandateApproved) { }
    private void enabled() { if (!config.enabled) throw new ApiException(ErrorCode.CHAIN_NOT_READY); }
    private static String lower(String s) {return s.toLowerCase(Locale.ROOT);}
    private static String address(String s) {
        if (s==null || !s.matches("0x[0-9a-fA-F]{40}")) throw new ApiException(ErrorCode.INVALID_INPUT);
        return lower(s);
    }
    private static String hash(String s) {
        if (s==null || !s.matches("0x[0-9a-fA-F]{64}")) throw new ApiException(ErrorCode.INVALID_INPUT);
        return lower(s);
    }
    private static void require(boolean condition) {if (!condition) throw new ApiException(ErrorCode.CHAIN_NOT_READY);}
    private TaskAccountRepository.Account owned(UUID owner,UUID taskId) {
        tasks.get(owner,taskId);
        return repo.byTask(taskId).orElseThrow(()->new ApiException(ErrorCode.CHAIN_NOT_READY,taskId));
    }
    private AccountView view(TaskAccountRepository.Account a,boolean deployment) {
        String data=deployment && "PREPARED".equals(a.state()) && Instant.now().isBefore(a.expiresAt())
                ? artifact.deployment(a.owner(),a.chainTaskId(),
                a.reviewDigest(),a.token(),a.recipient(),a.executor(),a.reporter(),a.amount(),a.expiresAt().getEpochSecond()) : null;
        return new AccountView(a.taskId(),a.attemptId(),a.state(),a.owner(),a.address(),a.deployTx(),a.chainTaskId(),
                a.reviewDigest(),a.amount().toString(),a.token(),a.recipient(),a.executor(),a.reporter(),
                a.quoteExpiresAt(),a.expiresAt(),data,a.approvalDigest(),operationHash(a,"APPROVAL"),
                operationState(a,"APPROVAL"),a.paymentId(),a.paymentTx(),operationState(a,"PAYMENT"),
                a.paymentVerifiedAt(),a.fulfillmentId(),a.fulfillmentId()==null?null:pharmacies.evidenceMode(),
                a.evidenceHash(),a.fulfillmentTx(),operationState(a,"FULFILLMENT"),a.fulfillmentVerifiedAt());
    }
    private String operationHash(TaskAccountRepository.Account a,String kind) {
        return repo.operation(a.id(),kind).map(TaskAccountRepository.Operation::hash).orElse(null);
    }
    private String operationState(TaskAccountRepository.Account a,String kind) {
        return repo.operation(a.id(),kind).map(TaskAccountRepository.Operation::state).orElse(null);
    }
    @Transactional
    public AccountView prepare(UUID owner,UUID taskId,UUID attemptId,String wallet) {
        enabled(); wallet=address(wallet);
        var candidate=tasks.accountCandidate(owner,taskId,attemptId);
        if (candidate.mandate().chainId()!=11155111 || !taskRepo.ownerHasWallet(owner,wallet)
                || wallet.equals(config.executor) || wallet.equals(config.reporter))
            throw new ApiException(ErrorCode.CHAIN_NOT_READY,taskId);
        var existing=repo.byTask(taskId);
        if (existing.isPresent()) {
            if (!existing.get().attemptId().equals(attemptId) || !existing.get().owner().equals(wallet))
                throw new ApiException(ErrorCode.APPROVAL_INVALIDATED,taskId);
            return view(existing.get(),true);
        }
        var m=candidate.mandate(); var q=candidate.quote();
        if (q.registryRecipientAddress().matches("0x00000000000000000000000000000000f10aa00[123]"))
            throw new ApiException(ErrorCode.CHAIN_NOT_READY,taskId);
        var review=new TaskAccountCrypto.Review(taskId,m.id(),m.version(),attemptId,q.merchantId(),
                q.externalQuoteId(),q.totalAmountBaseUnits(),q.registryRecipientAddress(),m.tokenAddress(),
                q.expiresAt());
        var typed=TaskAccountCrypto.reviewSchema(review);
        Instant expiry=q.expiresAt().isBefore(m.expiresAt())?q.expiresAt():m.expiresAt();
        var a=new TaskAccountRepository.Account(UUID.randomUUID(),taskId,attemptId,m.id(),m.version(),q.id(),wallet,
                null,null,m.chainId(),TaskAccountCrypto.taskId(taskId),TaskAccountCrypto.reviewDigest(review),write(typed),
                m.tokenAddress(),q.registryRecipientAddress(),config.executor,config.reporter,q.totalAmountBaseUnits(),
                q.expiresAt(),expiry,"PREPARED",null,null,null,null,null,null,null,null,null,null);
        repo.insert(a);
        taskRepo.appendEvent(taskId,attemptId,"ACCOUNT_PREPARED",candidate.task().status().name(),null,"server",
                Map.of("reviewSnapshotDigest",a.reviewDigest(),"chainTaskId",a.chainTaskId(),"quoteId",q.externalQuoteId()));
        return view(repo.byTask(taskId).orElseThrow(),true);
    }
    public AccountView get(UUID owner,UUID taskId) {enabled();return view(owned(owner,taskId),true);}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String getterAddress(String value) {return "0x"+value.substring(26).toLowerCase(Locale.ROOT);}
    private static BigInteger getterNumber(String value) {return Numeric.toBigInt(value);}
    private void verifyAccount(TaskAccountRepository.Account a) {
        require(rpc.number("eth_chainId").equals(BigInteger.valueOf(11155111)));
        require(artifact.matchesRuntime(rpc.code(a.address())));
        require(getterAddress(rpc.read(a.address(),"owner()")).equals(a.owner()));
        require(rpc.read(a.address(),"taskId()").equals(a.chainTaskId()));
        require(rpc.read(a.address(),"reviewSnapshotDigest()").equals(a.reviewDigest()));
        require(getterAddress(rpc.read(a.address(),"token()")).equals(a.token()));
        require(getterAddress(rpc.read(a.address(),"recipient()")).equals(a.recipient()));
        require(getterAddress(rpc.read(a.address(),"executor()")).equals(a.executor()));
        require(getterAddress(rpc.read(a.address(),"fulfillmentReporter()")).equals(a.reporter()));
        require(getterNumber(rpc.read(a.address(),"maxSpend()")).equals(a.amount()));
        require(getterNumber(rpc.read(a.address(),"expiresAt()")).equals(BigInteger.valueOf(a.expiresAt().getEpochSecond())));
        String expectedMandateHash=TaskAccountCrypto.mandateHash(a.chainId(),a.owner(),a.chainTaskId(),
                a.reviewDigest(),a.token(),a.recipient(),a.executor(),a.reporter(),a.amount(),
                a.expiresAt().getEpochSecond());
        require(rpc.read(a.address(),"mandateHash()").equalsIgnoreCase(expectedMandateHash));
        require(!getterNumber(rpc.read(a.address(),"revoked()")).equals(BigInteger.ONE));
    }
    public AccountView bind(UUID owner,UUID taskId,String accountAddress,String txHash) {
        enabled(); accountAddress=address(accountAddress); txHash=hash(txHash);
        var a=owned(owner,taskId);
        if (!"PREPARED".equals(a.state())) {
            if (accountAddress.equals(a.address()) && txHash.equals(a.deployTx())) return view(a,false);
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED,taskId);
        }
        tasks.accountCandidate(owner,taskId,a.attemptId());
        require(Instant.now().isBefore(a.expiresAt()));
        JsonNode receipt=rpc.receipt(txHash), tx=rpc.transaction(txHash);
        require(!receipt.isNull() && !tx.isNull() && "0x1".equals(receipt.path("status").asText()));
        require(accountAddress.equalsIgnoreCase(receipt.path("contractAddress").asText()));
        require(a.owner().equalsIgnoreCase(tx.path("from").asText()) && tx.path("to").isNull());
        require(tx.path("input").asText().equalsIgnoreCase(artifact.deployment(a.owner(),a.chainTaskId(),
                a.reviewDigest(),a.token(),a.recipient(),a.executor(),a.reporter(),a.amount(),a.expiresAt().getEpochSecond())));
        require(rpc.number("eth_chainId").equals(BigInteger.valueOf(a.chainId())));
        // Runtime template plus every immutable getter: an arbitrary contract with matching getters is rejected.
        var candidate=new TaskAccountRepository.Account(a.id(),a.taskId(),a.attemptId(),a.mandateId(),a.mandateVersion(),
                a.quoteId(),a.owner(),accountAddress,txHash,a.chainId(),a.chainTaskId(),a.reviewDigest(),
                a.reviewTypedData(),a.token(),a.recipient(),a.executor(),a.reporter(),a.amount(),a.quoteExpiresAt(),
                a.expiresAt(),"BOUND",null,null,null,null,null,null,null,null,null,null);
        verifyAccount(candidate);
        String finalAddress=accountAddress, finalHash=txHash;
        transactions.executeWithoutResult(s->{
            tasks.accountCandidate(owner,taskId,a.attemptId());
            var current=repo.lock(taskId).orElseThrow();
            require(current.id().equals(a.id()) && "PREPARED".equals(current.state())
                    && Instant.now().isBefore(current.expiresAt()));
            require(repo.bind(a.id(),finalAddress,finalHash));
            taskRepo.appendEvent(taskId,a.attemptId(),"ACCOUNT_BOUND",null,null,"server",
                    Map.of("accountAddress",finalAddress,"deploymentTxHash",finalHash));
        });
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
    private TaskAccountCrypto.Approval approval(TaskAccountRepository.Account a,BigInteger nonce) {
        return new TaskAccountCrypto.Approval(a.owner(),a.address(),a.chainTaskId(),a.reviewDigest(),a.token(),
                a.recipient(),a.executor(),a.reporter(),a.amount(),a.expiresAt().getEpochSecond(),nonce,a.chainId());
    }
    public ApprovalView approvalRequest(UUID owner,UUID taskId) {
        enabled(); var a=owned(owner,taskId);
        if (!"BOUND".equals(a.state())) throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,taskId);
        tasks.accountCandidate(owner,taskId,a.attemptId());
        require(Instant.now().isBefore(a.expiresAt())); verifyAccount(a);
        require(getterNumber(rpc.read(a.address(),"mandateApproved()")).equals(BigInteger.ZERO));
        BigInteger nonce=getterNumber(rpc.read(a.address(),"authorizationNonce()"));
        var typed=TaskAccountCrypto.approvalTypedData(approval(a,nonce));
        String digest=TaskAccountCrypto.digest(typed);
        require(rpc.read(a.address(),"mandateApprovalDigest()").equals(digest));
        return new ApprovalView(typed,digest,nonce.toString(),a.expiresAt());
    }
    public AccountView sign(UUID owner,UUID taskId,String signature) {
        enabled(); var a=owned(owner,taskId);
        if ("SIGNED".equals(a.state())) {
            if (a.approvalSignature().equalsIgnoreCase(signature)) return view(a,false);
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED,taskId);
        }
        var request=approvalRequest(owner,taskId);
        String signer=com.floww.server.task.approval.PurchaseApproval.recoverSigner(Numeric.hexStringToByteArray(request.digest()),signature)
                .orElseThrow(()->new ApiException(ErrorCode.SIGNATURE_INVALID,taskId));
        if (!signer.equals(a.owner())) throw new ApiException(ErrorCode.SIGNER_NOT_TASK_OWNER,taskId);
        transactions.executeWithoutResult(s->{
            tasks.confirmAccount(owner,taskId,a.attemptId(),request.digest(),signature,signer,write(request.typedData()));
            var current=repo.lock(taskId).orElseThrow();
            require(current.id().equals(a.id()) && "BOUND".equals(current.state()));
            require(repo.signed(a.id(),new BigInteger(request.nonce()),request.digest(),signature.toLowerCase(Locale.ROOT)));
        });
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
    private void verifyApproved(TaskAccountRepository.Account a) {
        verifyAccount(a);
        require(getterNumber(rpc.read(a.address(),"mandateApproved()")).equals(BigInteger.ONE));
        require(rpc.read(a.address(),"approvedDigest()").equals(a.approvalDigest()));
        require(getterNumber(rpc.read(a.address(),"authorizationNonce()")).equals(a.approvalNonce().add(BigInteger.ONE)));
        require(getterNumber(rpc.read(a.address(),"paymentExecuted()")).equals(BigInteger.ZERO));
        require(getterNumber(rpc.read(a.address(),"isActive()")).equals(BigInteger.ONE));
    }
    private TaskAccountRepository.Operation reserve(UUID owner,TaskAccountRepository.Account a,String kind,Credentials key,String calldata) {
        BigInteger pending=rpc.number("eth_getTransactionCount",key.getAddress(),"pending");
        BigInteger gasPrice=rpc.number("eth_gasPrice");
        return transactions.execute(status -> {
            if (kind.equals("PAYMENT")) tasks.accountPaymentOrder(owner,a.taskId(),a.attemptId());
            else if (kind.equals("APPROVAL")) tasks.accountApprovalReady(owner,a.taskId(),a.attemptId());
            else tasks.get(owner,a.taskId());
            var current=repo.lock(a.taskId()).orElseThrow();
            if (!current.state().equals(a.state())) throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,a.taskId());
            var prior=repo.operation(a.id(),kind);
            if (prior.isPresent()) return prior.get();
            BigInteger nonce=repo.reserveNonce(key.getAddress().toLowerCase(Locale.ROOT),pending);
            RawTransaction tx=RawTransaction.createTransaction(nonce,gasPrice,
                    BigInteger.valueOf(kind.equals("PAYMENT")?300_000:220_000),a.address(),BigInteger.ZERO,calldata);
            String raw=Numeric.toHexString(TransactionEncoder.signMessage(tx,11155111L,key));
            String txHash=Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(raw)));
            var op=new TaskAccountRepository.Operation(UUID.randomUUID(),a.id(),kind,
                    key.getAddress().toLowerCase(Locale.ROOT),nonce,raw,txHash,"UNKNOWN");
            repo.operation(op);
            if (kind.equals("APPROVAL")) repo.state(a.id(),"SIGNED","APPROVAL_UNKNOWN");
            if (kind.equals("PAYMENT")) {
                repo.payment(a.id(),TaskAccountCrypto.paymentId(a.attemptId()),txHash);
                taskRepo.updateOrderPayment(a.attemptId(),"UNKNOWN");
            }
            if (kind.equals("FULFILLMENT")) {
                PharmacySimulator.Fulfillment f=pharmacies.fulfill(taskRepo.orders(a.taskId()).stream()
                        .filter(o->o.attemptId().equals(a.attemptId())).findFirst().orElseThrow().externalOrderId());
                repo.fulfillment(a.id(),f.reference(),
                        TaskAccountCrypto.evidenceHash(f.orderId(),f.reference()),txHash);
            }
            return op;
        });
    }
    private void sendOnce(TaskAccountRepository.Operation op) {
        // A transport error leaves the persisted transaction UNKNOWN. Reconcile; never allocate a new nonce.
        try {rpc.send(op.raw(),op.hash());} catch (Exception ignored) { }
    }
    public AccountView approve(UUID owner,UUID taskId) {
        enabled(); var a=owned(owner,taskId);
        if ("APPROVAL_UNKNOWN".equals(a.state())) return view(a,false);
        if (!"SIGNED".equals(a.state())) return view(a,false);
        if (repo.operation(a.id(),"APPROVAL").isPresent()) return view(a,false);
        tasks.accountApprovalReady(owner,taskId,a.attemptId());
        require(Instant.now().isBefore(a.expiresAt())); verifyAccount(a);
        require(rpc.read(a.address(),"mandateApprovalDigest()").equals(a.approvalDigest()));
        require(getterNumber(rpc.read(a.address(),"authorizationNonce()")).equals(a.approvalNonce()));
        String sig=a.approvalSignature().substring(2);
        String data=TaskAccountArtifact.selector("approveMandate(bytes)")+TaskAccountArtifact.word(BigInteger.valueOf(32))
                +TaskAccountArtifact.word(BigInteger.valueOf(sig.length()/2))+sig+"0".repeat((64-sig.length()%64)%64);
        var op=reserve(owner,a,"APPROVAL",config.executorKey,data); sendOnce(op);
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
    public FundingView funding(UUID owner,UUID taskId) {
        enabled(); var a=owned(owner,taskId);if (a.address()==null) throw new ApiException(ErrorCode.CHAIN_NOT_READY);
        verifyAccount(a);
        String balanceData=TaskAccountArtifact.selector("balanceOf(address)")+TaskAccountArtifact.word(a.address());
        BigInteger balance=Numeric.toBigInt(rpc.call("eth_call",Map.of("to",a.token(),"data",balanceData),"latest").asText());
        return new FundingView(a.address(),a.token(),a.amount().toString(),
                TaskAccountArtifact.selector("approve(address,uint256)")+TaskAccountArtifact.word(a.address())+TaskAccountArtifact.word(a.amount()),
                TaskAccountArtifact.selector("fund(uint256)")+TaskAccountArtifact.word(a.amount()),balance.toString(),
                getterNumber(rpc.read(a.address(),"mandateApproved()")).equals(BigInteger.ONE));
    }
    public AccountView pay(UUID owner,UUID taskId) {
        enabled(); var a=owned(owner,taskId);
        if ("PAYMENT_UNKNOWN".equals(a.state()) || "PAID".equals(a.state()) || "FULFILLMENT_UNKNOWN".equals(a.state())
                || "COMPLETED".equals(a.state())) return view(a,false);
        if (!"APPROVED".equals(a.state())) throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,taskId);
        Order order=tasks.accountPaymentOrder(owner,taskId,a.attemptId());
        require(Instant.now().isBefore(a.expiresAt()));verifyApproved(a);
        require(order.amountBaseUnits().equals(a.amount()) && order.recipientAddress().equals(a.recipient()));
        require(new BigInteger(funding(owner,taskId).accountTokenBalanceBaseUnits()).compareTo(a.amount())>=0);
        String data=TaskAccountArtifact.selector("executePayment(bytes32,uint256)")+TaskAccountArtifact.word(TaskAccountCrypto.paymentId(a.attemptId()))
                +TaskAccountArtifact.word(a.amount());
        var op=reserve(owner,a,"PAYMENT",config.executorKey,data);sendOnce(op);
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
    private static final String PAYMENT_TOPIC=Numeric.toHexString(Hash.sha3(
            "PaymentExecuted(bytes32,bytes32,bytes32,address,address,uint256)".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private static final String TRANSFER_TOPIC=Numeric.toHexString(Hash.sha3(
            "Transfer(address,address,uint256)".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private static final String APPROVAL_TOPIC=Numeric.toHexString(Hash.sha3(
            "MandateApproved(bytes32,bytes32,bytes32,uint256)".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private static final String FULFILLMENT_TOPIC=Numeric.toHexString(Hash.sha3(
            "FulfillmentConfirmed(bytes32,bytes32,bytes32,address)".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private static String topicAddress(String address){return "0x"+TaskAccountArtifact.word(address);}
    private static boolean topics(JsonNode log,String... expected){JsonNode arr=log.path("topics");if(arr.size()!=expected.length)return false;
        for(int i=0;i<expected.length;i++)if(!expected[i].equalsIgnoreCase(arr.get(i).asText()))return false;return true;}
    private void verifyReceipt(TaskAccountRepository.Account a,TaskAccountRepository.Operation op,JsonNode receipt) {
        require("0x1".equals(receipt.path("status").asText()));
        require(op.hash().equalsIgnoreCase(receipt.path("transactionHash").asText()));
        require(a.address().equalsIgnoreCase(receipt.path("to").asText()));
        String mandateHash=rpc.read(a.address(),"mandateHash()");
        boolean accountEvent=false, transfer=false;
        for(JsonNode log:receipt.path("logs")) {
            if (a.address().equalsIgnoreCase(log.path("address").asText())) {
                if (op.kind().equals("APPROVAL") && topics(log,APPROVAL_TOPIC,a.chainTaskId(),mandateHash)
                        && log.path("data").asText().equalsIgnoreCase("0x"+TaskAccountArtifact.word(a.approvalDigest())
                        +TaskAccountArtifact.word(a.approvalNonce()))) accountEvent=true;
                if (op.kind().equals("PAYMENT") && topics(log,PAYMENT_TOPIC,a.chainTaskId(),mandateHash,a.paymentId())
                        && log.path("data").asText().equalsIgnoreCase("0x"+TaskAccountArtifact.word(a.token())
                        +TaskAccountArtifact.word(a.recipient())+TaskAccountArtifact.word(a.amount()))) accountEvent=true;
                if (op.kind().equals("FULFILLMENT") && topics(log,FULFILLMENT_TOPIC,a.chainTaskId(),a.paymentId(),
                        topicAddress(a.reporter())) && log.path("data").asText().equalsIgnoreCase("0x"+TaskAccountArtifact.word(a.evidenceHash()))) accountEvent=true;
            }
            if (op.kind().equals("PAYMENT") && a.token().equalsIgnoreCase(log.path("address").asText())
                    && topics(log,TRANSFER_TOPIC,topicAddress(a.address()),topicAddress(a.recipient()))
                    && log.path("data").asText().equalsIgnoreCase("0x"+TaskAccountArtifact.word(a.amount()))) transfer=true;
        }
        require(accountEvent && (!op.kind().equals("PAYMENT") || transfer));
    }
    public AccountView reconcile(UUID owner,UUID taskId) {
        enabled(); var a=owned(owner,taskId);
        String kind=switch(a.state()){case "APPROVAL_UNKNOWN"->"APPROVAL";case "PAYMENT_UNKNOWN"->"PAYMENT";
            case "FULFILLMENT_UNKNOWN"->"FULFILLMENT";default->null;};
        if(kind==null)return view(a,false);
        var op=repo.operation(a.id(),kind).orElse(null);if(op==null)return view(a,false);
        JsonNode receipt=rpc.receipt(op.hash());if(receipt.isNull())return view(a,false);
        if(!"0x1".equals(receipt.path("status").asText())) {
            transactions.executeWithoutResult(s->repo.operationState(op.id(),"REVERTED"));
            throw new ApiException(ErrorCode.CHAIN_NOT_READY,taskId);
        }
        try {verifyAccount(a);verifyReceipt(a,op,receipt);} catch(ApiException mismatch){
            transactions.executeWithoutResult(s->repo.operationState(op.id(),"MISMATCH"));throw mismatch;
        }
        transactions.executeWithoutResult(s->{
            tasks.get(owner,taskId);
            var current=repo.lock(taskId).orElseThrow();
            if(!current.state().equals(a.state()))return;
            if(kind.equals("APPROVAL"))repo.state(a.id(),"APPROVAL_UNKNOWN","APPROVED");
            if(kind.equals("PAYMENT")) {repo.paid(a.id());taskRepo.updateOrderPayment(a.attemptId(),"PAID");}
            if(kind.equals("FULFILLMENT")) {repo.completed(a.id());tasks.completeAccount(owner,taskId);}
            repo.operationState(op.id(),"VERIFIED");
            taskRepo.appendEvent(taskId,a.attemptId(),kind+"_VERIFIED",current.state(),null,"server",
                    Map.of("transactionHash",op.hash()));
        });
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
    public AccountView fulfill(UUID owner,UUID taskId) {
        enabled();var a=owned(owner,taskId);
        if(!"PAID".equals(a.state())) return view(a,false);
        require(a.paymentVerifiedAt()!=null);
        verifyAccount(a);
        require(getterNumber(rpc.read(a.address(),"paymentExecuted()")).equals(BigInteger.ONE));
        require(rpc.read(a.address(),"paymentId()").equals(a.paymentId()));
        require(getterNumber(rpc.read(a.address(),"paidAmount()")).equals(a.amount()));
        require(getterNumber(rpc.read(a.address(),"fulfillmentConfirmed()")).equals(BigInteger.ZERO));
        var order=taskRepo.orders(taskId).stream().filter(o->o.attemptId().equals(a.attemptId())).findFirst().orElseThrow();
        var f=pharmacies.fulfill(order.externalOrderId());
        String evidence=TaskAccountCrypto.evidenceHash(f.orderId(),f.reference());
        String data=TaskAccountArtifact.selector("confirmFulfillment(bytes32,bytes32)")+TaskAccountArtifact.word(a.paymentId())
                +TaskAccountArtifact.word(evidence);
        var op=reserve(owner,a,"FULFILLMENT",config.reporterKey,data);sendOnce(op);
        return view(repo.byTask(taskId).orElseThrow(),false);
    }
}
