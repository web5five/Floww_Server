package com.floww.server.auth.wallet;

import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.web3j.crypto.Keys;

@Repository
class WalletSigninRepository {
    record Challenge(String nonce, String address, long chainId, String message, Instant expiresAt, boolean consumed) { }
    record Wallet(UUID walletId, String address, String walletType, boolean primary) { }
    record FoundUser(User user, boolean isNew) { }

    private static final String USER_COLUMNS = "u.id, u.email, u.password_hash, u.role, u.status, "
            + "u.provider, u.display_name, u.created_at, u.updated_at";
    private final JdbcTemplate db;

    WalletSigninRepository(JdbcTemplate db) { this.db = db; }

    void lockAddress(String address) {
        // Transaction-scoped lock serializes issuance and first account creation across instances.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", address);
    }

    void insertChallenge(Challenge challenge) {
        // All issuers serialize admission across app instances before counting live rows.
        db.queryForList("SELECT pg_advisory_xact_lock(72692024018)");
        db.update("DELETE FROM wallet_login_challenges WHERE expires_at <= clock_timestamp()");
        Integer total = db.queryForObject("SELECT count(*) FROM wallet_login_challenges", Integer.class);
        if (total != null && total >= 10_000) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        Integer active = db.queryForObject("SELECT count(*) FROM wallet_login_challenges "
                + "WHERE address = ? AND consumed_at IS NULL", Integer.class, challenge.address());
        if (active != null && active >= 5) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        db.update("INSERT INTO wallet_login_challenges (nonce,address,chain_id,message,expires_at) "
                        + "VALUES (?,?,?,?,?)", challenge.nonce(), challenge.address(), challenge.chainId(),
                challenge.message(), java.sql.Timestamp.from(challenge.expiresAt()));
    }

    Optional<Challenge> challenge(String nonce) {
        return db.query("SELECT nonce,address,chain_id,message,expires_at,consumed_at "
                + "FROM wallet_login_challenges WHERE nonce = ?", (rs, row) -> new Challenge(
                rs.getString("nonce"), rs.getString("address"), rs.getLong("chain_id"),
                rs.getString("message"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") != null), nonce).stream().findFirst();
    }

    boolean consume(String nonce) {
        return db.update("UPDATE wallet_login_challenges SET consumed_at = clock_timestamp() "
                + "WHERE nonce = ? AND consumed_at IS NULL AND expires_at > clock_timestamp()", nonce) == 1;
    }

    FoundUser findOrCreate(String address) {
        List<User> found = db.query("SELECT " + USER_COLUMNS + " FROM users u JOIN wallet_identities w "
                + "ON w.user_id = u.id WHERE w.address = ? FOR UPDATE OF u", USER_MAPPER, address);
        if (!found.isEmpty()) return new FoundUser(found.get(0), false);
        UUID id = UUID.randomUUID();
        try {
            db.update("INSERT INTO users(id,email,password_hash,role,status,provider) "
                            + "VALUES (?,NULL,NULL,'USER','ACTIVE','WALLET')", id);
            db.update("INSERT INTO wallet_identities(id,user_id,address,wallet_type,is_primary) "
                            + "VALUES (?,?,?,'EXTERNAL',true)", UUID.randomUUID(), id, address);
        } catch (DataIntegrityViolationException e) {
            String state = e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : "unknown";
            throw new IllegalStateException("wallet identity write violated a constraint (SQLState " + state + ")");
        }
        return new FoundUser(db.query("SELECT " + USER_COLUMNS + " FROM users u WHERE u.id = ?",
                USER_MAPPER, id).get(0), true);
    }

    List<Wallet> wallets(UUID userId) {
        return db.query("SELECT id,address,wallet_type,is_primary FROM wallet_identities "
                + "WHERE user_id = ? ORDER BY created_at,id", (rs, row) -> new Wallet(
                rs.getObject("id", UUID.class), Keys.toChecksumAddress(rs.getString("address")),
                rs.getString("wallet_type"), rs.getBoolean("is_primary")), userId);
    }

    private static final RowMapper<User> USER_MAPPER = (ResultSet rs, int rowNum) -> new User(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
            UserRole.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("status")),
            AuthProvider.valueOf(rs.getString("provider")), rs.getString("display_name"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
}
