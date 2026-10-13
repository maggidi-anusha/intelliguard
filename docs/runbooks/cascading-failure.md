---
id: cascading-failure
title: Cascading failure
appliesTo: [MULTI_SERVICE_INCIDENT]
---
# Cascading failure

## Symptoms
- Several dependent services become anomalous within a few minutes of each other.
- Anomalies start at a backend service and then appear on the services that call it.
- Errors and latency at the edge (gateways, web front ends) follow a problem deeper in the stack.

## Likely causes
- A failing shared dependency such as a database that many services call.
- Retries and timeouts from callers that add load to an already struggling service.
- Resource exhaustion in one service that blocks the threads or connections of its callers.

## First checks
- Read the root-cause ranking: rank 1 is the service the heuristic thinks started the cascade.
- Check the evidence for rank 1: earliest onset and the number of anomalous services that depend on it carry the most weight.
- Look at the confidence: a low value means rank 1 and rank 2 score almost the same, so check both.
- Confirm the order of onsets in the timeline before acting on the ranking.

## Mitigation
- Work from the root downward: stabilise the rank-1 service first, then confirm its callers recover.
- Reduce retries and add backoff on callers to stop them amplifying the load.
- Use circuit breakers or fallbacks so callers fail fast instead of piling up.
- Avoid restarting every affected service at once; recover the root first.

## Escalate when
- Callers do not recover after the root service is stable.
- The ranking has low confidence and the first checks do not confirm a root.
- The cascade spreads to services that are not in the incident.
