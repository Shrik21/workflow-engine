package com.orchpilot.workflow.plugins.aws;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AwsCredentialsTest {
 @Test void readsLongLivedAndTemporaryCredentials(){AwsCredentials c=AwsCredentials.parse("{\"accessKeyId\":\"AKID\",\"secretAccessKey\":\"secret\",\"sessionToken\":\"token\"}");assertThat(c.accessKeyId()).isEqualTo("AKID");assertThat(c.sessionToken()).isEqualTo("token");}
 @Test void rejectsIncompleteSecret(){assertThatThrownBy(()->AwsCredentials.parse("{\"accessKeyId\":\"AKID\"}")).isInstanceOf(PluginConfigurationException.class).hasMessageContaining("secretAccessKey");}
}
