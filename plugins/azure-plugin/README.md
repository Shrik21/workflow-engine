# Azure plugin

One `Azure` palette category for Azure Virtual Machines, networking, and AKS. Create an engine secret such as `azure.production`:

```json
{"tenantId":"...","clientId":"...","clientSecret":"..."}
```

Use a least-privilege service principal. Credentials are exchanged for a short-lived Azure Resource Manager token and never appear in workflow output. Delete nodes require explicit confirmation and are marked destructive for supervised AI execution.
