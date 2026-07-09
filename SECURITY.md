# Security Policy

## Scope

This is an engineering portfolio repository. No production infrastructure, real user data, or active credentials are present.

If you discover what appears to be a real credential or sensitive data committed to this repository, please report it immediately — it would represent a mistake, and it would already be compromised.

## Reporting a Vulnerability

**Do not open a public Issue for security vulnerabilities.**

Report security concerns by email to: `gerardgurgui1991@gmail.com`

Include:
- Description of the concern
- File and line number (if applicable)
- Whether you believe a credential is real or a test placeholder

**Response time:** Best effort for a solo maintainer. Typically within 48 hours.

## What Is and Is Not Present in This Repository

**What is present:**
- Example environment variable names (`.env.example`) — no values
- Test credentials in unit tests — not real, not rotatable
- HMAC validation patterns in `.githooks/pre-commit` — pattern strings, not active keys

**What is not present:**
- Any real API key, token, password, or connection string
- Any real user or customer data
- Any production infrastructure configuration

## Supported Versions

This is a portfolio repository without a production deployment lifecycle. Security fixes are applied to the `main` branch and released in the next patch version.
