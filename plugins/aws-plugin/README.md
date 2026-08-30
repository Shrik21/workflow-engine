# AWS plugin

One OrchPilot plugin and one palette category (`AWS`) for EC2 instance lifecycle, VPC networking and EKS control-plane operations.

Create an engine secret such as `aws.production` containing:

```json
{"accessKeyId":"AKIA...","secretAccessKey":"...","sessionToken":"optional"}
```

The secret value is never stored in a workflow or returned as output. Requests are AWS Signature Version 4 signed and sent through OrchPilot's allow-listed plugin HTTP client. Use a least-privilege IAM principal. Destructive nodes require `confirmed=true` and are marked destructive for supervised AI approvals.
