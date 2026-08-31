package com.orchpilot.workflow.plugins.aws;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single-category AWS integration using engine-governed HTTP and AWS Signature Version 4. */
public final class AwsPlugin implements WorkflowNodePlugin {
    private static final String CATEGORY = "AWS";
    private volatile PluginContext context;
    private final AwsSigV4 signer = new AwsSigV4();

    public String getId(){return "aws";} public String getName(){return "AWS";} public String getVersion(){return "1.0.0";}
    public String getDescription(){return "Manage EC2, VPC networking and EKS resources in AWS.";}
    public PluginType getPluginType(){return PluginType.NODE;}
    public void initialize(PluginContext value){context=value; value.logger().info("AWS plugin initialised");}
    public void destroy(){if(context!=null)context.logger().info("AWS plugin destroyed");}

    @Override public List<NodeDefinition> getNodeDefinitions(){
        List<NodeDefinition> result=new ArrayList<>();
        for(AwsOperation op:AwsOperation.values()) result.add(NodeDefinition.builder(op.nodeType)
                .displayName(op.displayName).description(description(op)).category(CATEGORY).icon("cloud")
                .configurationSchema(schema(op)).outputVariables("success","operation","region","statusCode","response")
                .idempotent(op.readOnly).supportsRetry(true).supportsAI(true).destructive(op.destructive).build());
        return List.copyOf(result);
    }

    private static String description(AwsOperation op){return switch(op){
        case EC2_RUN->"Launch an EC2 instance from an AMI."; case EC2_DESCRIBE->"Read EC2 instance details, optionally by instance IDs.";
        case EC2_START->"Start one or more stopped EC2 instances."; case EC2_STOP->"Stop one or more EC2 instances.";
        case EC2_REBOOT->"Reboot one or more EC2 instances."; case EC2_TERMINATE->"Permanently terminate EC2 instances.";
        case VPC_CREATE->"Create a VPC with the requested CIDR block."; case VPC_DESCRIBE->"List or inspect VPCs."; case VPC_DELETE->"Delete a VPC.";
        case SUBNET_CREATE->"Create a subnet in a VPC."; case SECURITY_GROUP_CREATE->"Create a security group in a VPC.";
        case EKS_LIST->"List EKS clusters."; case EKS_DESCRIBE->"Read an EKS cluster."; case EKS_CREATE->"Create an EKS control plane."; case EKS_DELETE->"Delete an EKS cluster.";};}

    private static Map<String,Object> schema(AwsOperation op){
        SchemaBuilder s=SchemaBuilder.object().secretRef("credentialsSecret","AWS credentials secret name",true)
                .withDescription("credentialsSecret","Name of an aws.* secret containing accessKeyId, secretAccessKey and optional sessionToken.")
                .string("region","AWS region",true).withDefault("region","us-east-1");
        switch(op){
            case EC2_RUN -> s.string("imageId","AMI ID",true).string("instanceType","Instance type",false).withDefault("instanceType","t3.micro")
                    .string("keyName","Key pair name",false).string("subnetId","Subnet ID",false).text("securityGroupIds","Security group IDs (comma separated)",false)
                    .integer("count","Instance count",false).withDefault("count",1);
            case EC2_DESCRIBE -> s.text("instanceIds","Instance IDs (comma separated; blank lists all)",false);
            case EC2_START,EC2_STOP,EC2_REBOOT,EC2_TERMINATE -> s.text("instanceIds","Instance IDs (comma separated)",true);
            case VPC_CREATE -> s.string("cidrBlock","CIDR block",true);
            case VPC_DESCRIBE -> s.text("vpcIds","VPC IDs (comma separated; blank lists all)",false);
            case VPC_DELETE -> s.string("vpcId","VPC ID",true);
            case SUBNET_CREATE -> s.string("vpcId","VPC ID",true).string("cidrBlock","CIDR block",true).string("availabilityZone","Availability zone",false);
            case SECURITY_GROUP_CREATE -> s.string("vpcId","VPC ID",true).string("groupName","Group name",true).string("description","Description",true);
            case EKS_LIST -> s.integer("maxResults","Maximum results",false).withDefault("maxResults",100);
            case EKS_DESCRIBE,EKS_DELETE -> s.string("clusterName","Cluster name",true);
            case EKS_CREATE -> s.string("clusterName","Cluster name",true).string("roleArn","EKS service role ARN",true)
                    .text("subnetIds","Subnet IDs (comma separated)",true).text("securityGroupIds","Security group IDs (comma separated)",false)
                    .string("kubernetesVersion","Kubernetes version",false);
        }
        if(op.destructive) s.bool("confirmed","Confirm destructive action",true).withDefault("confirmed",false);
        return s.build();
    }

