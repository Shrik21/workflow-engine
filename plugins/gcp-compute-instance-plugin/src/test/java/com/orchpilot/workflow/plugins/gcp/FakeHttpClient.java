package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A scriptable {@link PluginHttpClient} for tests: it records every request and answers each with the first
 * matching rule, so a whole GCP flow (token exchange, then a Compute call, then operation polling) can be driven
 * with canned JSON and asserted on — no Google account, no network.
 */
final class FakeHttpClient implements PluginHttpClient {

    /** A rule: if {@code match} accepts the request, reply with {@code response} (which may advance a sequence). */
    private record Rule(Function<HttpRequestSpec, Boolean> match, Function<HttpRequestSpec, HttpResponseView> reply) {
    }

    private final List<Rule> rules = new ArrayList<>();
    final List<HttpRequestSpec> requests = new ArrayList<>();

    FakeHttpClient on(String methodAndUriSubstring, int status, String body) {
        rules.add(new Rule(
                req -> (req.method() + " " + req.uri()).contains(methodAndUriSubstring),
                req -> new HttpResponseView(status, Map.of(), body, 1)));
        return this;
    }

    FakeHttpClient on(Function<HttpRequestSpec, Boolean> match, Function<HttpRequestSpec, HttpResponseView> reply) {
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

    HttpRequestSpec lastRequestMatching(String uriSubstring) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).uri().contains(uriSubstring)) {
                return requests.get(i);
            }
        }
        throw new AssertionError("No recorded request for: " + uriSubstring);
    }

    long countMatching(String uriSubstring) {
        return requests.stream().filter(r -> r.uri().contains(uriSubstring)).count();
    }
}
