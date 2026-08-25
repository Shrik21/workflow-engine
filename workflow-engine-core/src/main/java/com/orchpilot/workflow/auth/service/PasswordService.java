package com.orchpilot.workflow.auth.service;

import com.orchpilot.workflow.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hashes passwords and enforces the password policy.
 *
 * <p>The only place in the platform that turns a raw password into a stored value, and it does so in one
 * direction. There is no {@code decrypt}, no {@code getPassword} and no method that returns anything from
 * which a password could be recovered: {@link #verify} re-hashes the candidate and compares digests.
 *
 * <p>Policy is validated here, on the server, for every path that sets a password: self-registration,
 * administrative creation, and password change. The Angular form applies the same rules for immediate
 * feedback, but that is a convenience for the user and is never trusted, because a client can be
 * bypassed with a single {@code curl}.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private static final String SPECIALS = "!@#$%^&*()_+-=[]{}|;:',.<>?/`~\"\\";

    /**
     * Passwords refused regardless of whether they satisfy the character rules.
     *
     * <p>A short list, not a dictionary: {@code Password123!} passes every complexity rule and is among
     * the first things any attacker tries. A production deployment should check against a breached
     * password corpus, which this list stands in for.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "password", "passwd", "qwerty", "qwertyuiop", "letmein", "welcome", "admin",
            "administrator", "changeme", "iloveyou", "monkey", "dragon", "sunshine", "princess",
            "football", "baseball", "trustno", "workflow", "orchpilot", "secret");

    /**
     * Length at which a forbidden word is also rejected as a substring rather than only as the whole
     * password.
     *
     * <p>Seven, so that {@code Password123!} is caught because its alphabetic core contains
     * {@code password}, while a genuine passphrase that happens to contain a short common word, such as
     * {@code MyDragonFlies99!}, is not. Shorter words are only rejected when they are the entire password.
     */
    private static final int SUBSTRING_MATCH_MIN_LENGTH = 7;

    private final PasswordEncoder encoder;
    private final AuthProperties.Password policy;

    /**
     * A hash used only to burn time when the account does not exist.
     *
     * <p>Computed once at startup against a random value. Verifying against it during a login for an
     * unknown username makes the failure path cost roughly the same as the real one, so response timing
     * does not tell an attacker which usernames exist.
     */
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.policy = properties.getPassword();
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * Validates then hashes a password.
     *
     * @param rawPassword the proposed password
     * @return an Argon2id digest in PHC format
     * @throws PasswordPolicyException when the password breaks the policy
     */
    public String hash(String rawPassword) {
        validate(rawPassword);
        return encoder.encode(rawPassword);
    }

    /**
     * Verifies a candidate against a stored hash.
     *
     * @param rawPassword  candidate
     * @param passwordHash stored digest
     * @return whether they match
     */
    public boolean verify(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }

    /**
     * Spends the same work as a real verification, for a username that does not exist.
     *
     * <p>Without this, a missing account returns in microseconds while a real one takes the Argon2id cost,
     * which is a reliable user-enumeration oracle even though both responses say the same thing.
     */
    public void verifyDummy(String rawPassword) {
        encoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }

    /**
     * Whether a stored hash should be re-hashed after a successful login.
     *
     * <p>True for a BCrypt hash from a migrated system, or for an Argon2id hash whose cost parameters no
     * longer match configuration. Upgrading on login is what allows the algorithm and its cost to change
     * without forcing a password reset on anyone.
     *
     * @param passwordHash the stored digest
     * @return whether to re-hash the verified password
     */
    public boolean needsUpgrade(String passwordHash) {
        return passwordHash != null && !passwordHash.startsWith("{argon2}") && !passwordHash.startsWith("$argon2");
    }

    /**
     * Validates a password against the configured policy.
     *
     * @param rawPassword the proposed password
     * @throws PasswordPolicyException listing every rule broken
     */
    public void validate(String rawPassword) {
        List<String> violations = new ArrayList<>();
        String candidate = rawPassword == null ? "" : rawPassword;

        if (candidate.length() < policy.getMinLength()) {
            violations.add("Must be at least " + policy.getMinLength() + " characters long");
        }
        if (candidate.length() > policy.getMaxLength()) {
            violations.add("Must be no more than " + policy.getMaxLength() + " characters long");
        }
        if (policy.isRequireUppercase() && candidate.chars().noneMatch(Character::isUpperCase)) {
            violations.add("Must contain an upper-case letter");
        }
        if (policy.isRequireLowercase() && candidate.chars().noneMatch(Character::isLowerCase)) {
            violations.add("Must contain a lower-case letter");
        }
        if (policy.isRequireDigit() && candidate.chars().noneMatch(Character::isDigit)) {
            violations.add("Must contain a digit");
        }
        if (policy.isRequireSpecial() && candidate.chars().noneMatch(c -> SPECIALS.indexOf(c) >= 0)) {
            violations.add("Must contain a special character");
        }
        if (isForbidden(candidate)) {
            violations.add("Is too common or too easily guessed");
        }
        if (!violations.isEmpty()) {
            // The password itself never appears in the log, only the count of broken rules.
            log.debug("Rejected a password for {} policy violation(s)", violations.size());
            throw new PasswordPolicyException(violations);
        }
    }

    /**
     * Matches the forbidden list through the decoration people add to satisfy complexity rules.
     *
     * <p>Two normalised forms are tested, because neither alone is sufficient. Character substitution turns
     * {@code P@ssw0rd} into {@code password}, but applying it to {@code Password123} would turn the trailing
     * digits into letters and produce {@code passwordle}, which matches nothing. Testing the plain form as
     * well catches that, and testing both catches {@code P@ssw0rd123}.
     *
     * <p>A password with no letters at all is refused outright: it is either digits or symbols, and both
     * spaces are small enough to enumerate whatever the length.
     */
    private boolean isForbidden(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);

        // Digits and symbols only. Reachable when the policy has been relaxed to not require letters.
        String letters = lower.replaceAll("[^a-z]", "");
        if (letters.isEmpty()) {
            return true;
        }

        String decoded = lower
                .replace('@', 'a')
                .replace('0', 'o')
                .replace('1', 'l')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('$', 's')
                .replace('!', 'i')
                .replaceAll("[^a-z]", "");

        return FORBIDDEN.stream().anyMatch(forbidden ->
                matches(letters, forbidden) || matches(decoded, forbidden));
    }

    private static boolean matches(String normalised, String forbidden) {
        if (normalised.equals(forbidden)) {
            return true;
        }
        return forbidden.length() >= SUBSTRING_MATCH_MIN_LENGTH && normalised.contains(forbidden);
    }

    /** @return the active policy, so the API can publish the rules the console should display */
    public AuthProperties.Password policy() {
        return policy;
    }
}