    @Override public NodeExecutionResult execute(NodeExecutionContext execution){
        AwsOperation op=AwsOperation.from(execution.nodeType());
        if(op==null)return NodeExecutionResult.failure("AWS_UNKNOWN_OPERATION","Unknown AWS node type: "+execution.nodeType());
        NodeConfiguration cfg=execution.configuration();
        try{
            if(op.destructive&&!cfg.getBoolean("confirmed",false)) return NodeExecutionResult.failure("AWS_CONFIRMATION_REQUIRED","Set confirmed to true before running this destructive AWS operation.");
            String region=cfg.requireString("region");
            AwsCredentials credentials=AwsCredentials.parse(context.secrets().require(cfg.requireString("credentialsSecret")));
            HttpRequestSpec request=op.name().startsWith("EKS_")?eksRequest(op,cfg,region,credentials,execution.timeoutMillis()):ec2Request(op,cfg,region,credentials,execution.timeoutMillis());
            HttpResponseView response=context.http().execute(request);
            if(!response.isSuccess()) return NodeExecutionResult.failure("AWS_API_ERROR","AWS returned HTTP "+response.statusCode()+": "+safe(response.body()),response.statusCode()==429||response.statusCode()>=500);
            Object parsed=parseResponse(response);
            Map<String,Object> out=new LinkedHashMap<>(); out.put("success",true); out.put("operation",op.name()); out.put("region",region); out.put("statusCode",response.statusCode()); out.put("response",parsed);
            return NodeExecutionResult.success(out);
        }catch(PluginConfigurationException ex){return NodeExecutionResult.failure("AWS_MISCONFIGURED",ex.getMessage());}
        catch(RuntimeException ex){return NodeExecutionResult.failure("AWS_EXECUTION_ERROR",safe(ex.getMessage()),true);}
    }

    private HttpRequestSpec ec2Request(AwsOperation op,NodeConfiguration c,String region,AwsCredentials creds,long timeout){
        Map<String,String> p=new LinkedHashMap<>(); p.put("Action",op.ec2Action); p.put("Version","2016-11-15");
        switch(op){
            case EC2_RUN->{p.put("ImageId",c.requireString("imageId"));p.put("InstanceType",c.getString("instanceType","t3.micro"));p.put("MinCount",String.valueOf(c.getInt("count",1)));p.put("MaxCount",String.valueOf(c.getInt("count",1)));optional(p,"KeyName",c.getString("keyName",null));optional(p,"SubnetId",c.getString("subnetId",null));indexed(p,"SecurityGroupId",c.getString("securityGroupIds",null));}
            case EC2_DESCRIBE,EC2_START,EC2_STOP,EC2_REBOOT,EC2_TERMINATE->indexed(p,"InstanceId",c.getString("instanceIds",null));
            case VPC_CREATE->p.put("CidrBlock",c.requireString("cidrBlock")); case VPC_DESCRIBE->indexed(p,"VpcId",c.getString("vpcIds",null)); case VPC_DELETE->p.put("VpcId",c.requireString("vpcId"));
            case SUBNET_CREATE->{p.put("VpcId",c.requireString("vpcId"));p.put("CidrBlock",c.requireString("cidrBlock"));optional(p,"AvailabilityZone",c.getString("availabilityZone",null));}
            case SECURITY_GROUP_CREATE->{p.put("VpcId",c.requireString("vpcId"));p.put("GroupName",c.requireString("groupName"));p.put("GroupDescription",c.requireString("description"));}
            default->throw new IllegalArgumentException("Not an EC2 operation");
        }
        String body=form(p); String uri="https://ec2."+region+".amazonaws.com/";
        return signer.sign("POST",uri,body,"application/x-www-form-urlencoded; charset=utf-8","ec2",region,creds,timeout);
    }

    private HttpRequestSpec eksRequest(AwsOperation op,NodeConfiguration c,String region,AwsCredentials creds,long timeout){
        String base="https://eks."+region+".amazonaws.com"; String method="GET", path="/clusters", body="";
        switch(op){
            case EKS_LIST->path += "?maxResults="+Math.max(1,Math.min(100,c.getInt("maxResults",100)));
            case EKS_DESCRIBE->{path+="/"+url(c.requireString("clusterName"));}
            case EKS_DELETE->{method="DELETE";path+="/"+url(c.requireString("clusterName"));}
            case EKS_CREATE->{method="POST";Map<String,Object> resources=new LinkedHashMap<>();resources.put("subnetIds",csv(c.requireString("subnetIds")));List<String> groups=csv(c.getString("securityGroupIds",null));if(!groups.isEmpty())resources.put("securityGroupIds",groups);Map<String,Object> payload=new LinkedHashMap<>();payload.put("name",c.requireString("clusterName"));payload.put("roleArn",c.requireString("roleArn"));payload.put("resourcesVpcConfig",resources);optionalObject(payload,"version",c.getString("kubernetesVersion",null));body=Json.write(payload);}
            default->throw new IllegalArgumentException("Not an EKS operation");
        }
        return signer.sign(method,base+path,body,"application/json","eks",region,creds,timeout);
    }
    private static Object parseResponse(HttpResponseView response){String content=response.firstHeader("Content-Type");if(content!=null&&content.toLowerCase().contains("json"))return Json.parse(response.body());return response.body();}
    private static String form(Map<String,String> p){StringBuilder b=new StringBuilder();p.forEach((k,v)->{if(b.length()>0)b.append('&');b.append(url(k)).append('=').append(url(v));});return b.toString();}
    private static void indexed(Map<String,String> p,String name,String values){List<String> list=csv(values);for(int i=0;i<list.size();i++)p.put(name+"."+(i+1),list.get(i));}
    private static List<String> csv(String text){if(text==null||text.isBlank())return List.of();return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(v->!v.isBlank()).toList();}
    private static void optional(Map<String,String> p,String key,String value){if(value!=null&&!value.isBlank())p.put(key,value);}
    private static void optionalObject(Map<String,Object> p,String key,String value){if(value!=null&&!value.isBlank())p.put(key,value);}
    private static String url(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    private static String safe(String value){if(value==null)return "AWS request failed";String compact=value.replaceAll("[\\r\\n]+"," ");return compact.length()>500?compact.substring(0,500):compact;}
}
