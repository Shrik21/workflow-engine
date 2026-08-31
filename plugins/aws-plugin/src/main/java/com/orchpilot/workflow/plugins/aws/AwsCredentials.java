package com.orchpilot.workflow.plugins.aws;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.json.Json;
import java.util.Map;

record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {
    static AwsCredentials parse(String json) {
        Map<String,Object> value = Json.parseObject(json);
        String access = text(value, "accessKeyId");
        String secret = text(value, "secretAccessKey");
        if (access == null || secret == null) throw new PluginConfigurationException(
                "AWS credential secret must contain accessKeyId and secretAccessKey");
        return new AwsCredentials(access, secret, text(value, "sessionToken"));
    }
    private static String text(Map<String,Object> map, String key) {
        Object value = map.get(key); return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
