package com.orchpilot.pluginserver.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a password must satisfy.
 *
 * <p>The interesting cases are the two ways this check can be wrong. Too lax and {@code Password1!} — which
 * satisfies every character-class rule — gets through. Too strict and a genuine passphrase is refused for
 * containing a common word, which is how a policy teaches people to write down their passwords.
 */
class PasswordPolicyTest {

    private PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PasswordPolicy(new AuthProperties());
    }

    private List<String> check(String password) {
        return policy.violations(password, "admin");
    }

    @Test
    @DisplayName("a strong password passes")
    void accepts() {
        assertTrue(check("Correct-Horse-Battery-9!").isEmpty());
    }

    @Test
    @DisplayName("every broken rule is reported at once")
    void reportsEverythingAtOnce() {
        // Short, no upper case, no digit, no special character: four problems, one attempt.
        List<String> problems = check("abcdefg");
        assertEquals(4, problems.size());
    }

    @Test
    @DisplayName("length is required")
    void requiresLength() {
        assertTrue(check("Ab1!short").stream().anyMatch(problem -> problem.contains("12 characters")));
    }

    @Test
    @DisplayName("each character class is required")
    void requiresCharacterClasses() {
        assertTrue(check("lowercase-only-1!").stream().anyMatch(p -> p.contains("upper-case")));
        assertTrue(check("UPPERCASE-ONLY-1!").stream().anyMatch(p -> p.contains("lower-case")));
        assertTrue(check("NoDigitsInHere!!").stream().anyMatch(p -> p.contains("digit")));
        assertTrue(check("NoSpecials12345").stream().anyMatch(p -> p.contains("special")));
    }

    @Test
    @DisplayName("a common password wearing a costume is refused")
    void refusesTheObviousOnes() {
        // Every character-class rule satisfied, and still among the first guesses anybody makes.
        assertTrue(check("Password123!").stream().anyMatch(p -> p.contains("commonly guessed")));
        assertTrue(check("Welcome-2026!").stream().anyMatch(p -> p.contains("commonly guessed")));
    }

    @Test
    @DisplayName("a passphrase merely containing a common word is accepted")
    void allowsPassphrasesContainingCommonWords() {
        // The rule is about the password *being* a common word, not containing one. On a plugin registry,
        // refusing every password containing "registry", "plugin" or "admin" would refuse most of the good
        // ones — which is how a policy teaches people to write their passwords down.
        assertTrue(policy.violations("Registry-Admin-2026!", "admin").isEmpty());
        assertTrue(policy.violations("My-Plugin-Registry-K3y!", "admin").isEmpty());
    }

    @Test
    @DisplayName("the password may not simply be the username")
    void refusesTheUsername() {
        assertTrue(policy.violations("Vivek-Vivek1!", "vivek-vivek").stream()
                .anyMatch(problem -> problem.contains("username")));
    }

    @Test
    @DisplayName("a username appearing inside a longer password is fine")
    void allowsUsernameAsPartOfSomethingLonger() {
        assertFalse(policy.violations("Correct-Horse-vivek-42!", "vivek").stream()
                .anyMatch(problem -> problem.contains("username")));
    }

    @Test
    @DisplayName("the rules can be stated before anything is typed")
    void describesItself() {
        assertFalse(policy.describe().isEmpty());
    }
}
