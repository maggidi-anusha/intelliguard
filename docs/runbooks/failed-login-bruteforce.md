---
id: failed-login-bruteforce
title: Failed logins / brute force
appliesTo: [FAILED_LOGIN]
---
# Failed logins / brute force

## Symptoms
- Many failed login attempts in a short time.
- Attempts target a few account names, or come from a few sources.
- Authentication latency or error rate rises because of the extra load.

## Likely causes
- A password-guessing or credential-stuffing attack.
- A client or service using an expired or wrong credential in a retry loop.
- A misconfigured integration after a credential rotation.

## First checks
- Group failed attempts by account name and by source.
- Check whether any attempt succeeded after many failures.
- Check whether the failures come from an internal client with an outdated credential.
- Compare with recent credential rotations or configuration changes.

## Mitigation
- Block or rate limit the offending sources.
- Lock or force a password reset for targeted accounts that may be compromised.
- Fix or update the credential of a misbehaving internal client.
- Require stronger authentication for the targeted accounts.

## Escalate when
- An attempt succeeded after a series of failures.
- Privileged accounts such as admin or root are targeted.
- The attack continues from many sources after blocking.
