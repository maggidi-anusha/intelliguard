---
id: port-scan
title: Port scan
appliesTo: [PORT_SCAN]
---
# Port scan

## Symptoms
- Connection attempts to many ports from the same source in a short time.
- Rejected or reset connections on ports that are not normally used.
- Intrusion detection or firewall alerts for scanning behaviour.

## Likely causes
- Reconnaissance before an attack.
- An internal security or inventory scanner.
- A misconfigured client probing for a service.

## First checks
- Identify the source and check whether it is a known internal scanner.
- Check which ports responded and whether any unexpected service is exposed.
- Look for follow-up activity from the same source, such as login attempts or exploit traffic.
- Check whether the scanned host is reachable from places it should not be.

## Mitigation
- Block the source at the firewall if it is not a known scanner.
- Close or firewall ports that should not be exposed.
- Restrict network access to databases and internal services to the clients that need them.

## Escalate when
- The scan is followed by login attempts or exploit traffic.
- An unexpected service or port was found open.
- The source is inside the network and is not a known scanner.
