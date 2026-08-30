package com.orchpilot.workflow.plugins.azure;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.json.Json;
import java.util.Map;
record AzureCredentials(String tenantId,String clientId,String clientSecret){
 static AzureCredentials parse(String json){Map<String,Object> m=Json.parseObject(json);String t=text(m,"tenantId"),c=text(m,"clientId"),s=text(m,"clientSecret");if(t==null||c==null||s==null)throw new PluginConfigurationException("Azure credential secret must contain tenantId, clientId and clientSecret");return new AzureCredentials(t,c,s);}
 private static String text(Map<String,Object> m,String k){Object v=m.get(k);return v==null||String.valueOf(v).isBlank()?null:String.valueOf(v);}
}
