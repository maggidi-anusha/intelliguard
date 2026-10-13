---
id: high-latency-database
title: High latency (database)
appliesTo: [LATENCY]
---
# High latency (database)

## Symptoms
- Query or request latency rises well above its normal range.
- Connection pools fill up and callers wait for connections.
- Services that depend on the database slow down or time out.

## Likely causes
- Slow queries, for example after a schema or query change, or missing indexes.
- Lock contention or long-running transactions.
- Resource saturation on the database host (CPU, disk or memory).
- More concurrent connections than the database or pool can serve.

## First checks
- List the slowest and most frequent queries in the affected period.
- Look for long-running transactions and lock waits.
- Check CPU, disk and memory on the database host.
- Check connection pool usage on the services that call the database.

## Mitigation
- Stop or kill runaway queries and long-running transactions.
- Roll back a recent schema or query change that coincides with the latency.
- Reduce load from callers, for example by pausing batch jobs.
- Add the missing index or increase resources once the cause is confirmed.

## Escalate when
- Latency stays high after removing obvious slow queries.
- Replication lag grows or the database stops accepting connections.
- Several dependent services time out at the same time.
