package com.springtechkb.user.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AuthService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final String dummyPasswordHash;
    private final String issuer;
    private final Duration accessTokenTtl;

    AuthService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${springtechkb.security.jwt.issuer:spring-tech-kb}") String issuer,
            @Value("${springtechkb.security.jwt.access-token-ttl:15m}") Duration accessTokenTtl) {
        if (issuer == null || issuer.isBlank() || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("Invalid JWT issuer or access-token TTL");
        }
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    @Transactional
    AuthResponse register(String email, String username, String password) {
        validateBcryptPassword(password);
        Account account;
        try {
            account = jdbc.queryForObject("""
                    INSERT INTO users (email, username, password_hash)
                    VALUES (?, ?, ?)
                    RETURNING id, email, username, role
                    """, (rs, rowNum) -> new Account(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("username"),
                    rs.getString("role")),
                    email.trim().toLowerCase(Locale.ROOT),
                    username.trim().toLowerCase(Locale.ROOT),
                    passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email or username is already registered");
        }
        return issueToken(account);
    }

    AuthResponse login(String identifier, String password) {
        validateBcryptPassword(password);
        String normalizedIdentifier = identifier.trim().toLowerCase(Locale.ROOT);
        List<LoginAccount> accounts = jdbc.query("""
                SELECT id, email, username, password_hash, role, is_active
                FROM users
                WHERE lower(email) = ?
                LIMIT 1
                """, (rs, rowNum) -> new LoginAccount(
                new Account(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("username"),
                        rs.getString("role")),
                rs.getString("password_hash"),
                rs.getBoolean("is_active")), normalizedIdentifier);
        if (accounts.isEmpty()) {
            accounts = jdbc.query("""
                    SELECT id, email, username, password_hash, role, is_active
                    FROM users
                    WHERE lower(username) = ?
                    LIMIT 1
                    """, (rs, rowNum) -> new LoginAccount(
                    new Account(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("username"),
                            rs.getString("role")),
                    rs.getString("password_hash"),
                    rs.getBoolean("is_active")), normalizedIdentifier);
        }
        LoginAccount account = accounts.isEmpty() ? null : accounts.getFirst();
        boolean passwordMatches = passwordEncoder.matches(
                password, account == null ? dummyPasswordHash : account.passwordHash());
        if (account == null || !account.active() || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return issueToken(account.account());
    }

    PublicUser currentUser(UUID userId) {
        List<PublicUser> users = jdbc.query("""
                SELECT id, email, username, role
                FROM users
                WHERE id = ? AND is_active = TRUE
                """, (rs, rowNum) -> new PublicUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("role")), userId);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found");
        }
        return users.getFirst();
    }

    private AuthResponse issueToken(Account account) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(account.id().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("username", account.username())
                .claim("roles", List.of(account.role()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", expiresAt, account.publicUser());
    }

    private static void validateBcryptPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes");
        }
    }

    record AuthResponse(String accessToken, String tokenType, Instant expiresAt, PublicUser user) {}

    record PublicUser(UUID id, String email, String username, String role) {}

    private record Account(UUID id, String email, String username, String role) {
        PublicUser publicUser() {
            return new PublicUser(id, email, username, role);
        }
    }

    private record LoginAccount(Account account, String passwordHash, boolean active) {}
}
