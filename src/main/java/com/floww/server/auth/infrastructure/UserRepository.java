package com.floww.server.auth.infrastructure;

import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String COLUMNS =
            "id, email, password_hash, role, status, provider, display_name, created_at, updated_at";

    private final JdbcTemplate db;

    public UserRepository(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * status=ACTIVE, provider=EMAIL.
     * @throws ApiException EMAIL_ALREADY_EXISTS
     */
    public User save(String email, String passwordHash, UserRole role, String displayName) {
        List<User> rows;
        try {
            rows = db.query("""
                    INSERT INTO users (id, email, password_hash, role, status, provider, display_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT users_email_key DO NOTHING
                    """ + "RETURNING " + COLUMNS,
                    MAPPER,
                    UUID.randomUUID(), email, passwordHash, role.name(),
                    UserStatus.ACTIVE.name(), AuthProvider.EMAIL.name(), displayName);
        } catch (DataIntegrityViolationException e) {
            throw sanitized(e);
        }
        if (rows.isEmpty()) throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        return rows.get(0);
    }

    public Optional<User> findByEmail(String email) {
        return first(db.query("SELECT " + COLUMNS + " FROM users WHERE email = ?", MAPPER, email));
    }

    public Optional<User> findById(UUID id) {
        return first(db.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", MAPPER, id));
    }

    /** 가입 전 중복 확인용. 최종 중복 판단은 {@link #save}의 제약이 한다 (동시 가입 대비). */
    public boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)", Boolean.class, email));
    }

    /** 9번 어드민 계정 생성: ADMIN이 이미 있는지 확인한다. */
    public boolean existsByRole(UserRole role) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE role = ?)", Boolean.class, role.name()));
    }

    private static Optional<User> first(List<User> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 원인 예외(DETAIL에 행 전체 포함)를 버리고 SQLState만 남긴다. */
    private static IllegalStateException sanitized(DataIntegrityViolationException e) {
        String state = e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : "unknown";
        return new IllegalStateException("users write violated a constraint (SQLState " + state + ")");
    }

    private static final RowMapper<User> MAPPER = (ResultSet rs, int rowNum) -> new User(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            UserRole.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")),
            AuthProvider.valueOf(rs.getString("provider")),
            rs.getString("display_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}