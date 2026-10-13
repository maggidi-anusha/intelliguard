---
id: memory-pressure
title: Memory pressure
appliesTo: [MEMORY]
---
# Memory pressure

## Symptoms
- Memory usage climbs steadily or stays close to the configured limit.
- Garbage collection runs more often and pauses grow longer.
- Processes are restarted or killed for running out of memory.

## Likely causes
- A memory leak introduced by a recent change.
- Caches or in-memory queues growing without a bound.
- Larger requests or payloads than usual.
- Limits set too low for the current workload.

## First checks
- Check whether the growth is steady (a leak) or follows traffic (load).
- Compare the start of the growth with recent deployments and configuration changes.
- Look for out-of-memory kills, restarts and long garbage collection pauses in the logs.
- Check the size of caches and queues held in memory.

## Mitigation
- Roll back a recent change if the growth started with it.
- Restart affected instances one at a time to recover capacity while investigating.
- Bound caches and queues, or lower their size limits.
- Raise memory limits only after ruling out a leak.

## Escalate when
- Memory keeps growing after a restart or rollback.
- Restarts cause failed requests for callers.
- More than one service shows memory pressure at the same time.
