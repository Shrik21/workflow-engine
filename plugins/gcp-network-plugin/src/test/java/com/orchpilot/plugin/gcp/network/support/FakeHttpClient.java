package com.orchpilot.plugin.gcp.network.support;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A scriptable {@link PluginHttpClient}: it records every request and answers each with the first matching rule.
 *
 * <p>That is enough to drive a whole flow — token exchange, GKE cluster lookup, then the Kubernetes call — from
 * canned JSON, with no GCP account and no network, and then assert on exactly what went over the wire.
 */
public final class FakeHttpClient implements PluginHttpClient {

    private record Rule(Function<HttpRequestSpec, Boolean> match,
                        Function<HttpRequestSpec, HttpResponseView> reply) {
    }

    private final List<Rule> rules = new ArrayList<>();
    public final List<HttpRequestSpec> requests = new ArrayList<>();

    /** Answers any request whose {@code "METHOD uri"} contains the given substring. */
    public FakeHttpClient on(String methodAndUriSubstring, int status, String body) {
        rules.add(new Rule(
                request -> (request.method() + " " + request.uri()).contains(methodAndUriSubstring),
                request -> new HttpResponseView(status, Map.of(), body, 1)));
        return this;
    }

    public FakeHttpClient on(Function<HttpRequestSpec, Boolean> match,
                             Function<HttpRequestSpec, HttpResponseView> reply) {
        rules.add(new Rule(match, reply));
        return this;
    }

    @Override
    public HttpResponseView execute(HttpRequestSpec request) {
        requests.add(request);
        for (Rule rule : rules) {
            if (rule.match().apply(request)) {
                return rule.reply().apply(request);
            }
        }
        throw new AssertionError("No fake HTTP rule matched: " + request.method() + " " + request.uri());
    }

    /**
     * @param method        the HTTP method the wanted request used
     * @param uriSubstring  a substring of its URI
     * @return the most recent matching request
     */
    public HttpRequestSpec lastMatching(String method, String uriSubstring) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            HttpRequestSpec request = requests.get(i);
            if (request.method().equalsIgnoreCase(method) && request.uri().contains(uriSubstring)) {
                return request;
            }
        }
        throw new AssertionError("No recorded " + method + " request for: " + uriSubstring);
    }

    public long countMatching(String uriSubstring) {
        return requests.stream().filter(request -> request.uri().contains(uriSubstring)).count();
    }

    /** @return every request body concatenated — used to assert that a secret never went over the wire */
    public String allTraffic() {
        StringBuilder traffic = new StringBuilder();
        for (HttpRequestSpec request : requests) {
            traffic.append(request.method()).append(' ').append(request.uri()).append('\n');
            if (request.body() != null) {
                traffic.append(request.body()).append('\n');
            }
            request.headers().forEach((name, value) -> traffic.append(name).append(": ").append(value).append('\n'));
        }
        return traffic.toString();
    }
}
