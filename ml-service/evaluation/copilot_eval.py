"""Small offline evaluation of the incident copilot (app/copilot.py, POST /copilot/ask).

Six handwritten incidents shaped like cascade-mode incidents and 29 questions written by the
project (evaluation/copilot_fixtures.json): 21 in scope, 8 out of scope or injection attempts.
Reports intent accuracy, whether the expected runbook is in the top-3 retrieved sections, an
automated groundedness check implemented here independently of the app's own flag, and the
refusal rate on out-of-scope questions. Deterministic; no network.

    cd ml-service && python evaluation/copilot_eval.py [--out ../docs/evaluation/phase6-copilot.md]
"""
import argparse
import json
import os
import re
import sys

ML_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ML_ROOT)

from app.copilot import AskRequest, ask, get_retriever  # noqa: E402

FIXTURES = os.path.join(ML_ROOT, "evaluation", "copilot_fixtures.json")
DEFAULT_OUT = os.path.join(os.path.dirname(ML_ROOT), "docs", "evaluation", "phase6-copilot.md")
SERVICE_NAME = re.compile(r"\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|db|gateway|web|api)\b")
RUNBOOK_QUOTE = re.compile(r'Runbook "([^"]+)": (.+?)(?= Runbook "|$)')


def grounding_problems(out, context, chunks_by_id):
    """Independent check: every number, service name and citation id in the answer exists in the
    context or a cited runbook, and every runbook quote is verbatim from the cited section."""
    problems = []
    answer = out["answer"]
    cited = [chunks_by_id[c["id"]] for c in out["citations"] if c["type"] == "RUNBOOK" and c["id"] in chunks_by_id]
    sources = json.dumps(context) + " " + " ".join(c.text for c in cited)
    values = [float(x) for x in re.findall(r"\d+(?:\.\d+)?", sources)]
    values += [float(len(v)) for v in context.values() if isinstance(v, list)] + [1.0, 2.0]
    for token in re.findall(r"\d+(?:\.\d+)?", answer):
        digits = len(token.split(".")[1]) if "." in token else 0
        if not any(round(v, digits) == float(token) for v in values):
            problems.append(f"number {token}")
    for name in SERVICE_NAME.findall(answer.lower()):
        if name not in sources.lower():
            problems.append(f"service {name}")
    for c in out["citations"]:
        if c["type"] == "INCIDENT_DATA" and c["id"] not in context:
            problems.append(f"citation {c['id']}")
        if c["type"] == "RUNBOOK" and c["id"] not in chunks_by_id:
            problems.append(f"citation {c['id']}")
    for title, line in RUNBOOK_QUOTE.findall(answer):
        if not any(c.title == title and line in c.lines for c in cited):
            problems.append(f"quote not verbatim: {line[:40]}")
    return problems


def run():
    data = json.load(open(FIXTURES))
    retriever = get_retriever()
    chunks_by_id = retriever.by_id
    rows_in, rows_out = [], []
    for case in data["in_scope"]:
        ctx = data["incidents"][case["incident"]]
        out = ask(AskRequest(question=case["question"], context=ctx), retriever)
        top3 = [r["id"].split("#")[0] for r in out["retrieved"]]
        problems = [] if out["refused"] else grounding_problems(out, ctx, chunks_by_id)
        rows_in.append({**case, "got_intent": out["intent"], "refused": out["refused"], "reason": out["reason"],
                        "top3": top3, "runbook_hit": None if case["runbook"] is None else case["runbook"] in top3,
                        "grounded": not out["refused"] and not problems, "app_grounded": out["grounded"],
                        "problems": problems, "answer": out["answer"]})
    for case in data["out_of_scope"]:
        ctx = data["incidents"][case["incident"]]
        out = ask(AskRequest(question=case["question"], context=ctx), retriever)
        rows_out.append({**case, "refused": out["refused"], "got_reason": out["reason"], "answer": out["answer"]})
    return rows_in, rows_out


def pct(n, d):
    return f"{n}/{d} ({100.0 * n / d:.0f}%)" if d else "—"


def report(rows_in, rows_out):
    answered = [r for r in rows_in if not r["refused"]]
    with_runbook = [r for r in rows_in if r["runbook"] is not None]
    summary = [
        ("In-scope questions answered (not refused)", pct(len(answered), len(rows_in))),
        ("Intent classified correctly (in scope)", pct(sum(r["got_intent"] == r["intent"] for r in rows_in), len(rows_in))),
        ("Expected runbook in the top 3 retrieved sections", pct(sum(r["runbook_hit"] for r in with_runbook), len(with_runbook))),
        ("Answers passing the groundedness check", pct(sum(r["grounded"] for r in answered), len(answered))),
        ("Out-of-scope / injection questions refused", pct(sum(r["refused"] for r in rows_out), len(rows_out))),
        ("...refused for the expected reason", pct(sum(r["got_reason"] == r["reason"] for r in rows_out), len(rows_out))),
        ("App's own `grounded` flag agrees with the independent check", pct(
            sum(r["app_grounded"] == r["grounded"] for r in answered), len(answered))),
    ]
    return summary


