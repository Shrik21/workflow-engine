package com.orchpilot.workflow.plugins.azure;
import com.orchpilot.workflow.sdk.context.*;
import com.orchpilot.workflow.sdk.json.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
final class AzureAuth{
 String token(PluginHttpClient http,AzureCredentials c,long timeout){String body="client_id="+enc(c.clientId())+"&client_secret="+enc(c.clientSecret())+"&scope="+enc("https://management.azure.com/.default")+"&grant_type=client_credentials";HttpResponseView r=http.execute(HttpRequestSpec.post("https://login.microsoftonline.com/"+enc(c.tenantId())+"/oauth2/v2.0/token",body).header("Content-Type","application/x-www-form-urlencoded").timeoutMillis(timeout).build());if(!r.isSuccess())throw new AzureException("AZURE_AUTH_FAILED","Azure identity returned HTTP "+r.statusCode(),r.statusCode()>=500||r.statusCode()==429);Map<String,Object> json=Json.parseObject(r.body());Object token=json.get("access_token");if(token==null||String.valueOf(token).isBlank())throw new AzureException("AZURE_AUTH_FAILED","Azure identity returned no access token",false);return String.valueOf(token);}
 static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
}
