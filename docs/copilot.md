# Incident copilot (Phase 6B)

"Ask about this incident" in the incident workspace answers questions about **one incident**, using
**only that incident's data and the runbooks in [`docs/runbooks`](runbooks)**.

> It is a **retrieval-and-template assistant**, not a free-form chat model and not a source of new
> facts. Every sentence of an answer is either a fixed template filled with values from the
> incident, or a line copied verbatim from a runbook section. Answers cite their sources.

## How it works

1. **Context (backend-core).** `POST /api/incidents/{id}/copilot/ask {question}` builds the
   incident's context from the same data as the incident detail. That is the summary, the ranked
   root-cause candidates with score, evidence and confidence, and the affected services with their
   current risk. It also includes the anomaly episodes (up to 100), the newest 40 timeline entries,
   the newest 20 notes with their authors, and the security-event counts per service and type
   inside the incident's window. It sends them to ml-service `POST /copilot/ask` with the scoring
   client's HTTP/1.1 setup and 2 s timeout. Nothing is stored.
2. **Intent (rules).** Keyword patterns sort the question into: *root cause*, *what to do*
   (mitigate), *impact*, *timeline*, *confidence*, *notes* or *general*.
3. **Retrieval.** The runbooks are split into one chunk per section. Chunks are ranked by TF-IDF
   cosine similarity with the question (scikit-learn, with a light suffix stemmer). Two fixed
   boosts are added: one when a runbook's `appliesTo` matches the incident's metric or
   security-event types (more for the rank-1 service's types), and one for sections that fit the
   intent (e.g. *First checks* for "what should I check first"). There are no embeddings and no
   network calls.
4. **Answer.** Each intent has templates that use only context values (service names and numbers
   come only from the context). Root-cause answers always state the confidence and that the ranking
   is a heuristic. Runbook lines are quoted verbatim with their source. The response lists its
   citations (`INCIDENT_DATA` sections and `RUNBOOK` sections, each with an excerpt) and has
   `mode: "extractive"`. A `grounded` flag reports an automated check: every number, service name
   and citation id in the answer exists in the context or in a cited runbook.
5. **Refusal.** The copilot refuses (`refused: true` with a reason) in these cases:
   - **Off topic:** the question doesn't match the incident data or the runbooks.
   - **Instruction override:** the question tries to change its rules.
   - **Not in context:** it asks for facts the context doesn't hold (IP addresses, passwords,
     commits, who deployed, ...).
   - **Unknown service:** it asks about a service that isn't part of the incident.

**Notes are untrusted free text.** They are only ever quoted, with their author, when you ask
about the notes. Instructions written in a note are never followed.

## What it can and cannot do

| Can | Cannot |
|---|---|
| Explain the rank-1 service, its evidence, the runner-up and the confidence | Know anything outside this incident's data and the runbooks |
| Suggest first checks and mitigations, quoted from the runbook that fits the incident (or the question) | Diagnose beyond the heuristic ranking, or say whether the ranking is right |
| List the affected services with their current risk | Remember earlier questions (each answer is independent; answers stay in the page only) |
| Give the order of events, acknowledgement and resolution times | Summarise freely, reason across sources or answer questions its templates don't cover |
| Quote the team's notes with their authors | Act on anything: it reads only, for every role, VIEWER included |

API: `POST /api/incidents/{id}/copilot/ask` with `{"question": "..."}`. Any authenticated role is
allowed; there is no token → 401. A blank question or one over 500 characters → 400, and an
unknown incident → 404. If ml-service is unavailable the answer is 503 with a clear message, and the
rest of the workspace keeps working.

## Adding a runbook

1. Add `docs/runbooks/<id>.md` with front matter and `## ` sections made of `- ` bullet lines (only
   bullet lines are retrieved and quoted):

   ```markdown
   ---
   id: disk-full
   title: Disk full
   appliesTo: [DISK]
   ---
   # Disk full

   ## Symptoms
   - ...
   ## Likely causes
   ## First checks
   ## Mitigation
   ## Escalate when
   ```

   `appliesTo` takes metric types (`CPU`, `MEMORY`, `DISK`, `NETWORK`, `LATENCY`, `ERROR_RATE`),
   security-event types (`FAILED_LOGIN`, `PORT_SCAN`) or `MULTI_SERVICE_INCIDENT`. Keep runbooks
   generic: they are general guidance, not facts about this system.
2. Rebuild ml-service (`docker compose up -d --build ml-service`). The runbooks are copied into
   the image at build time (`RUNBOOKS_DIR`). Locally, ml-service reads `../docs/runbooks`.
3. Run `python evaluation/copilot_eval.py` from `ml-service/` to check that retrieval still finds
   the expected runbooks.

There is no `DISK` runbook yet, so disk anomalies get no runbook boost.

## Evaluation and limits

A small offline evaluation is in
[`evaluation/phase6-copilot.md`](evaluation/phase6-copilot.md): 6 handwritten incidents and 29
questions written by the project. On that set, 21/21 in-scope questions were classified correctly,
the expected runbook was in the top 3 for 14 of 15, 21/21 answers passed the groundedness check and
8/8 out-of-scope or injection questions were refused. These questions were written by the same
author as the rules, so the set is easy. There was no user study, and intent rules and TF-IDF
miss paraphrases that share no words with the rules or the runbooks.
