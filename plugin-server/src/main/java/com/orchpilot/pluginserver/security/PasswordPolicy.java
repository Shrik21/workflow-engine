package com.orchpilot.pluginserver.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a new password must satisfy.
 *
 * <h2>Length first</h2>
 *
 * The minimum is twelve characters, and that is the rule doing most of the work. Character-class requirements
 * are here because they are asked for and because they cost little, but they mostly push people toward
 * `Password1!` — which satisfies every class rule and is among the first guesses anybody makes. The list of
 * obviously guessed passwords below exists to refuse exactly that.
 *
 * <h2>Every failure is reported at once</h2>
 *
 * A checker that stops at the first problem makes somebody submit four times to learn four rules. All of them
 * are collected and returned together.
 */
@Component
public class PasswordPolicy {

    /**
     * Passwords that pass every structural rule and are still worthless.
     *
     * <p>Short by design. This is not a breach corpus — that belongs behind a service — it is the handful of
     * shapes that satisfy "upper, lower, digit, special, twelve characters" and are guessed first anyway.
     */
    private static final Set<String> OBVIOUS = Set.of(
            "password", "passw0rd", "password1", "password123", "letmein", "welcome",
            "admin", "administrator", "changeme", "qwerty", "iloveyou", "plugin", "registry");

    private final AuthProperties properties;

    public PasswordPolicy(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks a candidate password.
     *
     * @param password the candidate
     * @param username the account it is for, so the password cannot simply be the name
     * @return every rule it breaks, empty when it is acceptable
     */
    public List<String> violations(String password, String username) {
        AuthProperties.Password rules = properties.getPassword();
        List<String> problems = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            problems.add("A password is required.");
            return problems;
        }
        if (password.length() < rules.getMinLength()) {
            problems.add("At least " + rules.getMinLength() + " characters.");
        }
        if (password.length() > rules.getMaxLength()) {
            problems.add("At most " + rules.getMaxLength() + " characters.");
        }
        if (rules.isRequireUppercase() && password.chars().noneMatch(Character::isUpperCase)) {
            problems.add("An upper-case letter.");
        }
        if (rules.isRequireLowercase() && password.chars().noneMatch(Character::isLowerCase)) {
            problems.add("A lower-case letter.");
        }
        if (rules.isRequireDigit() && password.chars().noneMatch(Character::isDigit)) {
            problems.add("A digit.");
        }
        if (rules.isRequireSpecial()
                && password.chars().noneMatch(character -> !Character.isLetterOrDigit(character))) {
            problems.add("A special character.");
        }

        String lower = password.toLowerCase(Locale.ROOT);

        /*
         * Compared against the password's alphabetic core rather than as a substring.
         *
         * A substring test refuses every password *containing* one of these words, which on a plugin registry
         * means "registry", "plugin" and "admin" are banned from any passphrase — and a passphrase is exactly
         * what should be encouraged. Stripping the digits and punctuation catches what this rule is actually
         * for, which is Password1! and admin2026 wearing a costume, while leaving
         * Registry-Admin-2026! alone.
         */
        String core = lower.replaceAll("[^a-z]", "");
        if (OBVIOUS.contains(core)) {
            problems.add("Not a commonly guessed password.");
        }
        if (username != null && !username.isBlank()) {
            String name = username.toLowerCase(Locale.ROOT);
            // The whole password being the username, decorated, is the case worth refusing. A password that
            // merely contains a short username is not.
            if (core.equals(name.replaceAll("[^a-z]", "")) || lower.equals(name)) {
                problems.add("Not your username.");
            }
        }
        return problems;
    }

    /** @return the rules as sentences, for a sign-up form to show before anything is typed */
    public List<String> describe() {
        AuthProperties.Password rules = properties.getPassword();
        List<String> described = new ArrayList<>();
        described.add("At least " + rules.getMinLength() + " characters");
        if (rules.isRequireUppercase()) {
            described.add("An upper-case letter");
        }
        if (rules.isRequireLowercase()) {
            described.add("A lower-case letter");
        }
        if (rules.isRequireDigit()) {
            described.add("A digit");
        }
        if (rules.isRequireSpecial()) {
            described.add("A special character");
        }
        described.add("Not a commonly guessed password, and not your username");
        return described;
    }
}
