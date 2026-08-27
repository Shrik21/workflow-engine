package com.orchpilot.workflow.plugins.registry;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A scriptable {@link PluginHttpClient}: records every request, answers with the first matching rule. */
public final class FakeHttpClient implements PluginHttpClient {

    private record Rule(Function<HttpRequestSpec, Boolean> match, Function<HttpRequestSpec, HttpResponseView> reply) {
    }

    private final List<Rule> rules = new ArrayList<>();
    public final List<HttpRequestSpec> requests = new ArrayList<>();

    public FakeHttpClient on(String methodAndUriSubstring, int status, String body) {
        return on(methodAndUriSubstring, status, Map.of(), body);
    }

    public FakeHttpClient on(String methodAndUriSubstring, int status, Map<String, List<String>> headers,
                             String body) {
        rules.add(new Rule(req -> (req.method() + " " + req.uri()).contains(methodAndUriSubstring),
                req -> new HttpResponseView(status, headers, body, 1)));
        return this;
    }

    /** For stateful stubs, where the same URL must answer differently on a later call. */
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
        throw new AssertionError("No fake rule matched: " + request.method() + " " + request.uri());
    }

    /** Finds by method as well as URI — several calls often target the same path with different verbs. */
    public HttpRequestSpec lastMatching(String method, String uriSubstring) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            HttpRequestSpec request = requests.get(i);
            if (request.method().equals(method) && request.uri().contains(uriSubstring)) {
                return request;
            }
        }
        throw new AssertionError("No recorded " + method + " for: " + uriSubstring);
    }

    public HttpRequestSpec lastMatching(String uriSubstring) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).uri().contains(uriSubstring)) {
                return requests.get(i);
            }
        }
        throw new AssertionError("No recorded request for: " + uriSubstring);
    }

    public long countMatching(String uriSubstring) {
        return requests.stream().filter(r -> r.uri().contains(uriSubstring)).count();
    }
}
