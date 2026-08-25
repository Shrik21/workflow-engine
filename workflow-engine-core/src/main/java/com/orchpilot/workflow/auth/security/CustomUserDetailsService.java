package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Loads users for the security layer.
 *
 * <p>Accepts either a username or an email address, so a user need not remember which they registered
 * with. Both are stored lower-cased, so the lookup normalises its input and stays index-covered rather
 * than resorting to a case-insensitive regular expression.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public CustomUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        return AuthPrincipal.of(find(usernameOrEmail)
                // The message is for the server's own logs. The login endpoint never surfaces it,
                // because saying "no such user" would turn the form into a user-enumeration oracle.
                .orElseThrow(() -> new UsernameNotFoundException("No user for '" + usernameOrEmail + "'")));
    }

    /**
     * @param usernameOrEmail either identifier, in any case
     * @return the user, or empty
     */
    public Optional<User> find(String usernameOrEmail) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            return Optional.empty();
        }
        String normalised = usernameOrEmail.trim().toLowerCase(Locale.ROOT);
        Optional<User> byUsername = users.findByUsername(normalised);
        return byUsername.isPresent() ? byUsername : users.findByEmail(normalised);
    }

    /**
     * @param userId the user id from a validated token's subject claim
     * @return the user, or empty when the account has since been deleted
     */
    public Optional<User> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return users.findById(userId);
    }
}
