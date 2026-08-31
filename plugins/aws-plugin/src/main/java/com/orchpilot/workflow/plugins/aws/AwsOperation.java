package com.orchpilot.workflow.plugins.aws;

enum AwsOperation {
    EC2_RUN("AWS_EC2_RUN_INSTANCE","Run EC2 instance",false,false,"RunInstances"),
    EC2_DESCRIBE("AWS_EC2_DESCRIBE_INSTANCES","Describe EC2 instances",true,false,"DescribeInstances"),
    EC2_START("AWS_EC2_START_INSTANCES","Start EC2 instances",false,false,"StartInstances"),
    EC2_STOP("AWS_EC2_STOP_INSTANCES","Stop EC2 instances",false,false,"StopInstances"),
    EC2_REBOOT("AWS_EC2_REBOOT_INSTANCES","Reboot EC2 instances",false,false,"RebootInstances"),
    EC2_TERMINATE("AWS_EC2_TERMINATE_INSTANCES","Terminate EC2 instances",false,true,"TerminateInstances"),
    VPC_CREATE("AWS_VPC_CREATE","Create VPC",false,false,"CreateVpc"),
    VPC_DESCRIBE("AWS_VPC_DESCRIBE","Describe VPCs",true,false,"DescribeVpcs"),
    VPC_DELETE("AWS_VPC_DELETE","Delete VPC",false,true,"DeleteVpc"),
    SUBNET_CREATE("AWS_SUBNET_CREATE","Create subnet",false,false,"CreateSubnet"),
    SECURITY_GROUP_CREATE("AWS_SECURITY_GROUP_CREATE","Create security group",false,false,"CreateSecurityGroup"),
    EKS_LIST("AWS_EKS_LIST_CLUSTERS","List EKS clusters",true,false,null),
    EKS_DESCRIBE("AWS_EKS_DESCRIBE_CLUSTER","Describe EKS cluster",true,false,null),
    EKS_CREATE("AWS_EKS_CREATE_CLUSTER","Create EKS cluster",false,false,null),
    EKS_DELETE("AWS_EKS_DELETE_CLUSTER","Delete EKS cluster",false,true,null);
    final String nodeType, displayName, ec2Action; final boolean readOnly, destructive;
    AwsOperation(String nodeType,String displayName,boolean readOnly,boolean destructive,String ec2Action){this.nodeType=nodeType;this.displayName=displayName;this.readOnly=readOnly;this.destructive=destructive;this.ec2Action=ec2Action;}
    static AwsOperation from(String type){for(AwsOperation op:values())if(op.nodeType.equals(type))return op;return null;}
}
