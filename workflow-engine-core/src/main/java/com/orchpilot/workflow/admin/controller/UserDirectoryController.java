package com.orchpilot.workflow.admin.controller;

import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The picker feed: who a task can be assigned to.
 *
 * <p>Separate from {@code /api/admin/users} and deliberately not under it. That endpoint is ADMIN-only and returns
 * roles, email addresses, lock state and login history; this one is for any authenticated user choosing somebody
 * to hand a task to, and returns the two fields that requires.
 *
 * <h2>What it does not return</h2>
 *
 * <p>No email address, no role, no status beyond being listed at all, and no password material of any kind. An
 * assignee picker needs a name and an id. Everything else would be a directory of the organisation available to
 * every account, which is not what was being asked for and is difficult to withdraw once clients depend on it.
 *
 * <p>Disabled and locked accounts are excluded rather than returned and greyed out: assigning work to an account
 * that cannot sign in produces a task nobody can complete.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "The assignee picker feed")
public class UserDirectoryController {

    /** Enough for a dropdown; a larger organisation searches instead of scrolling. */
    private static final int MAX_RESULTS = 200;

    private final UserRepository users;

    public UserDirectoryController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/available")
    @Operation(summary = "List accounts a task can be assigned to",
            description = "Username, display name and id only. Locked and disabled accounts are omitted, "
                    + "because assigning work to an account that cannot sign in produces a task nobody can "
                    + "complete.")
    public List<Assignable> available(@RequestParam(required = false) String search) {
        Sort byName = Sort.by(Sort.Order.asc("username"));
        List<User> found = (search == null || search.isBlank()
                ? users.findAll(PageRequest.of(0, MAX_RESULTS, byName))
                // Escaped, because the repository interpolates this into a regular expression and an unescaped
                // "(" would make the query throw rather than match nothing.
                : users.search(java.util.regex.Pattern.quote(search.trim()),
                        PageRequest.of(0, MAX_RESULTS, byName)))
                .getContent();

        return found.stream()
                .filter(User::isUsable)
                .map(Assignable::of)
                .toList();
    }

    /**
     * One selectable account.
     *
     * @param userId      the id to send back when assigning
     * @param username    the login name, which is what a task history shows
     * @param displayName a friendlier label, falling back to the username
     */
    public record Assignable(String userId, String username, String displayName) {

        static Assignable of(User user) {
            return new Assignable(user.getId(), user.getUsername(), displayNameOf(user));
        }

        private static String displayNameOf(User user) {
            String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
            String last = user.getLastName() == null ? "" : user.getLastName().trim();
            String full = (first + " " + last).trim();
            return full.isEmpty() ? user.getUsername() : full;
        }
    }
}