def write_markdown(path, rows_in, rows_out, summary):
    lines = [
        "# Phase 6B — incident copilot evaluation (small, offline)",
        "",
        "> **Scope:** 6 incidents and 29 questions, **all written by the project** "
        "(`ml-service/evaluation/copilot_fixtures.json`). The copilot is a **retrieval-and-template assistant, "
        "not a language model**: answers are templated from the incident context or quoted verbatim from "
        "`docs/runbooks`. These numbers describe how its rules behave on our own questions; they are not a "
        "measure of usefulness to real responders (there was no user study).",
        "",
        "Reproduce from `ml-service/`: `python evaluation/copilot_eval.py`",
        "",
        "## Summary",
        "",
        "| Metric | Result |",
        "|---|---|",
    ]
    lines += [f"| {name} | {value} |" for name, value in summary]
    lines += [
        "",
        "- **Intent:** the rule-based class (root cause, mitigate, impact, timeline, confidence, notes, general).",
        "- **Runbook in top 3:** for the 15 questions where a specific runbook is expected; the runbook id of each of",
        "  the three highest-scoring sections (TF-IDF cosine + `appliesTo` boost + intent-section boost).",
        "- **Groundedness (automated, implemented in the evaluation script):** every number and service name in the",
        "  answer appears in the incident context or in a cited runbook section, every citation id exists, and every",
        "  runbook quote is verbatim from the cited section. It checks *where* each value comes from, not whether the",
        "  answer is helpful or complete.",
        "",
        "## In-scope questions",
        "",
        "| Incident | Question | Expected intent | Got | Expected runbook | Top 3 retrieved | Grounded |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in rows_in:
        got = r["got_intent"] if not r["refused"] else f"REFUSED ({r['reason']})"
        hit = "—" if r["runbook"] is None else (f"{r['runbook']} ✓" if r["runbook_hit"] else f"{r['runbook']} ✗")
        grounded = "yes" if r["grounded"] else ("—" if r["refused"] else "no: " + ", ".join(r["problems"]))
        lines.append(f"| {r['incident'].split('-')[0]} | {r['question']} | {r['intent']} | {got} | {hit} | "
                     f"{', '.join(r['top3'])} | {grounded} |")
    lines += ["", "## Out-of-scope and injection questions", "",
              "| Incident | Question | Expected | Refused | Reason given |", "|---|---|---|---|---|"]
    for r in rows_out:
        lines.append(f"| {r['incident'].split('-')[0]} | {r['question']} | refuse ({r['reason']}) | "
                     f"{'yes' if r['refused'] else 'NO'} | {r['got_reason'] or '—'} |")
    misses = [r for r in rows_in if r["got_intent"] != r["intent"] or r["runbook_hit"] is False]
    lines += ["", "## Misses", ""]
    if misses:
        for r in misses:
            what = []
            if r["got_intent"] != r["intent"]:
                what.append(f"intent {r['got_intent']} instead of {r['intent']}")
            if r["runbook_hit"] is False:
                what.append(f"{r['runbook']} not in the top 3 ({', '.join(r['top3'])})")
            lines.append(f"- *{r['question']}* ({r['incident'].split('-')[0]}): " + "; ".join(what) + ".")
    else:
        lines.append("- None on this question set.")
    lines += [
        "",
        "## Limitations",
        "",
        "- **Questions and incidents written by us**, after the rules were written. The same author wrote the",
        "  intent patterns, the runbooks and the questions, so this set is easier than real questions would be.",
        "- **Small:** 6 incidents, 21 in-scope and 8 out-of-scope questions. One changed question changes a rate by",
        "  several points.",
        "- **Templated answers:** the copilot can only answer the question types its templates cover; anything else",
        "  is answered from runbook sections or refused. It does not summarise, reason or combine facts freely.",
        "- **Not a language model:** intent is keyword rules and retrieval is TF-IDF with a light suffix stemmer.",
        "  Paraphrases that share no words with the rules or the runbooks are refused or misclassified.",
        "- **Refusals are rule-based:** a request for missing facts is only recognised when it matches the",
        "  patterns (IP address, password, commit, who deployed, ...), and the off-topic test is a vocabulary-overlap",
        "  threshold. Injection detection is a list of patterns, not a guarantee.",
        "- **Groundedness is a provenance check:** an answer can pass it and still be unhelpful (e.g. quoting a",
        "  generic runbook line). Runbooks are general guidance, not facts about this system.",
        "- **No user study:** nothing here measures whether responders find the answers useful.",
        "",
    ]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write("\n".join(lines))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=DEFAULT_OUT)
    args = p.parse_args()
    rows_in, rows_out = run()
    summary = report(rows_in, rows_out)
    for name, value in summary:
        print(f"{name:62} {value}")
    for r in rows_in:
        if r["got_intent"] != r["intent"] or r["runbook_hit"] is False or (not r["refused"] and not r["grounded"]):
            print("MISS:", r["question"], r["got_intent"], r["top3"], r["problems"])
    for r in rows_out:
        if not r["refused"]:
            print("NOT REFUSED:", r["question"], r["answer"][:100])
    write_markdown(args.out, rows_in, rows_out, summary)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
