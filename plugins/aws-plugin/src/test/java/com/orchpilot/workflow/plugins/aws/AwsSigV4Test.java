package com.orchpilot.workflow.plugins.aws;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AwsSigV4Test {
 @Test void signsWithExpectedScopeAndTemporaryToken(){AwsSigV4 signer=new AwsSigV4(Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"),ZoneOffset.UTC));HttpRequestSpec r=signer.sign("POST","https://ec2.us-east-1.amazonaws.com/","Action=DescribeInstances&Version=2016-11-15","application/x-www-form-urlencoded; charset=utf-8","ec2","us-east-1",new AwsCredentials("AKID","secret","token"),3000);assertThat(r.headers()).containsEntry("X-Amz-Date","20260830T120000Z").containsEntry("X-Amz-Security-Token","token");assertThat(r.headers().get("Authorization")).contains("Credential=AKID/20260830/us-east-1/ec2/aws4_request").contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token");}
}
