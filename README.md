# XamOps-Dashboard

This project provides a Spring Boot based dashboard for visualising data across multiple AWS accounts.

### Connecting Accounts

1. Use `POST /api/account-manager/generate-stack-url` with an account name to obtain a CloudFormation quick create URL.
2. Launch the stack in the target AWS account. It creates a cross‑account role.
3. Call `POST /api/account-manager/verify-stack` with the role ARN and external ID to finalise the connection.
4. Connected accounts can be listed with `GET /api/account-manager/accounts`.