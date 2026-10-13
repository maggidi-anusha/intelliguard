---
id: high-error-rate
title: High error rate
appliesTo: [ERROR_RATE]
---
# High error rate

## Symptoms
- The share of failed requests rises above its normal level.
- Error logs increase, often with the same few messages repeated.
- Callers report failures or fall back to degraded behaviour.

## Likely causes
- A failing dependency such as a database or a downstream service.
- A recent deployment or configuration change with a bug.
- Expired credentials, certificates or tokens.
- Requests rejected because the service is overloaded.

## First checks
- Group recent errors by message to find the dominant failure.
- Check whether the services this one depends on are also anomalous.
- Compare the start of the errors with recent deployments and configuration changes.
- Check whether errors affect all requests or only one endpoint or client.

## Mitigation
- Roll back a recent change if errors started right after it.
- If a dependency is failing, work on the dependency first; errors here are often a symptom.
- Renew expired credentials or certificates.
- Enable circuit breakers or fallbacks to limit the impact on callers.

## Escalate when
- The error rate keeps rising or spreads to callers.
- The dominant error points to data loss or corruption.
- No recent change or failing dependency explains the errors.
