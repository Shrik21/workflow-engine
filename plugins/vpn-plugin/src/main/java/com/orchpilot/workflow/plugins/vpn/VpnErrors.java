package com.orchpilot.workflow.plugins.vpn;

/** The structured error codes the node publishes, referenced from the docs and the tests. */
final class VpnErrors {

    static final String CONFIGURATION_INVALID = "VPN_CONFIGURATION_INVALID";
    static final String UNKNOWN_PROVIDER = "VPN_UNKNOWN_PROVIDER";
    static final String UNSUPPORTED_OPERATION = "VPN_UNSUPPORTED_OPERATION";
    static final String CREDENTIAL_MISSING = "VPN_CREDENTIAL_MISSING";
    static final String CONNECTION_FAILED = "VPN_CONNECTION_FAILED";
    static final String CONNECTION_TIMEOUT = "VPN_CONNECTION_TIMEOUT";
    static final String AUTHENTICATION_FAILED = "VPN_AUTHENTICATION_FAILED";
    static final String PROVIDER_ERROR = "VPN_PROVIDER_ERROR";
    static final String TEST_FAILED = "VPN_TEST_FAILED";

    private VpnErrors() {
    }
}
