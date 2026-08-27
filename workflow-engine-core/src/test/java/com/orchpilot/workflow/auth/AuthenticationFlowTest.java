package com.orchpilot.workflow.auth;

import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditLog;
import com.orchpilot.workflow.audit.SecurityAuditLogRepository;
import com.orchpilot.workflow.audit.SecurityAuditService;
import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.config.JwtConfig;
import com.orchpilot.workflow.auth.dto.ChangePasswordRequest;
import com.orchpilot.workflow.auth.dto.LoginRequest;
import com.orchpilot.workflow.auth.dto.RegisterRequest;
import com.orchpilot.workflow.auth.model.LoginAttemptCounter;
import com.orchpilot.workflow.auth.model.RefreshToken;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.LoginAttemptRepository;
import com.orchpilot.workflow.auth.repository.RefreshTokenRepository;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.CustomUserDetailsService;
import com.orchpilot.workflow.auth.service.AuthenticationException;
import com.orchpilot.workflow.auth.service.AuthenticationService;
import com.orchpilot.workflow.auth.service.DuplicateAccountException;
import com.orchpilot.workflow.auth.service.JwtService;
import com.orchpilot.workflow.auth.service.LoginThrottleService;
import com.orchpilot.workflow.auth.service.PasswordPolicyException;
import com.orchpilot.workflow.auth.service.PasswordService;
import com.orchpilot.workflow.auth.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Registration, login, refresh rotation, logout and password change, end to end through the services.
 *
 * <p>Uses hand-written in-memory repositories rather than mocks for the three collections involved. The
 * behaviour under test is largely about state transitions, revoked tokens, incremented counters, rotated
 * families, and a mock that returns whatever it was told would assert nothing about them.
 */
class AuthenticationFlowTest {

    private static final String STRONG_PASSWORD = "Tr0ubador-Zebra!x";

    private InMemoryUsers users;
    private InMemoryRefreshTokens tokens;
    private InMemoryLoginAttempts attempts;
    private RecordingAudit audit;
    private AuthenticationService authentication;
    private RefreshTokenService refreshTokens;
    private LoginThrottleService throttle;
    private PasswordService passwords;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getPassword().setMemoryKb(1024);
        properties.getPassword().setIterations(1);
        properties.getJwt().setSecret(Base64.getEncoder().encodeToString(
                "a-test-signing-secret-that-is-long-enough-for-hs256".getBytes()));

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", new Argon2PasswordEncoder(16, 32, 1, 1024, 1));
        encoders.put("bcrypt", new BCryptPasswordEncoder(4));
        PasswordEncoder encoder = new DelegatingPasswordEncoder("argon2", encoders);

        users = new InMemoryUsers();
        tokens = new InMemoryRefreshTokens();
        attempts = new InMemoryLoginAttempts();
        audit = new RecordingAudit();

        passwords = new PasswordService(encoder, properties);
        JwtConfig config = new JwtConfig();
        JwtService jwt = new JwtService(config.jwtEncoder(properties), config.jwtDecoder(properties),
                config.jwtSigningDetails(properties), properties);
        refreshTokens = new RefreshTokenService(tokens, properties);
        throttle = new LoginThrottleService(attempts, properties);

