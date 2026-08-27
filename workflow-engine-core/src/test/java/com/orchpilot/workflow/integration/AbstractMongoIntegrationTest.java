package com.orchpilot.workflow.integration;

import com.orchpilot.workflow.WorkflowApplication;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;
import java.util.LinkedHashSet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Base class for integration tests that need a real MongoDB.
 *
 * <p>A real database rather than an embedded or in-memory substitute, because the engine depends on behaviour a fake
 * would not reproduce: GridFS chunking for plugin JARs, unique index enforcement for idempotency keys, conditional
 * updates for schedule claiming, and TTL indexes. A test that passed against a fake and failed against MongoDB
 * would be worse than no test.
 *
 * <h2>Where the database comes from</h2>
 *
 * <p>Testcontainers by default, which needs a working Docker daemon. When {@code WORKFLOW_IT_MONGODB_URI} is set,
 * that server is used instead and no container is started, so the suite also runs on a machine that has MongoDB
 * but no Docker — the situation this project has been in on two machines running.
 *
 * <p>The fallback is opt-in and never defaults to {@code localhost}. A suite that silently found a developer's own
 * MongoDB would write test fixtures into whatever database that server holds, and these tests delete collections.
 * Supplying the URI is the operator saying "this server is expendable"; guessing it is not something a test may do.
 *
 * <p>Tagged {@code integration} and therefore excluded from {@code mvn test}. Run with {@code mvn verify}.
 */
// The application class is named explicitly rather than relying on the upward package search, so these tests keep
// working if they are ever moved out of a package beneath com.orchpilot.workflow.
@SpringBootTest(classes = WorkflowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
public abstract class AbstractMongoIntegrationTest {

    /** Set this to a MongoDB the suite may write to and wipe, to run without Docker. */
    private static final String EXTERNAL_URI_VARIABLE = "WORKFLOW_IT_MONGODB_URI";

    /**
     * Started once for the whole suite, and only when no external server was supplied.
     *
     * <p>Managed here rather than through {@code @Container} and {@code @Testcontainers}, because that lifecycle
     * starts a container unconditionally and there would be no way to skip it when one is not wanted.
     */
    private static MongoDBContainer container;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MongoTemplate mongoTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        // spring.mongodb, NOT spring.data.mongodb. Spring Boot 4 binds the connection from the former; a URI
        // under the older prefix is ignored without complaint and the driver falls back to its own defaults,
        // which for these tests means quietly using a developer's local server instead of the test one.
        registry.add("spring.mongodb.uri", AbstractMongoIntegrationTest::mongoUri);
        registry.add("spring.mongodb.database", () -> "workflow_engine_it");

        // A deterministic 256-bit key so encrypted secrets are readable within a test run.
        registry.add("workflow.engine.secrets.master-key",
                () -> Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        // The cron poller and the recovery sweep are exercised by their own tests; leaving them running here
        // would make unrelated assertions flaky.
        registry.add("workflow.engine.scheduler.enabled", () -> "false");
        registry.add("workflow.engine.execution.recovery-enabled", () -> "false");
        registry.add("workflow.engine.plugins.auto-load-on-startup", () -> "false");
        // No registry: these tests are about the engine, and an unreachable one would add a startup delay and a
        // stack trace to every run for no gain.
        registry.add("plugin.server.base-url", () -> "");
        registry.add("plugin.server.sync-on-startup", () -> "false");
        // A fresh database has no administrator, and these tests authenticate directly rather than signing in.
        registry.add("app.bootstrap-admin.enabled", () -> "false");
    }

    private static synchronized String mongoUri() {
        String external = System.getenv(EXTERNAL_URI_VARIABLE);
        if (external != null && !external.isBlank()) {
            return external;
        }
        if (container == null) {
            container = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
            container.start();
            // No explicit stop: the JVM exits at the end of the suite and Testcontainers' Ryuk reaps the
            // container. Stopping it in a shutdown hook races with tests still using it in a parallel run.
        }
        return container.getReplicaSetUrl();
    }

    /**
     * Authenticates a request as an administrator.
     *
     * <p>The engine authorises on permissions carried by an {@link AuthPrincipal}, so the tests build one rather
     * than presenting a header. An earlier version of these tests sent {@code X-Admin-Api-Key}, which no code has
     * read since authentication moved to JWTs; every request they made would now be refused.
     *
     * @return a post-processor that puts an ADMIN principal in the security context
     */
    protected static RequestPostProcessor asAdmin() {
        return as("integration-admin", Role.ADMIN);
    }

    /**
     * Authenticates a request as a user holding exactly the given roles.
     *
     * @param username the name recorded in audit entries
     * @param roles    the roles, whose permissions become the request's authorities
     * @return a post-processor that puts that principal in the security context
     */
    protected static RequestPostProcessor as(String username, Role... roles) {
        User user = new User();
        user.setId(username);
        user.setUsername(username);
        user.setEmail(username + "@integration.test");
        user.setRoles(new LinkedHashSet<>(java.util.Arrays.asList(roles)));
        user.setEnabled(true);

        AuthPrincipal principal = AuthPrincipal.of(user);
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, "n/a",
                principal.getAuthorities());
        return authentication(authentication);
    }

    /**
     * Empties the collections a test touches. Called from {@code @BeforeEach} in subclasses that need isolation.
     */
    protected void clearCollections(String... collections) {
        for (String collection : collections) {
            if (mongoTemplate.collectionExists(collection)) {
                mongoTemplate.getCollection(collection).deleteMany(new org.bson.Document());
            }
        }
    }
}
