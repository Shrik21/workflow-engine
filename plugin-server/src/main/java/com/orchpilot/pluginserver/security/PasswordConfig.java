package com.orchpilot.pluginserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * How passwords are hashed.
 *
 * <h2>One-way, and there is no other direction</h2>
 *
 * There is no method in this service that recovers a password from what is stored, and none can be written:
 * Argon2id is a hash, not a cipher. Verification hands the candidate and the stored value to
 * {@link PasswordEncoder#matches}, which hashes the candidate the same way and compares. Anything shaped like
 * {@code decryptPassword} would mean the passwords were never hashed at all.
 *
 * <h2>Argon2id, with BCrypt still accepted</h2>
 *
 * New hashes are Argon2id. A {@link DelegatingPasswordEncoder} reads the {@code {algorithm}} prefix on each
 * stored value, so a BCrypt hash written by an earlier release still verifies and can be upgraded on the next
 * successful sign-in. Committing to a single algorithm would make changing it a password reset for everybody.
 */
@Configuration
public class PasswordConfig {

    private static final Logger log = LoggerFactory.getLogger(PasswordConfig.class);

    /** BCrypt is only ever used to verify legacy hashes, but a weak cost would still be a weak check. */
    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder(AuthProperties properties) {
        AuthProperties.Password policy = properties.getPassword();

        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                policy.getSaltLength(),
                policy.getHashLength(),
                policy.getParallelism(),
                policy.getMemoryKb(),
                policy.getIterations());

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", argon2);
        encoders.put("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH));

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("argon2", encoders);
        /*
         * Values written before prefixes were used still have to verify, or an upgrade locks everybody out.
         *
         * This has to be a real encoder. It was previously PasswordEncoderFactories.createDelegatingPasswordEncoder(),
         * which is itself a DelegatingPasswordEncoder and therefore throws "each password must have a password
         * encoding prefix" for exactly the unprefixed values it was installed to handle — so the fallback
         * defeated its own purpose. The symptom was a service client bootstrapped by an earlier release, whose
         * raw $2a$ hash could no longer be verified: the workflow engine could not obtain a token, and its
         * catalogue sync failed with what looked like a credentials problem.
         *
         * BCrypt, because that is what those values are. It verifies any $2a/$2b/$2y hash regardless of the
         * cost it was created with, so the strength here applies only to hashes this encoder writes, and it
         * writes none: nothing calls encode() through this path.
         */
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder(BCRYPT_STRENGTH));

        log.info("Password hashing: Argon2id (memory {} KiB, iterations {}, parallelism {}); BCrypt "
                        + "accepted for verification only", policy.getMemoryKb(), policy.getIterations(),
                policy.getParallelism());
        return delegating;
    }
}
