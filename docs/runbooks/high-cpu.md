---
id: high-cpu
title: High CPU
appliesTo: [CPU]
---
# High CPU

## Symptoms
- CPU utilisation stays well above its normal range for minutes, not just a short spike.
- Request latency rises and callers of the service start to see timeouts.
- Thread pools or worker queues back up while traffic looks unchanged.

## Likely causes
- A traffic increase or a retry storm from callers.
- A recent deployment that introduced an inefficient code path or a busy loop.
- Garbage collection pressure that shows up as CPU time.
- A scheduled or batch job running on the same host.

## First checks
- Compare the start of the CPU rise with recent deployments, configuration changes and scheduled jobs.
- Check request rate and error rate to see whether load or work per request increased.
- Look at the busiest processes or threads on the host.
- Check whether the callers of this service are retrying more than usual.

## Mitigation
- Roll back a recent deployment if the rise started right after it.
- Scale out horizontally or move non-urgent batch work away from the service.
- Apply rate limiting or shed low-priority traffic to protect latency-sensitive requests.
- Reduce retry counts or add backoff on callers that are amplifying the load.

## Escalate when
- CPU stays saturated after a rollback or scale-out.
- Latency or error rate keeps rising for dependent services.
- The cause cannot be identified within the first 15 minutes.
