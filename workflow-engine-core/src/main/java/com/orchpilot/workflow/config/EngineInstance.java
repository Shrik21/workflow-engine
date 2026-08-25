package com.orchpilot.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Identity of this engine process.
 *
 * <p>Every execution records the instance that owns it. Recovery uses that, together with the
 * heartbeat, to distinguish "another instance is working on this" from "the instance that owned this
 * died", which is the difference between a safe resume and a duplicated side effect.
 */
@Component
public class EngineInstance {

    private static final Logger log = LoggerFactory.getLogger(EngineInstance.class);

    private final String id;

    public EngineInstance(WorkflowEngineProperties properties) {
        String configured = properties.getInstanceId();
        this.id = (configured == null || configured.isBlank()) ? generate() : configured;
        log.info("Workflow engine instance id: {}", this.id);
    }

    /**
     * @return stable identifier of this process for the lifetime of the JVM
     */
    public String id() {
        return id;
    }

    private static String generate() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