        authentication = new AuthenticationService(users, new CustomUserDetailsService(users), passwords,
                jwt, refreshTokens, throttle, audit, properties);
    }

    private void givenUser(String username, String password, Set<Role> roles, boolean enabled, boolean locked) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwords.hash(password));
        user.setRoles(roles);
        user.setEnabled(enabled);
        user.setAccountLocked(locked);
        user.setCreatedAt(Instant.now());
        users.save(user);
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("always creates a USER, never an ADMIN")
        void alwaysCreatesUser() {
            var created = authentication.register(
                    new RegisterRequest("vivek", "vivek@example.com", STRONG_PASSWORD, "Vivek", "User"), null);

            assertThat(created.roles()).containsExactly(Role.USER);
            assertThat(created.roles()).doesNotContain(Role.ADMIN);
            assertThat(created.permissions()).doesNotContain(
                    com.orchpilot.workflow.auth.model.Permission.PLUGIN_UPLOAD);
            assertThat(audit.events()).contains(SecurityAuditEvent.USER_REGISTERED);
        }

        @Test
        @DisplayName("stores only a hash, never the password")
        void storesOnlyAHash() {
            authentication.register(
                    new RegisterRequest("vivek", "vivek@example.com", STRONG_PASSWORD, null, null), null);

            User stored = users.findByUsername("vivek").orElseThrow();
            assertThat(stored.getPasswordHash()).startsWith("{argon2}");
            assertThat(stored.getPasswordHash()).doesNotContain(STRONG_PASSWORD);
        }

        @Test
        @DisplayName("normalises the username and email to lower case")
        void normalisesIdentifiers() {
            authentication.register(
                    new RegisterRequest("Vivek", "Vivek@Example.COM", STRONG_PASSWORD, null, null), null);

            assertThat(users.findByUsername("vivek")).isPresent();
            assertThat(users.findByEmail("vivek@example.com")).isPresent();
        }

        @Test
        @DisplayName("rejects a duplicate username or email")
        void rejectsDuplicates() {
            authentication.register(
                    new RegisterRequest("vivek", "vivek@example.com", STRONG_PASSWORD, null, null), null);

            assertThatThrownBy(() -> authentication.register(
                    new RegisterRequest("vivek", "other@example.com", STRONG_PASSWORD, null, null), null))
                    .isInstanceOf(DuplicateAccountException.class)
                    .satisfies(t -> assertThat(((DuplicateAccountException) t).getField()).isEqualTo("username"));

            assertThatThrownBy(() -> authentication.register(
                    new RegisterRequest("other", "vivek@example.com", STRONG_PASSWORD, null, null), null))
                    .isInstanceOf(DuplicateAccountException.class)
                    .satisfies(t -> assertThat(((DuplicateAccountException) t).getField()).isEqualTo("email"));
        }

        @Test
        @DisplayName("rejects a weak password")
        void rejectsWeakPassword() {
            assertThatThrownBy(() -> authentication.register(
                    new RegisterRequest("vivek", "vivek@example.com", "weak", null, null), null))
                    .isInstanceOf(PasswordPolicyException.class);

            assertThat(users.findByUsername("vivek")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @BeforeEach
        void seed() {
            givenUser("vivek", STRONG_PASSWORD, Set.of(Role.USER), true, false);
        }

        @Test
        @DisplayName("issues an access token and a refresh token")
        void issuesTokens() {
            var result = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);

            assertThat(result.response().accessToken()).isNotBlank();
            assertThat(result.rawRefreshToken()).isNotBlank();
            assertThat(result.response().tokenType()).isEqualTo("Bearer");
            assertThat(result.response().expiresIn()).isEqualTo(900);
            assertThat(result.response().user().username()).isEqualTo("vivek");
            assertThat(audit.events()).contains(SecurityAuditEvent.LOGIN_SUCCESS);
        }

        @Test
        @DisplayName("does not put the refresh token in the body under cookie transport")
        void hidesRefreshTokenFromBody() {
            var result = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);

            // The token exists, but only for the controller to place in an HttpOnly cookie. Returning it
            // in the body as well would let script read it and defeat the cookie entirely.
            assertThat(result.response().refreshToken()).isNull();
            assertThat(result.rawRefreshToken()).isNotBlank();
        }

        @Test
        @DisplayName("accepts the email address as well as the username")
        void acceptsEmail() {
            assertThat(authentication.login(new LoginRequest("vivek@example.com", STRONG_PASSWORD), null))
                    .isNotNull();
        }

        @Test
        @DisplayName("gives the same generic message for every failure kind")
        void neverRevealsWhyLoginFailed() {
            givenUser("disabled", STRONG_PASSWORD, Set.of(Role.USER), false, false);
            givenUser("locked", STRONG_PASSWORD, Set.of(Role.USER), true, true);

            String unknown = messageFrom(() -> authentication.login(
                    new LoginRequest("nobody", STRONG_PASSWORD), null));
            String wrongPassword = messageFrom(() -> authentication.login(
                    new LoginRequest("vivek", "Wr0ng-Password!x"), null));
            String disabled = messageFrom(() -> authentication.login(
                    new LoginRequest("disabled", STRONG_PASSWORD), null));
            String locked = messageFrom(() -> authentication.login(
                    new LoginRequest("locked", STRONG_PASSWORD), null));

            // The whole defence against user enumeration: four different causes, one message.
            assertThat(unknown).isEqualTo(AuthenticationException.GENERIC_MESSAGE);
            assertThat(wrongPassword).isEqualTo(unknown);
            assertThat(disabled).isEqualTo(unknown);
            assertThat(locked).isEqualTo(unknown);
        }

        @Test
        @DisplayName("records the real reason in the audit trail even though the response hides it")
        void auditsTheRealReason() {
            messageFrom(() -> authentication.login(new LoginRequest("vivek", "Wr0ng-Password!x"), null));
            messageFrom(() -> authentication.login(new LoginRequest("nobody", STRONG_PASSWORD), null));

            assertThat(audit.reasons()).contains("bad_credentials", "user_not_found");
        }

        @Test
        @DisplayName("locks out after the configured number of failures")
        void locksOutAfterRepeatedFailures() {
            for (int attempt = 0; attempt < 5; attempt++) {
                messageFrom(() -> authentication.login(new LoginRequest("vivek", "Wr0ng-Password!x"), null));
            }

            // Even the correct password is now refused, and the message says so: that is a deliberate
            // exception to the generic rule, because the user needs to understand why.
            assertThatThrownBy(() -> authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null))
                    .isInstanceOf(AuthenticationException.class)
                    .satisfies(t -> assertThat(((AuthenticationException) t).isLockedOut()).isTrue())
                    .hasMessageContaining("Too many failed");
            assertThat(audit.events()).contains(SecurityAuditEvent.ACCOUNT_LOCKED);
        }

        @Test
        @DisplayName("clears the failure counter on success")
        void clearsCounterOnSuccess() {
            messageFrom(() -> authentication.login(new LoginRequest("vivek", "Wr0ng-Password!x"), null));
            messageFrom(() -> authentication.login(new LoginRequest("vivek", "Wr0ng-Password!x"), null));
            assertThat(throttle.remainingAttempts("vivek")).isEqualTo(3);

            authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);
            assertThat(throttle.remainingAttempts("vivek")).isEqualTo(5);
        }

        @Test
        @DisplayName("upgrades a legacy BCrypt hash on successful login")
        void upgradesLegacyHash() {
            User legacy = users.findByUsername("vivek").orElseThrow();
            legacy.setPasswordHash("{bcrypt}" + new BCryptPasswordEncoder(4).encode(STRONG_PASSWORD));
            users.save(legacy);

            authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);

            assertThat(users.findByUsername("vivek").orElseThrow().getPasswordHash())
                    .startsWith("{argon2}");
        }

        @Test
        @DisplayName("records the last login time")
        void recordsLastLogin() {
            assertThat(users.findByUsername("vivek").orElseThrow().getLastLoginAt()).isNull();
            authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);
            assertThat(users.findByUsername("vivek").orElseThrow().getLastLoginAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Refresh token rotation")
    class Rotation {

        @BeforeEach
        void seed() {
            givenUser("vivek", STRONG_PASSWORD, Set.of(Role.USER), true, false);
        }

        @Test
        @DisplayName("issues a new pair and invalidates the presented token")
        void rotates() {
            String first = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();

            var refreshed = authentication.refresh(first, null);
            assertThat(refreshed.rawRefreshToken()).isNotBlank().isNotEqualTo(first);
            assertThat(refreshed.response().accessToken()).isNotBlank();
            assertThat(audit.events()).contains(SecurityAuditEvent.TOKEN_REFRESH);
        }

        @Test
        @DisplayName("treats reuse of a rotated token as theft and revokes the whole family")
        void detectsReuse() {
            String first = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();
            String second = authentication.refresh(first, null).rawRefreshToken();

            // Replaying the consumed token. Both it and the token that replaced it must die, because we
            // cannot tell which party is the attacker.
            assertThatThrownBy(() -> authentication.refresh(first, null))
                    .isInstanceOf(AuthenticationException.class);
            assertThat(audit.events()).contains(SecurityAuditEvent.TOKEN_REUSE_DETECTED);

            assertThatThrownBy(() -> authentication.refresh(second, null))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects an unknown or expired token")
        void rejectsUnusableTokens() {
            assertThatThrownBy(() -> authentication.refresh("never-issued", null))
                    .isInstanceOf(AuthenticationException.class);
            assertThatThrownBy(() -> authentication.refresh(null, null))
                    .isInstanceOf(AuthenticationException.class);

            String issued = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();
            tokens.all().forEach(token -> token.setExpiresAt(Instant.now().minusSeconds(60)));

            assertThatThrownBy(() -> authentication.refresh(issued, null))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("refuses to refresh once the account is disabled")
        void refusesWhenAccountDisabled() {
            String issued = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();

            User user = users.findByUsername("vivek").orElseThrow();
            user.setEnabled(false);
            users.save(user);

            // Otherwise disabling an account would leave its holder minting access tokens for a week.
            assertThatThrownBy(() -> authentication.refresh(issued, null))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("revokes the token on logout, and logout is idempotent")
        void logoutRevokes() {
            String issued = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();

            authentication.logout(issued, null);
            assertThat(audit.events()).contains(SecurityAuditEvent.LOGOUT);

            assertThatThrownBy(() -> authentication.refresh(issued, null))
                    .isInstanceOf(AuthenticationException.class);

            // A second logout, or one with no token at all, must not fail: a client always has to be
            // able to clear its own state.
            authentication.logout(issued, null);
            authentication.logout(null, null);
        }

        @Test
        @DisplayName("caps concurrent sessions")
        void capsSessions() {
            for (int i = 0; i < 12; i++) {
                authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null);
            }
            String userId = users.findByUsername("vivek").orElseThrow().getId();
            assertThat(refreshTokens.liveSessions(userId)).hasSizeLessThanOrEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Password change")
    class PasswordChange {

        private String userId;

        @BeforeEach
        void seed() {
            givenUser("vivek", STRONG_PASSWORD, Set.of(Role.USER), true, false);
            userId = users.findByUsername("vivek").orElseThrow().getId();
        }

        @Test
        @DisplayName("changes the password and revokes every session")
        void changesAndRevokes() {
            String issued = authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null)
                    .rawRefreshToken();

            authentication.changePassword(userId,
                    new ChangePasswordRequest(STRONG_PASSWORD, "N3w-Passphrase!z", "N3w-Passphrase!z"), null);

            assertThat(audit.events()).contains(SecurityAuditEvent.PASSWORD_CHANGED);
            // Changing a password must end existing sessions, or it is useless as a response to a
            // compromise.
            assertThatThrownBy(() -> authentication.refresh(issued, null))
                    .isInstanceOf(AuthenticationException.class);

            authentication.login(new LoginRequest("vivek", "N3w-Passphrase!z"), null);
            assertThatThrownBy(() -> authentication.login(new LoginRequest("vivek", STRONG_PASSWORD), null))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("requires the current password")
        void requiresCurrentPassword() {
            assertThatThrownBy(() -> authentication.changePassword(userId,
                    new ChangePasswordRequest("Wr0ng-Password!x", "N3w-Passphrase!z", "N3w-Passphrase!z"), null))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("rejects a mismatched confirmation, a weak password, or reusing the current one")
        void validatesNewPassword() {
            assertThatThrownBy(() -> authentication.changePassword(userId,
                    new ChangePasswordRequest(STRONG_PASSWORD, "N3w-Passphrase!z", "different"), null))
                    .isInstanceOf(PasswordPolicyException.class);

            assertThatThrownBy(() -> authentication.changePassword(userId,
                    new ChangePasswordRequest(STRONG_PASSWORD, "weak", "weak"), null))
                    .isInstanceOf(PasswordPolicyException.class);

            assertThatThrownBy(() -> authentication.changePassword(userId,
                    new ChangePasswordRequest(STRONG_PASSWORD, STRONG_PASSWORD, STRONG_PASSWORD), null))
                    .isInstanceOf(PasswordPolicyException.class)
                    .satisfies(t -> assertThat(((PasswordPolicyException) t).getViolations())
                            .anyMatch(v -> v.contains("differ")));
        }
    }

    private static String messageFrom(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected the login to fail");
        } catch (AuthenticationException ex) {
            return ex.getMessage();
        }
    }

    // ------------------------------------------------------------------ in-memory repositories

    /**
     * Only the methods the services actually call are implemented; everything else throws.
     *
     * <p>A deliberate choice over Mockito here: these tests assert on state that accumulates across calls,
     * and stubbing each read to return a fixed value would make the assertions vacuous. An unimplemented
     * method throwing is also a useful signal that a service started using a query the test does not model.
     */
    private static class InMemoryUsers implements UserRepository {
        private final Map<String, User> byId = new java.util.LinkedHashMap<>();

        @Override
        public <S extends User> S save(S user) {
            if (user.getId() == null) {
                user.setId(UUID.randomUUID().toString());
            }
            byId.put(user.getId(), user);
            return user;
        }

        @Override
        public Optional<User> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return byId.values().stream().filter(u -> username.equals(u.getUsername())).findFirst();
        }

        @Override
        public Optional<User> findByEmail(String email) {
            return byId.values().stream().filter(u -> email.equals(u.getEmail())).findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public boolean existsByEmail(String email) {
            return findByEmail(email).isPresent();
        }

        @Override
        public boolean existsByRolesContaining(Role role) {
            return byId.values().stream().anyMatch(u -> u.getRoles().contains(role));
        }

        @Override
        public List<User> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public void delete(User user) {
            byId.remove(user.getId());
        }

        // Unused by these tests.
        @Override public org.springframework.data.domain.Page<User> search(String t, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<User> findByRolesContaining(Role r, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public List<User> findAll(org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<User> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<User> findAllById(Iterable<String> i) { throw new UnsupportedOperationException(); }
        @Override public long count() { return byId.size(); }
        @Override public void deleteById(String id) { byId.remove(id); }
        @Override public void deleteAll() { byId.clear(); }
        @Override public void deleteAll(Iterable<? extends User> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> i) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String id) { return byId.containsKey(id); }
        @Override public <S extends User> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> long count(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> boolean exists(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends User, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        @Override public <S extends User> S insert(S entity) { return save(entity); }
        @Override public <S extends User> List<S> insert(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    }

    private static class InMemoryRefreshTokens implements RefreshTokenRepository {
        private final Map<String, RefreshToken> byId = new java.util.LinkedHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();

        List<RefreshToken> all() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public <S extends RefreshToken> S save(S token) {
            if (token.getId() == null) {
                token.setId("token-" + sequence.incrementAndGet());
            }
            byId.put(token.getId(), token);
            return token;
        }

        @Override
        public <S extends RefreshToken> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            entities.forEach(entity -> saved.add(save(entity)));
            return saved;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return byId.values().stream().filter(t -> tokenHash.equals(t.getTokenHash())).findFirst();
        }

        @Override
        public List<RefreshToken> findByUserIdAndRevokedFalse(String userId) {
            return byId.values().stream()
                    .filter(t -> userId.equals(t.getUserId()) && !t.isRevoked()).toList();
        }

        @Override
        public List<RefreshToken> findByFamilyIdAndRevokedFalse(String familyId) {
            return byId.values().stream()
                    .filter(t -> familyId.equals(t.getFamilyId()) && !t.isRevoked()).toList();
        }

        @Override
        public long countByUserIdAndRevokedFalse(String userId) {
            return findByUserIdAndRevokedFalse(userId).size();
        }

        @Override
        public void deleteByUserId(String userId) {
            byId.values().removeIf(t -> userId.equals(t.getUserId()));
        }

        @Override public Optional<RefreshToken> findById(String id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<RefreshToken> findAll() { return all(); }
        @Override public List<RefreshToken> findAll(org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<RefreshToken> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<RefreshToken> findAllById(Iterable<String> i) { throw new UnsupportedOperationException(); }
        @Override public long count() { return byId.size(); }
        @Override public void deleteById(String id) { byId.remove(id); }
        @Override public void delete(RefreshToken t) { byId.remove(t.getId()); }
        @Override public void deleteAll() { byId.clear(); }
        @Override public void deleteAll(Iterable<? extends RefreshToken> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> i) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String id) { return byId.containsKey(id); }
        @Override public <S extends RefreshToken> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> long count(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> boolean exists(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        @Override public <S extends RefreshToken> S insert(S entity) { return save(entity); }
        @Override public <S extends RefreshToken> List<S> insert(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    }

    private static class InMemoryLoginAttempts implements LoginAttemptRepository {
        private final Map<String, LoginAttemptCounter> byIdentifier = new java.util.LinkedHashMap<>();

        @Override
        public <S extends LoginAttemptCounter> S save(S counter) {
            if (counter.getId() == null) {
                counter.setId(counter.getIdentifier());
            }
            byIdentifier.put(counter.getIdentifier(), counter);
            return counter;
        }

        @Override
        public Optional<LoginAttemptCounter> findByIdentifier(String identifier) {
            return Optional.ofNullable(byIdentifier.get(identifier));
        }

        @Override
        public void deleteByIdentifier(String identifier) {
            byIdentifier.remove(identifier);
        }

        @Override public Optional<LoginAttemptCounter> findById(String id) { return Optional.ofNullable(byIdentifier.get(id)); }
        @Override public List<LoginAttemptCounter> findAll() { return new ArrayList<>(byIdentifier.values()); }
        @Override public List<LoginAttemptCounter> findAll(org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<LoginAttemptCounter> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<LoginAttemptCounter> findAllById(Iterable<String> i) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public long count() { return byIdentifier.size(); }
        @Override public void deleteById(String id) { byIdentifier.remove(id); }
        @Override public void delete(LoginAttemptCounter c) { byIdentifier.remove(c.getIdentifier()); }
        @Override public void deleteAll() { byIdentifier.clear(); }
        @Override public void deleteAll(Iterable<? extends LoginAttemptCounter> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> i) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String id) { return byIdentifier.containsKey(id); }
        @Override public <S extends LoginAttemptCounter> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> long count(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> boolean exists(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        @Override public <S extends LoginAttemptCounter> S insert(S entity) { return save(entity); }
        @Override public <S extends LoginAttemptCounter> List<S> insert(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    }

    /** Captures audit records so tests can assert on what was recorded, and what was not. */
    private static class RecordingAudit extends SecurityAuditService {
        private final List<SecurityAuditLog> written = new ArrayList<>();

        RecordingAudit() {
            super(stubRepository());
        }

        @SuppressWarnings("unchecked")
        private static SecurityAuditLogRepository stubRepository() {
            SecurityAuditLogRepository repository = mock(SecurityAuditLogRepository.class);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            return repository;
        }

        @Override
        public void success(SecurityAuditEvent event, String userId, String username,
                            jakarta.servlet.http.HttpServletRequest request, Map<String, Object> details) {
            record(event, null);
            super.success(event, userId, username, request, details);
        }

        @Override
        public void failure(SecurityAuditEvent event, String userId, String username, String reason,
                            jakarta.servlet.http.HttpServletRequest request) {
            record(event, reason);
            super.failure(event, userId, username, reason, request);
        }

        @Override
        public void administrative(SecurityAuditEvent event, String actorId, String actorUsername,
                                   String subjectId, String subjectName,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   Map<String, Object> details) {
            record(event, null);
            super.administrative(event, actorId, actorUsername, subjectId, subjectName, request, details);
        }

        private void record(SecurityAuditEvent event, String reason) {
            SecurityAuditLog entry = new SecurityAuditLog();
            entry.setEvent(event);
            entry.setReason(reason);
            written.add(entry);
        }

        List<SecurityAuditEvent> events() {
            return written.stream().map(SecurityAuditLog::getEvent).toList();
        }

        List<String> reasons() {
            return written.stream().map(SecurityAuditLog::getReason).filter(java.util.Objects::nonNull).toList();
        }
    }
}
