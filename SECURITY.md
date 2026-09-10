# Security

HuoCode performs read-only, syntactic analysis of public repositories. Code from analyzed
repositories is never built, executed, stored, or uploaded anywhere beyond the computed metrics
(scores, paths, numbers). This is a design constraint, not a side effect.

## Reporting a vulnerability

Please report security issues privately through a
[GitHub Security Advisory](https://github.com/huocode-bot/huocode/security/advisories/new)
instead of opening a public issue. Include the steps to reproduce and, where relevant, a minimal
payload. Reports are acknowledged within 7 days.

## Supported environments

Production runs on AWS Lambda behind API Gateway, deployed on Poja. Local development and
self-hosting are not supported configurations for now.