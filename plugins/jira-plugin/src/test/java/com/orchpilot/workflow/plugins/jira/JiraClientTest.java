package com.orchpilot.workflow.plugins.jira;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The Cloud/Server divergence — the part of this plugin that silently breaks if it is wrong, because sending a
 * plain string description to Jira Cloud fails with an opaque 400 that names no cause.
 */
class JiraClientTest {

    private JiraClient client(JiraClient.Deployment deployment, String credential) {
        return new JiraClient(mock(com.orchpilot.workflow.sdk.context.PluginHttpClient.class),
                "https://company.atlassian.net", credential, deployment, 30_000);
    }

    @Test
    void picksTheApiVersionForTheDeployment() {
        assertThat(client(JiraClient.Deployment.CLOUD, "a@b.com:token").api())
                .isEqualTo("https://company.atlassian.net/rest/api/3");
        assertThat(client(JiraClient.Deployment.SERVER, "pat").api())
                .isEqualTo("https://company.atlassian.net/rest/api/2");
        // The Agile API is versioned separately and is the same on both.
        assertThat(client(JiraClient.Deployment.CLOUD, "a@b.com:token").agile())
                .endsWith("/rest/agile/1.0");
    }

    @Test
    void normalisesTheBaseUrl() {
        JiraClient trailing = new JiraClient(mock(com.orchpilot.workflow.sdk.context.PluginHttpClient.class),
                "company.atlassian.net///", "a@b.com:token", JiraClient.Deployment.CLOUD, 30_000);
        assertThat(trailing.baseUrl()).isEqualTo("https://company.atlassian.net");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cloudGetsAdfAndServerGetsPlainText() {
        Object cloud = client(JiraClient.Deployment.CLOUD, "a@b.com:token").richText("Login fails");
        assertThat(cloud).isInstanceOf(Map.class);
        Map<String, Object> document = (Map<String, Object>) cloud;
        assertThat(document).containsEntry("type", "doc").containsEntry("version", 1);

        List<Object> content = (List<Object>) document.get("content");
        Map<String, Object> paragraph = (Map<String, Object>) content.get(0);
        assertThat(paragraph).containsEntry("type", "paragraph");
        List<Object> inner = (List<Object>) paragraph.get("content");
        assertThat((Map<String, Object>) inner.get(0)).containsEntry("text", "Login fails");

        // Server v2 wants exactly the string the author typed.
        assertThat(client(JiraClient.Deployment.SERVER, "pat").richText("Login fails"))
                .isEqualTo("Login fails");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiLineTextBecomesOneAdfParagraphPerLineSoBreaksSurvive() {
        Object cloud = client(JiraClient.Deployment.CLOUD, "a@b.com:token")
                .richText("First line\nSecond line\n\nFourth");
        List<Object> content = (List<Object>) ((Map<String, Object>) cloud).get("content");

        assertThat(content).hasSize(4);
        // An empty line must be an empty paragraph with no content array at all — ADF rejects an empty text node.
        assertThat((Map<String, Object>) content.get(2)).doesNotContainKey("content");
    }

    @Test
    void readsTextBackOutOfAdfSoWorkflowsAlwaysSeeAString() {
        Object adf = client(JiraClient.Deployment.CLOUD, "a@b.com:token").richText("Deployment complete");
        assertThat(JiraClient.plainText(adf)).contains("Deployment complete");
        // A Server comment is already a string and must pass through untouched.
        assertThat(JiraClient.plainText("plain")).isEqualTo("plain");
        assertThat(JiraClient.plainText(null)).isNull();
    }

    @Test
    void credentialShapeIsEnforcedPerDeployment() {
        // Cloud needs email:apiToken; a bare token is the most common misconfiguration and is named as such.
        assertThatThrownBy(() -> client(JiraClient.Deployment.CLOUD, "just-a-token"))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("email:apiToken");

        // Server takes the PAT alone, as a bearer token.
        assertThat(client(JiraClient.Deployment.SERVER, "just-a-token")).isNotNull();

        assertThatThrownBy(() -> client(JiraClient.Deployment.CLOUD, ""))
                .isInstanceOf(JiraException.class);
    }

    @Test
    void extractsBothJiraErrorShapes() {
        // The per-field form carries the genuinely useful text.
        String fielded = "{\"errorMessages\":[],\"errors\":{\"summary\":\"Summary is required\"}}";
        assertThat(JiraException.extractMessage(fielded)).contains("summary").contains("required");

        String topLevel = "{\"errorMessages\":[\"Issue does not exist\"],\"errors\":{}}";
        assertThat(JiraException.extractMessage(topLevel)).contains("Issue does not exist");

        assertThat(JiraException.extractMessage("not json")).isEqualTo("not json");
        assertThat(JiraException.extractMessage("")).isNull();
    }

    @Test
    void statusMapsToStableCodesAndOnlyTransientOnesRetry() {
        assertThat(error(401).errorCode()).isEqualTo("JIRA_AUTHENTICATION_FAILED");
        assertThat(error(401).retryable()).isFalse();
        assertThat(error(403).errorCode()).isEqualTo("JIRA_PERMISSION_DENIED");
        assertThat(error(404).errorCode()).isEqualTo("JIRA_NOT_FOUND");
        assertThat(error(400).errorCode()).isEqualTo("JIRA_INVALID_REQUEST");

        assertThat(error(429).errorCode()).isEqualTo("JIRA_RATE_LIMITED");
        assertThat(error(429).retryable()).isTrue();
        assertThat(error(503).errorCode()).isEqualTo("JIRA_UNAVAILABLE");
        assertThat(error(503).retryable()).isTrue();
    }

    private static JiraException error(int status) {
        return JiraException.of(
                new com.orchpilot.workflow.sdk.context.HttpResponseView(status, Map.of(), "{}", 1), "an issue");
    }
}
