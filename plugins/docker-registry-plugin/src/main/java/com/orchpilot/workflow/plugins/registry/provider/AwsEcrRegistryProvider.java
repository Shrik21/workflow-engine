package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.auth.SigV4Signer;
import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Elastic Container Registry.
 *
 * <h2>Two APIs, one provider</h2>
 *
 * ECR's data plane is a standard {@code /v2/} registry at
 * {@code <account>.dkr.ecr.<region>.amazonaws.com}, so manifests, tags and digests come from the shared base
 * class. Everything <em>about</em> repositories — creating, deleting, listing them — is the ECR control-plane
 * API at {@code api.ecr.<region>.amazonaws.com}, a SigV4-signed JSON-RPC service. Both are implemented here so a
 * workflow sees one capability set.
 *
 * <p>Authentication bridges the two: {@code GetAuthorizationToken} (control plane, SigV4) returns a base64
 * {@code AWS:password} pair which is then used as HTTP Basic against the data plane. That indirection is why ECR
 * needs a real signer rather than a static credential.
 */
public class AwsEcrRegistryProvider extends AbstractRegistryProvider {

    private final String accountId;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;

    /** Cached so a multi-step workflow does not re-sign a control-plane call for every data-plane request. */
    private String cachedBasic;

    public AwsEcrRegistryProvider(PluginHttpClient http, long timeoutMillis, String accountId, String region,
                                  String accessKey, String secretKey, String sessionToken) {
        super(http, timeoutMillis);
        this.accountId = require(accountId, "AWS account id");
        this.region = require(region, "AWS region");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new RegistryException("INVALID_REQUEST", "ECR needs the " + what + ".", false);
        }
        return value.trim();
    }

    @Override
    public RegistryProviderType type() {
        return RegistryProviderType.AWS_ECR;
    }

    @Override
    protected String registryHost() {
        return accountId + ".dkr.ecr." + region + ".amazonaws.com";
    }

    @Override
    protected String basicAuthorization() {
        if (cachedBasic == null) {
            cachedBasic = "Basic " + authorizationToken();
        }
        return cachedBasic;
    }

    /**
     * Calls {@code GetAuthorizationToken}, whose result is already a base64 {@code AWS:password} — exactly the
     * payload HTTP Basic wants, so it is used verbatim rather than re-encoded.
     */
    private String authorizationToken() {
        Map<String, Object> response = ecrApi("GetAuthorizationToken", Map.of());
        Object data = response.get("authorizationData");
        if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> entry) {
            Object token = entry.get("authorizationToken");
            if (token != null) {
                return String.valueOf(token);
            }
        }
        throw RegistryException.authentication("ECR returned no authorization token");
    }

    @Override
    public List<String> listRepositories() {
        Map<String, Object> response = ecrApi("DescribeRepositories", Map.of("maxResults", 1000));
        List<String> names = new ArrayList<>();
        if (response.get("repositories") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("repositoryName") != null) {
                    names.add(String.valueOf(map.get("repositoryName")));
                }
            }
        }
        return names;
    }

    @Override
    public void createRepository(String repository) {
        ecrApi("CreateRepository", Map.of("repositoryName", repository));
    }

    @Override
    public void deleteRepository(String repository) {
        // force=true so a repository that still holds images can be removed; without it ECR refuses and the
        // operator is left doing it by hand, which is exactly the manual step this plugin exists to remove.
        ecrApi("DeleteRepository", Map.of("repositoryName", repository, "force", true));
    }

    /**
     * ECR's control plane: a SigV4-signed JSON-RPC POST. Every operation is the same shape, so one helper
     * serves them all.
     */
    private Map<String, Object> ecrApi(String operation, Map<String, Object> payload) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "ECR needs credentials as accessKeyId:secretAccessKey in the connection secret.", false);
        }
        String host = "api.ecr." + region + ".amazonaws.com";
        String body = Json.write(payload);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = SigV4Signer.sign(host, "/", region, "ecr",
                "AmazonEC2ContainerRegistry_V20150921." + operation, bytes, accessKey, secretKey, sessionToken);

        HttpRequestSpec.Builder builder = HttpRequestSpec.builder("POST", "https://" + host + "/")
                .body(body)
                .timeoutMillis(timeoutMillis);
        headers.forEach(builder::header);

        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw RegistryException.of(response, "ECR " + operation);
        }
        return json(response);
    }

    @Override
    public Map<String, Object> login() {
        Map<String, Object> details = new LinkedHashMap<>(super.login());
        details.put("accountId", accountId);
        details.put("region", region);
        return details;
    }

    /** Kept for symmetry with the base class's Base64 usage; ECR's token already arrives encoded. */
    static String decodeUsername(String authorizationToken) {
        String decoded = new String(Base64.getDecoder().decode(authorizationToken), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        return colon < 0 ? decoded : decoded.substring(0, colon);
    }
}
