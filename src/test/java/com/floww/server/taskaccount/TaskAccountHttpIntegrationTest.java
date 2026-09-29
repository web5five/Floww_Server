package com.floww.server.taskaccount;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/** Owner-JWT HTTP + V5 PostgreSQL + bounded loopback JSON-RPC. No live chain or user acceptance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "floww.auth.jwt.signing-key=test-only-signing-key-0123456789abcdef",
        "floww.auth.wallet.enabled=true", "floww.auth.wallet.origin=http://127.0.0.1:8080",
        "floww.auth.wallet.chain-ids=11155111", "floww.merchant.test-base-url=",
        "floww.taskaccount.enabled=true",
        "floww.merchant.pharmacy-a.recipient=0x00000000000000000000000000000000facea001"
})
class TaskAccountHttpIntegrationTest {
    private static final String EXEC_KEY="1".repeat(64), REPORT_KEY="2".repeat(64);
    private static final Credentials EXEC=Credentials.create(EXEC_KEY), REPORT=Credentials.create(REPORT_KEY);
    private static final String ACCOUNT=randomAddress();
    private static String randomAddress(){try{return Credentials.create(Keys.createEcKeyPair()).getAddress().toLowerCase();}
        catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Fixture FIXTURE=new Fixture();
    private static final HttpServer SERVER;
    static {
        try {
            SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SERVER.createContext("/",exchange->{
                try {
                    JsonNode req=JSON.readTree(exchange.getRequestBody());
                    Object result=FIXTURE.reply(req.path("method").asText(),req.path("params"));
                    var response=new java.util.HashMap<String,Object>();
                    response.put("jsonrpc","2.0");response.put("id",req.path("id").asLong());response.put("result",result);
                    byte[] body=JSON.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);
                    exchange.getResponseBody().write(body);
                } catch(Exception e) {
                    byte[] body="{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"fixture error\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } finally {exchange.close();}
            });
            SERVER.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @AfterAll static void stop(){SERVER.stop(0);}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("floww.taskaccount.rpc-url",()->"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/");
        r.add("floww.taskaccount.executor-key",()->EXEC_KEY);
        r.add("floww.taskaccount.reporter-key",()->REPORT_KEY);
        r.add("floww.taskaccount.executor-address",()->EXEC.getAddress());
        r.add("floww.taskaccount.reporter-address",()->REPORT.getAddress());
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired TaskAccountArtifact artifact;
    record Session(String token,Credentials wallet) { }
    private static String word(String v){return TaskAccountArtifact.word(v);}
    private static String word(long v){return TaskAccountArtifact.word(java.math.BigInteger.valueOf(v));}
    private static String topic(String s){return Numeric.toHexString(Hash.sha3(s.getBytes(StandardCharsets.UTF_8)));}
    private static Map<String,Object> log(String address,List<String> topics,String data){return Map.of("address",address,"topics",topics,"data",data);}
    private static Map<String,Object> receipt(String hash,List<Map<String,Object>> logs){return Map.of("status","0x1","transactionHash",hash,"to",ACCOUNT,"logs",logs);}
    private static final class Fixture {
        volatile String owner,taskId,review,token,recipient,expiry,approvalDigest,paymentId,evidenceHash;
        volatile boolean approved,paid,fulfilled,funded;
        volatile String deployHash,deployData;
        final Map<String,Object> receipts=new ConcurrentHashMap<>();
        final AtomicInteger sends=new AtomicInteger();
        final String mandateHash="0x"+"ab".repeat(32);
        final String runtime;
        Fixture() {
            try {runtime="0x"+JSON.readTree(TaskAccountHttpIntegrationTest.class.getResourceAsStream(
                    "/taskaccount/FlowwTaskAccount.json")).path("artifact").path("evm")
                    .path("deployedBytecode").path("object").asText();}
            catch(Exception e){throw new ExceptionInInitializerError(e);}
        }
        Object reply(String method,JsonNode params) {
            return switch(method) {
                case "eth_chainId" -> "0xaa36a7";
                case "eth_getCode" -> runtime;
                case "eth_getTransactionReceipt" -> receipts.getOrDefault(params.get(0).asText(),"__null__").equals("__null__")
                        ? null : receipts.get(params.get(0).asText());
                case "eth_getTransactionByHash" -> {
                    var tx=new java.util.HashMap<String,Object>();tx.put("from",owner);tx.put("to",null);
                    tx.put("input",deployData);yield tx;
                }
                case "eth_getTransactionCount" -> "0x0";
                case "eth_gasPrice" -> "0x1";
                case "eth_sendRawTransaction" -> {
                    sends.incrementAndGet();yield Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(params.get(0).asText())));
                }
                case "eth_call" -> call(params.get(0).path("data").asText());
                default -> throw new IllegalArgumentException(method);
            };
        }
        String call(String data) {
            String s=data.substring(0,10);
            if(s.equals(TaskAccountArtifact.selector("balanceOf(address)")))return "0x"+word(funded?23_500_000:0);
            String[][] values={{"owner()",owner},{"taskId()",taskId},{"reviewSnapshotDigest()",review},
                    {"token()",token},{"recipient()",recipient},{"executor()",EXEC.getAddress()},
                    {"fulfillmentReporter()",REPORT.getAddress()},{"maxSpend()","0x"+word(23_500_000)},
                    {"expiresAt()",expiry},{"revoked()","0x"+word(0)},
                    {"mandateApproved()","0x"+word(approved?1:0)},
                    {"authorizationNonce()","0x"+word(approved?1:0)},
                    {"mandateApprovalDigest()",approvalDigest},{"approvedDigest()",approvalDigest},
                    {"paymentExecuted()","0x"+word(paid?1:0)},{"isActive()","0x"+word(approved&&!paid?1:0)},
                    {"mandateHash()",mandateHash},{"paymentId()",paymentId},{"paidAmount()","0x"+word(paid?23_500_000:0)},
                    {"fulfillmentConfirmed()","0x"+word(fulfilled?1:0)}};
            for(String[] v:values)if(s.equals(TaskAccountArtifact.selector(v[0]))) {
                if(v[1]==null) return "0x"+word(0);
                if(v[1].length()==42)return "0x"+word(v[1]);
                return v[1];
            }
            throw new IllegalArgumentException(s);
        }
    }
    private ResponseEntity<String> call(HttpMethod method,String path,Session session,String key,Object body)throws Exception {
        HttpHeaders h=new HttpHeaders();if(session!=null)h.setBearerAuth(session.token());
        if(key!=null)h.set("Idempotency-Key",key);
        String encoded=body==null?null:JSON.writeValueAsString(body);
        if(encoded!=null)h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(encoded,h),String.class);
    }
    private JsonNode ok(HttpMethod method,String path,Session s,String key,Object body,int status)throws Exception {
        var r=call(method,path,s,key,body);assertEquals(status,r.getStatusCode().value(),r.getBody());return JSON.readTree(r.getBody());
    }
    private Session login()throws Exception {
        Credentials key=Credentials.create(Keys.createEcKeyPair());
        JsonNode challenge=ok(HttpMethod.POST,"/api/v1/auth/wallet/nonce",null,null,
                Map.of("address",key.getAddress(),"chainId",11155111),200);
        String message=challenge.path("message").asText();
        Sign.SignatureData sig=Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),key.getEcKeyPair());
        byte[] bytes=new byte[65];System.arraycopy(sig.getR(),0,bytes,0,32);System.arraycopy(sig.getS(),0,bytes,32,32);bytes[64]=sig.getV()[0];
        JsonNode jwt=ok(HttpMethod.POST,"/api/v1/auth/wallet/verify",null,null,
                Map.of("message",message,"signature",Numeric.toHexString(bytes)),200);
        return new Session(jwt.path("accessToken").asText(),key);
    }
    private static JsonNode quote(JsonNode quotes,String merchant) {
        for(JsonNode q:quotes)if(merchant.equals(q.path("merchantId").asText()))return q;
        throw new AssertionError(merchant);
    }
    @Test void selectedPurchaseAndUnknownPaymentReconcileToSimulatedCompletion() throws Exception {
        Session owner=login(),stranger=login();
        JsonNode task=ok(HttpMethod.POST,"/api/v1/tasks",owner,UUID.randomUUID().toString(),Map.of(
                "goal","Buy one acetaminophen","itemId","acetaminophen-500mg-10",
                "maxAmountBaseUnits","60000000","expiresAt",Instant.now().plusSeconds(86400).toString()),201);
        String id=task.path("taskId").asText(),prefix="/api/v1/tasks/"+id;
        JsonNode quotes=ok(HttpMethod.POST,prefix+"/quotes",owner,null,null,200).path("quotes");
        for(String merchant:List.of("pharmacy-b","pharmacy-c")) {
            JsonNode denied=ok(HttpMethod.POST,prefix+"/attempts",owner,null,
                    Map.of("quoteId",quote(quotes,merchant).path("quoteId").asText(),"proposedBy","AI"),201);
            assertEquals("DENY",denied.path("policy").path("decision").asText());
            assertEquals(0,FIXTURE.sends.get());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM task_accounts WHERE task_id=?::uuid",Integer.class,id));
        }
        JsonNode selected=quote(quotes,"pharmacy-a");
        JsonNode allowed=ok(HttpMethod.POST,prefix+"/attempts",owner,null,
                Map.of("quoteId",selected.path("quoteId").asText(),"proposedBy","AI"),201);
        String attempt=allowed.path("attemptId").asText();
        JsonNode prepared=ok(HttpMethod.POST,prefix+"/account/prepare",owner,null,
                Map.of("attemptId",attempt,"ownerAddress",owner.wallet().getAddress()),200);
        assertEquals("PREPARED",prepared.path("state").asText());
        assertEquals("23500000",prepared.path("amountBaseUnits").asText());
        var blocked=call(HttpMethod.POST,prefix+"/account/prepare",stranger,null,
                Map.of("attemptId",attempt,"ownerAddress",owner.wallet().getAddress()));
        assertEquals(404,blocked.getStatusCode().value());
        FIXTURE.owner=owner.wallet().getAddress().toLowerCase();FIXTURE.taskId=prepared.path("chainTaskId").asText();
        FIXTURE.review=prepared.path("reviewSnapshotDigest").asText();FIXTURE.token=prepared.path("tokenAddress").asText();
        FIXTURE.recipient=prepared.path("recipientAddress").asText();
        FIXTURE.expiry="0x"+word(prepared.path("expiresAt").isTextual()?Instant.parse(prepared.path("expiresAt").asText()).getEpochSecond():0);
        FIXTURE.deployHash=topic(UUID.randomUUID().toString());FIXTURE.deployData=prepared.path("deploymentData").asText();
        FIXTURE.receipts.put(FIXTURE.deployHash,Map.of("status","0x1","contractAddress",ACCOUNT));
        JsonNode bound=ok(HttpMethod.POST,prefix+"/account/bind",owner,null,
                Map.of("accountAddress",ACCOUNT,"deploymentTxHash",FIXTURE.deployHash),200);
        assertEquals("BOUND",bound.path("state").asText());
        var message=new TaskAccountCrypto.Approval(FIXTURE.owner,ACCOUNT,FIXTURE.taskId,FIXTURE.review,FIXTURE.token,
                FIXTURE.recipient,EXEC.getAddress().toLowerCase(),REPORT.getAddress().toLowerCase(),
                java.math.BigInteger.valueOf(23_500_000),Instant.parse(prepared.path("expiresAt").asText()).getEpochSecond(),
                java.math.BigInteger.ZERO,11155111);
        FIXTURE.approvalDigest=TaskAccountCrypto.digest(TaskAccountCrypto.approvalTypedData(message));
        JsonNode request=ok(HttpMethod.POST,prefix+"/account/approval-request",owner,null,Map.of(),200);
        assertEquals(FIXTURE.approvalDigest,request.path("digest").asText());
        byte[] digest=new StructuredDataEncoder(JSON.writeValueAsString(request.path("typedData"))).hashStructuredData();
        Sign.SignatureData signed=Sign.signMessage(digest,owner.wallet().getEcKeyPair(),false);
        byte[] signature=new byte[65];System.arraycopy(signed.getR(),0,signature,0,32);
        System.arraycopy(signed.getS(),0,signature,32,32);signature[64]=signed.getV()[0];
        JsonNode signedAccount=ok(HttpMethod.POST,prefix+"/account/signature",owner,null,
                Map.of("signature",Numeric.toHexString(signature)),200);
        assertEquals("SIGNED",signedAccount.path("state").asText());
        assertEquals("ACTIVE",ok(HttpMethod.GET,prefix,owner,null,null,200).path("status").asText());
        JsonNode pending=ok(HttpMethod.POST,prefix+"/account/approve",owner,null,Map.of(),200);
        assertEquals("APPROVAL_UNKNOWN",pending.path("state").asText());
        assertEquals("UNKNOWN",pending.path("approvalOperationState").asText());
        String approvalHash=db.queryForObject("SELECT tx_hash FROM task_account_operations WHERE kind='APPROVAL' AND account_id=(SELECT id FROM task_accounts WHERE task_id=?::uuid)",String.class,id);
        assertEquals(1,FIXTURE.sends.get());
        ok(HttpMethod.POST,prefix+"/account/approve",owner,null,Map.of(),200);
        assertEquals(1,FIXTURE.sends.get());
        FIXTURE.receipts.put(approvalHash,receipt(approvalHash,List.of(log(ACCOUNT,List.of(topic("MandateApproved(bytes32,bytes32,bytes32,uint256)"),
                FIXTURE.taskId,FIXTURE.mandateHash),"0x"+word(FIXTURE.approvalDigest)+word(0)))));
        FIXTURE.approved=true;
        assertEquals("APPROVED",ok(HttpMethod.POST,prefix+"/account/reconcile",owner,null,Map.of(),200).path("state").asText());
        JsonNode funding=ok(HttpMethod.GET,prefix+"/account/funding",owner,null,null,200);
        assertEquals("0",funding.path("accountTokenBalanceBaseUnits").asText());
        FIXTURE.funded=true;
        JsonNode order=ok(HttpMethod.POST,prefix+"/orders",owner,UUID.randomUUID().toString(),
                Map.of("attemptId",attempt),201);
        assertEquals("NOT_ATTEMPTED",order.path("paymentStatus").asText());
        // A stale trusted recipient snapshot is rejected at the final signer seam.
        db.update("UPDATE merchant_quotes SET registry_recipient_address='0x00000000000000000000000000000000faceb999' WHERE task_id=?::uuid AND merchant_id='pharmacy-a'",id);
        var stale=call(HttpMethod.POST,prefix+"/account/payment",owner,null,Map.of());
        assertEquals(409,stale.getStatusCode().value());
        assertEquals(1,FIXTURE.sends.get());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM task_account_operations WHERE kind='PAYMENT' AND account_id=(SELECT id FROM task_accounts WHERE task_id=?::uuid)",Integer.class,id));
        db.update("UPDATE merchant_quotes SET registry_recipient_address=? WHERE task_id=?::uuid AND merchant_id='pharmacy-a'",
                FIXTURE.recipient,id);
        // Exact amount is a backend invariant: reject a one-unit-short order before reserve/sign/send.
        db.update("UPDATE merchant_orders SET amount_base_units=?::numeric WHERE task_id=?::uuid",
                java.math.BigInteger.valueOf(23_499_999),id);
        var wrongAmount=call(HttpMethod.POST,prefix+"/account/payment",owner,null,Map.of());
        assertEquals(409,wrongAmount.getStatusCode().value());
        assertEquals(1,FIXTURE.sends.get());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM task_account_operations WHERE kind='PAYMENT' AND account_id=(SELECT id FROM task_accounts WHERE task_id=?::uuid)",Integer.class,id));
        assertEquals("NOT_ATTEMPTED",db.queryForObject("SELECT payment_status FROM merchant_orders WHERE task_id=?::uuid",String.class,id));
        JsonNode noPaymentHash=ok(HttpMethod.GET,prefix+"/account",owner,null,null,200);
        assertTrue(noPaymentHash.path("paymentTxHash").isMissingNode() || noPaymentHash.path("paymentTxHash").isNull());
        db.update("UPDATE merchant_orders SET amount_base_units=?::numeric WHERE task_id=?::uuid",
                java.math.BigInteger.valueOf(23_500_000),id);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        JsonNode unknown;
        try {
            var first=pool.submit(()->call(HttpMethod.POST,prefix+"/account/payment",owner,null,Map.of()));
            var second=pool.submit(()->call(HttpMethod.POST,prefix+"/account/payment",owner,null,Map.of()));
            var r1=first.get();var r2=second.get();
            assertTrue((r1.getStatusCode().value()==200 && r2.getStatusCode().value()==200)
                    || (r1.getStatusCode().value()==200 && r2.getStatusCode().value()==409)
                    || (r1.getStatusCode().value()==409 && r2.getStatusCode().value()==200));
            unknown=JSON.readTree(r1.getStatusCode().value()==200?r1.getBody():r2.getBody());
        } finally {pool.shutdownNow();}
        assertEquals("PAYMENT_UNKNOWN",unknown.path("state").asText());
        assertEquals(2,FIXTURE.sends.get());
        assertEquals("UNKNOWN",db.queryForObject("SELECT payment_status FROM merchant_orders WHERE task_id=?::uuid",String.class,id));
        ok(HttpMethod.POST,prefix+"/account/payment",owner,null,Map.of(),200);
        assertEquals(2,FIXTURE.sends.get());
        String paymentId=unknown.path("paymentId").asText(),paymentHash=unknown.path("paymentTxHash").asText();
        FIXTURE.paymentId=paymentId;
        // A reverted or event-mismatched receipt cannot create PAID or trigger another payment.
        FIXTURE.receipts.put(paymentHash,Map.of("status","0x0","transactionHash",paymentHash,"to",ACCOUNT,"logs",List.of()));
        assertEquals(409,call(HttpMethod.POST,prefix+"/account/reconcile",owner,null,Map.of()).getStatusCode().value());
        assertEquals("UNKNOWN",db.queryForObject("SELECT payment_status FROM merchant_orders WHERE task_id=?::uuid",String.class,id));
        assertEquals("REVERTED",ok(HttpMethod.GET,prefix+"/account",owner,null,null,200).path("paymentOperationState").asText());
        assertEquals(2,FIXTURE.sends.get());
        FIXTURE.receipts.put(paymentHash,receipt(paymentHash,List.of()));
        assertEquals(409,call(HttpMethod.POST,prefix+"/account/reconcile",owner,null,Map.of()).getStatusCode().value());
        assertEquals("UNKNOWN",db.queryForObject("SELECT payment_status FROM merchant_orders WHERE task_id=?::uuid",String.class,id));
        assertEquals("MISMATCH",ok(HttpMethod.GET,prefix+"/account",owner,null,null,200).path("paymentOperationState").asText());
        assertEquals("EXECUTING",ok(HttpMethod.GET,prefix,owner,null,null,200).path("status").asText());
        FIXTURE.receipts.put(paymentHash,receipt(paymentHash,List.of(
                log(ACCOUNT,List.of(topic("PaymentExecuted(bytes32,bytes32,bytes32,address,address,uint256)"),
                        FIXTURE.taskId,FIXTURE.mandateHash,paymentId),"0x"+word(FIXTURE.token)+word(FIXTURE.recipient)+word(23_500_000)),
                log(FIXTURE.token,List.of(topic("Transfer(address,address,uint256)"),"0x"+word(ACCOUNT),"0x"+word(FIXTURE.recipient)),
                        "0x"+word(23_500_000)))));
        FIXTURE.paid=true;
        assertEquals("PAID",ok(HttpMethod.POST,prefix+"/account/reconcile",owner,null,Map.of(),200).path("state").asText());
        assertEquals("PAID",db.queryForObject("SELECT payment_status FROM merchant_orders WHERE task_id=?::uuid",String.class,id));
        JsonNode unknownFulfillment=ok(HttpMethod.POST,prefix+"/account/fulfillment",owner,null,Map.of(),200);
        assertEquals("FULFILLMENT_UNKNOWN",unknownFulfillment.path("state").asText());
        assertEquals("local_pharmacy_simulator",unknownFulfillment.path("fulfillmentEvidenceMode").asText());
        String fhash=unknownFulfillment.path("fulfillmentTxHash").asText();FIXTURE.evidenceHash=unknownFulfillment.path("fulfillmentEvidenceHash").asText();
        FIXTURE.receipts.put(fhash,receipt(fhash,List.of(log(ACCOUNT,List.of(topic("FulfillmentConfirmed(bytes32,bytes32,bytes32,address)"),
                FIXTURE.taskId,paymentId,"0x"+word(REPORT.getAddress())),"0x"+word(FIXTURE.evidenceHash)))));
        FIXTURE.fulfilled=true;
        assertEquals("COMPLETED",ok(HttpMethod.POST,prefix+"/account/reconcile",owner,null,Map.of(),200).path("state").asText());
        assertEquals("COMPLETED",ok(HttpMethod.GET,prefix,owner,null,null,200).path("status").asText());
    }
}
