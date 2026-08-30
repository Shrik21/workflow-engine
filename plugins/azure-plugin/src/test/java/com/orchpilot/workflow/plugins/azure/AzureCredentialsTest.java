package com.orchpilot.workflow.plugins.azure;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AzureCredentialsTest{
 @Test void parsesServicePrincipal(){AzureCredentials c=AzureCredentials.parse("{\"tenantId\":\"tenant\",\"clientId\":\"client\",\"clientSecret\":\"secret\"}");assertThat(c.tenantId()).isEqualTo("tenant");assertThat(c.clientId()).isEqualTo("client");}
 @Test void rejectsIncompletePrincipal(){assertThatThrownBy(()->AzureCredentials.parse("{\"tenantId\":\"tenant\"}")).isInstanceOf(PluginConfigurationException.class).hasMessageContaining("clientSecret");}
}
