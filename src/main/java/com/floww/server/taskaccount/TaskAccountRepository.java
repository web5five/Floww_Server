package com.floww.server.taskaccount;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskAccountRepository {
    private final JdbcTemplate db;
    public TaskAccountRepository(JdbcTemplate db) { this.db=db; }
    public record Account(UUID id, UUID taskId, UUID attemptId, UUID mandateId, int mandateVersion, UUID quoteId,
                          String owner, String address, String deployTx, long chainId, String chainTaskId,
                          String reviewDigest, String reviewTypedData, String token, String recipient,
                          String executor, String reporter, BigInteger amount, Instant quoteExpiresAt,
                          Instant expiresAt, String state, BigInteger approvalNonce, String approvalDigest,
                          String approvalSignature, String paymentId, String paymentTx, Instant paymentVerifiedAt,
                          String fulfillmentId, String evidenceHash, String fulfillmentTx, Instant fulfillmentVerifiedAt) { }
    public record Operation(UUID id, UUID accountId, String kind, String signer, BigInteger nonce,
                            String raw, String hash, String state) { }
    private static final String COLUMNS = "id,task_id,attempt_id,mandate_id,mandate_version,quote_id,owner_address,"
            +"account_address,deploy_tx_hash,chain_id,chain_task_id,review_digest,review_typed_data,token_address,"
            +"recipient_address,executor_address,reporter_address,amount_base_units,quote_expires_at,expires_at,"
            +"state,approval_nonce,approval_digest,approval_signature,payment_id,payment_tx_hash,payment_verified_at,"
            +"fulfillment_id,fulfillment_evidence_hash,fulfillment_tx_hash,fulfillment_verified_at";
    private static Account map(ResultSet r, int n) throws SQLException {
        return new Account(r.getObject("id", UUID.class),r.getObject("task_id",UUID.class),
                r.getObject("attempt_id",UUID.class),r.getObject("mandate_id",UUID.class),r.getInt("mandate_version"),
                r.getObject("quote_id",UUID.class),r.getString("owner_address"),r.getString("account_address"),
                r.getString("deploy_tx_hash"),r.getLong("chain_id"),r.getString("chain_task_id"),
                r.getString("review_digest"),r.getString("review_typed_data"),r.getString("token_address"),
                r.getString("recipient_address"),r.getString("executor_address"),r.getString("reporter_address"),
                number(r,"amount_base_units"),instant(r,"quote_expires_at"),instant(r,"expires_at"),r.getString("state"),
                number(r,"approval_nonce"),r.getString("approval_digest"),r.getString("approval_signature"),
                r.getString("payment_id"),r.getString("payment_tx_hash"),instant(r,"payment_verified_at"),
                r.getString("fulfillment_id"),r.getString("fulfillment_evidence_hash"),r.getString("fulfillment_tx_hash"),
                instant(r,"fulfillment_verified_at"));
    }
    private static BigInteger number(ResultSet r,String c) throws SQLException { BigDecimal x=r.getBigDecimal(c);return x==null?null:x.toBigIntegerExact(); }
    private static Instant instant(ResultSet r,String c) throws SQLException {Timestamp x=r.getTimestamp(c);return x==null?null:x.toInstant();}
    public Optional<Account> byTask(UUID task) {return db.query("SELECT "+COLUMNS+" FROM task_accounts WHERE task_id=?",TaskAccountRepository::map,task).stream().findFirst();}
    public Optional<Account> lock(UUID task) {return db.query("SELECT "+COLUMNS+" FROM task_accounts WHERE task_id=? FOR UPDATE",TaskAccountRepository::map,task).stream().findFirst();}
    public void insert(Account a) {
        db.update("INSERT INTO task_accounts(id,task_id,attempt_id,mandate_id,mandate_version,quote_id,owner_address,"
                +"chain_id,chain_task_id,review_digest,review_typed_data,token_address,recipient_address,executor_address,"
                +"reporter_address,amount_base_units,quote_expires_at,expires_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?)",
                a.id(),a.taskId(),a.attemptId(),a.mandateId(),a.mandateVersion(),a.quoteId(),a.owner(),a.chainId(),
                a.chainTaskId(),a.reviewDigest(),a.reviewTypedData(),a.token(),a.recipient(),a.executor(),a.reporter(),
                new BigDecimal(a.amount()),Timestamp.from(a.quoteExpiresAt()),Timestamp.from(a.expiresAt()),a.state());
    }
    public boolean bind(UUID id,String address,String tx) {return db.update("UPDATE task_accounts SET account_address=?,deploy_tx_hash=?,state='BOUND',updated_at=now() WHERE id=? AND state='PREPARED'",address,tx,id)==1;}
    public boolean signed(UUID id,BigInteger nonce,String digest,String sig) {return db.update("UPDATE task_accounts SET approval_nonce=?,approval_digest=?,approval_signature=?,state='SIGNED',updated_at=now() WHERE id=? AND state='BOUND'",new BigDecimal(nonce),digest,sig,id)==1;}
    public boolean state(UUID id,String from,String to) {return db.update("UPDATE task_accounts SET state=?,updated_at=now() WHERE id=? AND state=?",to,id,from)==1;}
    public void payment(UUID id,String paymentId,String hash) {db.update("UPDATE task_accounts SET payment_id=?,payment_tx_hash=?,state='PAYMENT_UNKNOWN',updated_at=now() WHERE id=? AND state='APPROVED'",paymentId,hash,id);}
    public void paid(UUID id) {db.update("UPDATE task_accounts SET state='PAID',payment_verified_at=now(),updated_at=now() WHERE id=? AND state='PAYMENT_UNKNOWN'",id);}
    public void fulfillment(UUID id,String fulfillmentId,String evidence,String hash) {db.update("UPDATE task_accounts SET fulfillment_id=?,fulfillment_evidence_hash=?,fulfillment_tx_hash=?,state='FULFILLMENT_UNKNOWN',updated_at=now() WHERE id=? AND state='PAID'",fulfillmentId,evidence,hash,id);}
    public void completed(UUID id) {db.update("UPDATE task_accounts SET state='COMPLETED',fulfillment_verified_at=now(),updated_at=now() WHERE id=? AND state='FULFILLMENT_UNKNOWN'",id);}
    public Optional<Operation> operation(UUID account,String kind) {return db.query("SELECT id,account_id,kind,signer_address,signer_nonce,raw_transaction,tx_hash,state FROM task_account_operations WHERE account_id=? AND kind=?",(r,n)->new Operation(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),number(r,"signer_nonce"),r.getString(6),r.getString(7),r.getString(8)),account,kind).stream().findFirst();}
    public void operation(Operation o) {db.update("INSERT INTO task_account_operations(id,account_id,kind,signer_address,signer_nonce,raw_transaction,tx_hash,state) VALUES(?,?,?,?,?,?,?,'UNKNOWN')",o.id(),o.accountId(),o.kind(),o.signer(),new BigDecimal(o.nonce()),o.raw(),o.hash());}
    public void operationState(UUID id,String state) {db.update("UPDATE task_account_operations SET state=?,updated_at=now() WHERE id=?",state,id);}
    public BigInteger reserveNonce(String signer,BigInteger chainPending) {
        db.update("INSERT INTO task_account_signer_nonces(signer_address,next_nonce) VALUES(?,?) ON CONFLICT DO NOTHING",signer,new BigDecimal(chainPending));
        BigInteger current=db.queryForObject("SELECT next_nonce FROM task_account_signer_nonces WHERE signer_address=? FOR UPDATE",BigDecimal.class,signer).toBigIntegerExact();
        BigInteger nonce=current.max(chainPending);
        db.update("UPDATE task_account_signer_nonces SET next_nonce=? WHERE signer_address=?",new BigDecimal(nonce.add(BigInteger.ONE)),signer);
        return nonce;
    }
}
