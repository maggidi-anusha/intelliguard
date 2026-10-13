---
id: network-saturation
title: Network saturation
appliesTo: [NETWORK]
---
# Network saturation

## Symptoms
- Network throughput approaches the capacity of the interface or link.
- Packet loss, retransmissions and connection timeouts increase.
- Latency rises for every service that shares the link.

## Likely causes
- A traffic surge, legitimate or abusive.
- Large transfers such as backups, replication or bulk exports.
- Retry storms that multiply traffic between services.
- A misconfigured client or loop that resends data.

## First checks
- Identify the top talkers by source, destination and port.
- Check whether scheduled transfers such as backups are running.
- Compare request rates with normal levels to tell a surge from a few large flows.
- Check whether other services on the same link also show latency.

## Mitigation
- Pause or reschedule bulk transfers.
- Rate limit or block abusive sources.
- Reduce retries and add backoff on chatty clients.
- Add capacity or spread traffic once the source is understood.

## Escalate when
- Saturation continues after bulk transfers are paused.
- Traffic patterns suggest an attack.
- Saturation affects shared infrastructure beyond this service.
