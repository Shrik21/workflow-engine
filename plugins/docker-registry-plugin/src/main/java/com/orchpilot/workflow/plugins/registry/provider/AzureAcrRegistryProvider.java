package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Azure Container Registry.
 *
 * <h2>Two credential styles, both supported</h2>
 *
 * ACR accepts either an <em>admin</em> username and password — simple, and what most people start with — or a
 * service principal, which is the one an organisation should actually use. The service-principal path needs a
 * genuine two-step exchange that no other provider here requires:
 *
 * <ol>
 *   <li>client credentials → an Azure AD access token for the ACR resource;</li>
 *   <li>that AAD token → an ACR <em>refresh</em> token, via the registry's own {@code /oauth2/exchange};</li>
 *   <li>the refresh token then satisfies the standard {@code /v2/} bearer challenge, so everything above this
 *       class works unchanged.</li>
 * </ol>
 *
 * <p>The admin path collapses to plain Basic, which the shared challenge flow already handles.
 */
public class AzureAcrRegistryProvider extends AbstractRegistryProvider {

    private static final String AAD_SCOPE = "https://management.azure.com/.default";

    private final String registryName;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String adminUsername;
    private final String adminPassword;

    private String cachedRefreshToken;

    public AzureAcrRegistryProvider(PluginHttpClient http, long timeoutMillis, String registryName,
                                    String tenantId, String clientId, String clientSecret,
                                    String adminUsername, String adminPassword) {
        super(http, timeoutMillis);
        if (registryName == null || registryName.isBlank()) {
            throw new RegistryException("INVALID_REQUEST",
                    "ACR needs the registry name, e.g. myregistry (or myregistry.azurecr.io).", false);
        }
        this.registryName = registryName.trim().replaceFirst("\\.azurecr\\.io$", "");
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public RegistryProviderType type() {
        return RegistryProviderType.AZURE_ACR;
    }

    @Override
    protected String registryHost() {
        return registryName + ".azurecr.io";
    }

    @Override
    protected String basicAuthorization() {
        if (adminUsername != null && !adminUsername.isBlank()) {
            return basic(adminUsername, adminPassword == null ? "" : adminPassword);
        }
        // Service principal: the ACR refresh token is presented as a password under a fixed sentinel username
        // that ACR defines for exactly this exchange.
        if (clientId != null && !clientId.isBlank()) {
            return basic("00000000-0000-0000-0000-000000000000", acrRefreshToken());
        }
        return null;
    }

    /** Exchanges an AAD access token for an ACR refresh token, cached for the life of this provider. */
    private String acrRefreshToken() {
        if (cachedRefreshToken != null) {
            return cachedRefreshToken;
        }
        String aadToken = aadAccessToken();
        String form = "grant_type=access_token"
                + "&service=" + enc(registryHost())
                + "&tenant=" + enc(tenantId == null ? "" : tenantId)
                + "&access_token=" + enc(aadToken);

        HttpResponseView response = http.execute(HttpRequestSpec
                .builder("POST", "https://" + registryHost() + "/oauth2/exchange")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .body(form)
                .timeoutMillis(timeoutMillis)
                .build());
        if (!response.isSuccess()) {
            throw RegistryException.authentication(
                    "the ACR token exchange returned HTTP " + response.statusCode());
        }
        Object token = json(response).get("refresh_token");
        if (token == null) {
            throw RegistryException.authentication("ACR returned no refresh token");
        }
        cachedRefreshToken = String.valueOf(token);
        return cachedRefreshToken;
    }

    private String aadAccessToken() {
        if (tenantId == null || tenantId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "ACR service-principal authentication needs a tenant id, client id and client secret.",
                    false);
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&scope=" + enc(AAD_SCOPE);

        HttpResponseView response = http.execute(HttpRequestSpec
                .builder("POST", "https://login.microsoftonline.com/" + enc(tenantId) + "/oauth2/v2.0/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .body(form)
                .timeoutMillis(timeoutMillis)
                .build());
        if (!response.isSuccess()) {
            throw RegistryException.authentication("Azure AD returned HTTP " + response.statusCode());
        }
        Object token = json(response).get("access_token");
        if (token == null) {
            throw RegistryException.authentication("Azure AD returned no access token");
        }
        return String.valueOf(token);
    }

    @Override
    public Map<String, Object> login() {
        Map<String, Object> details = new LinkedHashMap<>(super.login());
        details.put("registryName", registryName);
        details.put("authentication", adminUsername != null && !adminUsername.isBlank()
                ? "ADMIN_USER" : "SERVICE_PRINCIPAL");
        return details;
    }
}
