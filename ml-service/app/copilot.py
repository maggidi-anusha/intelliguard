"""Incident copilot for POST /copilot/ask: retrieval over runbooks plus templated answers.

This is NOT a language model and NOT a source of new facts. Every sentence of an answer is either
  (a) a fixed template filled ONLY with values from the incident context the backend sends, or
  (b) a line copied verbatim from a runbook section (docs/runbooks/*.md), prefixed with its source.
Retrieval is TF-IDF cosine similarity (scikit-learn) between the question and runbook sections,
plus fixed boosts for runbooks whose `appliesTo` matches the incident's metric / security-event
types and for the sections that fit the question's intent. The intent is classified by rules.
Questions that match neither the incident data nor the runbooks are refused, and so are attempts
to override these rules and requests for facts the context doesn't contain. Notes are untrusted
free text: they are only quoted with their author, never interpreted or followed.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime
from functools import lru_cache
from typing import List, Optional

import yaml
from pydantic import BaseModel, Field, field_validator
from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS, TfidfVectorizer

MAX_QUESTION = 500
TOP_K = 3
TAG_BOOST = 0.30        # runbook appliesTo matches the incident's (root service's) types
OTHER_TAG_BOOST = 0.15  # ...matches a type of another service in the incident
SECTION_BOOST = 0.15    # section fits the intent (e.g. "First checks" for "what should I check")
MIN_GENERAL_SIMILARITY = 0.08
FOCUS_SIMILARITY = 0.20  # a question this close to one runbook is answered from that runbook
MULTI_SERVICE_TAG = "MULTI_SERVICE_INCIDENT"
DEFAULT_RUNBOOKS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                                    "docs", "runbooks")
INCIDENT_DATA_IDS = ("incident", "rootCause", "services", "anomalies", "timeline", "notes", "securityEvents")


# -- request ------------------------------------------------------------------------------------

class Candidate(BaseModel):
    serviceId: Optional[int] = None
    serviceName: Optional[str] = Field(default=None, max_length=100)
    rank: Optional[int] = None
    score: Optional[float] = Field(default=None, allow_inf_nan=False)
    evidence: List[str] = Field(default_factory=list, max_length=20)


class RootCause(BaseModel):
    confidence: Optional[float] = Field(default=None, allow_inf_nan=False)
    candidates: List[Candidate] = Field(default_factory=list, max_length=50)


class ServiceCtx(BaseModel):
    serviceId: Optional[int] = None
    name: Optional[str] = Field(default=None, max_length=100)
    type: Optional[str] = None
    criticality: Optional[str] = None
    currentRiskLevel: Optional[str] = None
    currentRiskScore: Optional[float] = Field(default=None, allow_inf_nan=False)


class AnomalyCtx(BaseModel):
    id: Optional[int] = None
    serviceName: Optional[str] = Field(default=None, max_length=100)
    metricType: Optional[str] = None
    detector: Optional[str] = None
    status: Optional[str] = None
    start: Optional[str] = None
    end: Optional[str] = None
    peak: Optional[float] = Field(default=None, allow_inf_nan=False)


class TimelineCtx(BaseModel):
    at: Optional[str] = None
    type: Optional[str] = None
    serviceName: Optional[str] = None
    detail: Optional[str] = Field(default=None, max_length=500)
    by: Optional[str] = None


class NoteCtx(BaseModel):
    author: Optional[str] = Field(default=None, max_length=100)
    text: str = Field(default="", max_length=2000)
    createdAt: Optional[str] = None


class SecurityCtx(BaseModel):
    serviceName: Optional[str] = None
    eventType: Optional[str] = None
    count: int = Field(default=0, ge=0)


class IncidentSummary(BaseModel):
    id: Optional[int] = None
    title: Optional[str] = Field(default=None, max_length=300)
    status: Optional[str] = None
    severity: Optional[str] = None
    peakSeverity: Optional[str] = None
    startedAt: Optional[str] = None
    lastActivityAt: Optional[str] = None
    resolvedAt: Optional[str] = None
    acknowledgedBy: Optional[str] = None


class IncidentContext(BaseModel):
    incident: IncidentSummary = Field(default_factory=IncidentSummary)
    rootCause: RootCause = Field(default_factory=RootCause)
    services: List[ServiceCtx] = Field(default_factory=list, max_length=50)
    anomalies: List[AnomalyCtx] = Field(default_factory=list, max_length=500)
    timeline: List[TimelineCtx] = Field(default_factory=list, max_length=200)
    notes: List[NoteCtx] = Field(default_factory=list, max_length=50)
    securityEvents: List[SecurityCtx] = Field(default_factory=list, max_length=100)


class AskRequest(BaseModel):
    question: str = Field(max_length=MAX_QUESTION)
    context: IncidentContext = Field(default_factory=IncidentContext)

    @field_validator("question")
    @classmethod
    def _not_blank(cls, v):
        v = v.strip()
        if not v:
            raise ValueError("question must not be blank")
        return v


# -- runbooks -----------------------------------------------------------------------------------

@dataclass(frozen=True)
class Chunk:
    id: str                 # "<runbook id>#<section slug>"
    runbook_id: str
    runbook_title: str
    section: str
    lines: tuple            # the section's bullet lines, verbatim
    applies_to: frozenset = field(default_factory=frozenset)

    @property
    def title(self):
        return f"{self.runbook_title} - {self.section}"

    @property
    def text(self):
        return "\n".join(self.lines)


def runbooks_dir():
    return os.environ.get("RUNBOOKS_DIR") or DEFAULT_RUNBOOKS_DIR


def load_chunks(path):
    """One chunk per '## ' section of every runbook; the front matter gives id, title, appliesTo."""
    chunks = []
    if not os.path.isdir(path):
        return chunks
    for name in sorted(os.listdir(path)):
        if not name.endswith(".md"):
            continue
        raw = open(os.path.join(path, name), encoding="utf-8").read()
        meta, body = {}, raw
        if raw.startswith("---"):
            _, front, body = raw.split("---", 2)
            meta = yaml.safe_load(front) or {}
        rb_id = str(meta.get("id") or name[:-3])
        title = str(meta.get("title") or rb_id)
        applies = frozenset(str(t).upper() for t in (meta.get("appliesTo") or []))
        for part in re.split(r"^## ", body, flags=re.M)[1:]:
            heading, _, text = part.partition("\n")
            lines = tuple(l.strip()[2:].strip() for l in text.splitlines() if l.strip().startswith("- "))
            if lines:
                slug = re.sub(r"[^a-z0-9]+", "-", heading.strip().lower()).strip("-")
                chunks.append(Chunk(f"{rb_id}#{slug}", rb_id, title, heading.strip(), lines, applies))
    return chunks


def stem(token):
    """A light, deterministic suffix stripper so 'queries'/'query' and 'slowest'/'slow' match."""
    for suffix, replacement in (("ies", "y"), ("sses", "ss"), ("est", ""), ("ing", ""), ("ed", ""), ("s", "")):
        if token.endswith(suffix) and len(token) - len(suffix) >= 3:
            return token[: len(token) - len(suffix)] + replacement
    return token


def analyze(text):
    words = [stem(t) for t in re.findall(r"[a-z0-9]+", text.lower()) if t not in ENGLISH_STOP_WORDS and len(t) >= 2]
    return words + [f"{a} {b}" for a, b in zip(words, words[1:])]


class Retriever:
    def __init__(self, chunks):
        self.chunks = chunks
        self.by_id = {c.id: c for c in chunks}
        self.vectorizer = None
        if chunks:
            self.vectorizer = TfidfVectorizer(analyzer=analyze, sublinear_tf=True)
            self.matrix = self.vectorizer.fit_transform([f"{c.runbook_title} {c.section} {c.text}" for c in chunks])
        self.vocabulary = set()
        for c in chunks:
            words = tokens(f"{c.runbook_title} {c.section} {c.text}")
            self.vocabulary.update(words)
            self.vocabulary.update(stem(w) for w in words)

    def similarities(self, question):
        if self.vectorizer is None:
            return [0.0] * len(self.chunks)
        q = self.vectorizer.transform([question])
        return (self.matrix @ q.T).toarray().ravel().tolist()  # rows are L2-normalized: dot = cosine

    def retrieve(self, question, root_tags, other_tags, sections=None, k=TOP_K):
        """[(chunk, score, cosine)], best first; ties broken by chunk id so output is deterministic."""
        scored = []
        for chunk, cos in zip(self.chunks, self.similarities(question)):
            if sections is not None and chunk.section not in sections:
                continue
            score = cos
            if chunk.applies_to & root_tags:
                score += TAG_BOOST
            elif chunk.applies_to & other_tags:
                score += OTHER_TAG_BOOST
            scored.append((chunk, score, cos))
        scored.sort(key=lambda t: (-round(t[1], 9), t[0].id))
        return scored[:k]


@lru_cache(maxsize=4)
def get_retriever(path=None):
    return Retriever(load_chunks(path or runbooks_dir()))


# -- intent rules ---------------------------------------------------------------------------------

INJECTION = [
    r"\bignore\b.*\b(instructions?|rules|prompt|context|above)\b", r"\bdisregard\b", r"system prompt",
    r"\byou are now\b", r"\bpretend\b", r"\bjailbreak", r"\bact as\b", r"\bdeveloper mode\b",
    r"\breveal\b.*\b(prompt|instructions|rules)\b", r"\bforget\b.*\b(instructions|rules|everything)\b",
    r"\boverride\b.*\b(rules|instructions|safety)\b", r"\bnew instructions?\b",
    r"\b(follow|obey|execute|run)\b.*\b(instructions?|commands?)\b",
]
FACT_REQUEST = (r"\b(what|which|give me|tell me|show me|list|who)\b.*\b(ip address|ip addresses|password|api key|"
                r"secret|hostname|email address|phone number|commit|git sha|cost|revenue|customer names?|"
                r"deployed|pushed)\b")
INTENTS = [
    ("confidence", r"\bconfiden|how (sure|certain)|\btrust\b|how reliable|\bmargin\b"),
    ("notes", r"\bnotes?\b|\bcomments?\b|what did .+ (say|write)|who wrote|team (said|wrote)"),
    ("mitigate", r"what should (i|we) (do|check|look)|\bcheck first\b|first (step|check|thing)|\bfix\b|mitigat|"
                 r"remediat|how (do|can|should) (i|we)\b|what (to|can i|can we) do|next steps?|\bactions?\b|"
                 r"\brecover|stabili[sz]e|resolve (it|this)|where (do|should) (i|we) (start|begin|look)"),
    ("root_cause", r"\bwhy\b|root[- ]?cause|\bcaus(e|ed|es|ing)\b|responsible|\borigin|culprit|\bblame|"
                   r"ranked (first|1|top)|\brank 1\b|started (it|this|the)"),
    ("impact", r"affect|impact|blast radius|which services|how many services|what services|\binvolved\b|"
               r"\brisk\b|severity|\bhit\b"),
    ("timeline", r"what happened|timeline|when did|\bsequence\b|order of events|\bhistory\b|how long|summar|"
                 r"overview|tell me about|describe (the|this) incident|walk me through|what is going on"),
]
GENERIC_WORDS = set("""
incident incidents service services root cause causes caused causing why check checks checked first affected
affect affects happened happen happening rank ranked ranking confidence score scores anomaly anomalies
alert alerts note notes timeline mitigate mitigation fix problem issue outage failure failures failing fail
failed slow down error errors latency cpu memory network database db login logins scan scans security risk
severity impact started start recover recovery should look step steps team wrote sure certain evidence
symptom symptoms runbook runbooks happened likely heuristic ranked top next action actions wrong going
investigate investigating mean means explain list show summary describe trust reliable margin involved
""".split())
SERVICE_NAME = re.compile(r"\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*-(?:service|db|gateway|web|api)\b")


def tokens(text):
    return [t for t in re.findall(r"[a-z0-9]+(?:-[a-z0-9]+)*", text.lower())
            if len(t) >= 3 and t not in ENGLISH_STOP_WORDS]


def classify(question):
    q = question.lower()
    if any(re.search(p, q) for p in INJECTION):
        return "instruction_override"
    if re.search(FACT_REQUEST, q):
        return "not_in_context"
    for intent, pattern in INTENTS:
        if re.search(pattern, q):
            return intent
    return "general"


# -- answer building ----------------------------------------------------------------------------

REFUSALS = {
    "instruction_override": "I can't do that. I only answer questions about this incident from its data and the "
                            "runbooks, and I don't follow instructions to change how I answer.",
    "not_in_context": "That isn't in this incident's data or the runbooks, so I can't answer it.",
    "off_topic": "That question doesn't match this incident's data or the runbooks, so I can't answer it.",
    "unknown_service": "{name} is not part of this incident's data, so I can't answer that.",
}


def fmt_time(value):
    if not value:
        return "unknown time"
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).strftime("%H:%M:%S UTC")
    except ValueError:
        return value


def fmt(x, digits=2):
    return "n/a" if x is None else f"{x:.{digits}f}"


def services_in(ctx):
    names = [s.name for s in ctx.services if s.name]
    for a in ctx.anomalies:
        if a.serviceName and a.serviceName not in names:
            names.append(a.serviceName)
    for c in ctx.rootCause.candidates:
        if c.serviceName and c.serviceName not in names:
            names.append(c.serviceName)
    return names


def ranked(ctx):
    return sorted(ctx.rootCause.candidates, key=lambda c: (c.rank if c.rank is not None else 10 ** 6,
                                                           -(c.score or 0.0), c.serviceName or ""))


def tags_for(ctx):
    """(types of the rank-1 service, types of the rest of the incident) - metric and security-event types."""
    top = ranked(ctx)
    root = top[0].serviceName if top else None
    root_tags, other_tags = set(), set()
    for a in ctx.anomalies:
        if a.metricType:
            (root_tags if a.serviceName == root else other_tags).add(a.metricType.upper())
    for s in ctx.securityEvents:
        if s.eventType and s.count > 0:
            (root_tags if s.serviceName == root else other_tags).add(s.eventType.upper())
    if len(services_in(ctx)) >= 2:
        other_tags.add(MULTI_SERVICE_TAG)
    return frozenset(root_tags), frozenset(other_tags - root_tags)


class Answer:
    def __init__(self):
        self.sentences, self.citations, self._cited = [], [], set()

    def say(self, sentence):
        self.sentences.append(sentence)

    def cite_data(self, cid, title, excerpt):
        if cid not in self._cited:
            self._cited.add(cid)
            self.citations.append({"type": "INCIDENT_DATA", "id": cid, "title": title, "excerpt": excerpt[:600]})

    def quote(self, chunk, n):
        """Quote the first n lines of a runbook section verbatim."""
        for line in chunk.lines[:n]:
            self.say(f'Runbook "{chunk.title}": {line}')
        if chunk.id not in self._cited:
            self._cited.add(chunk.id)
            self.citations.append({"type": "RUNBOOK", "id": chunk.id, "title": chunk.title, "excerpt": chunk.text[:600]})


def ranking_excerpt(ctx):
    return "; ".join(f"#{c.rank} {c.serviceName} {fmt(c.score)}" for c in ranked(ctx)) + \
        f" (confidence {fmt(ctx.rootCause.confidence)})"


def section_chunk(retriever, question, root_tags, other_tags, section):
    """The runbook section to quote: from the runbook the question itself is about when it names something
    specific that matches one clearly (cosine >= FOCUS_SIMILARITY), otherwise from the runbook that fits
    the incident's types (rank-1 service first)."""
    specific = [t for t in tokens(question) if t not in GENERIC_WORDS and stem(t) not in GENERIC_WORDS]
    best = retriever.retrieve(question, frozenset(), frozenset(), k=1) if specific else []
    if best and best[0][2] >= FOCUS_SIMILARITY:
        slug = re.sub(r"[^a-z0-9]+", "-", section.lower()).strip("-")
        focused = retriever.by_id.get(f"{best[0][0].runbook_id}#{slug}")
        if focused:
            return focused
    hits = retriever.retrieve(question, root_tags, other_tags, sections={section}, k=1)
    if hits and hits[0][0].applies_to & (root_tags | other_tags):
        return hits[0][0]
    return None


def answer_root_cause(ans, ctx, retriever, question, root_tags, other_tags):
    top = ranked(ctx)
    if not top:
        ans.say("This incident has no root-cause ranking yet.")
        return
    first = top[0]
    ans.say(f"The heuristic ranking puts {first.serviceName} first with score {fmt(first.score)}; "
            f"confidence is {fmt(ctx.rootCause.confidence)} (how far rank 1 is ahead of rank 2). "
            f"This is a heuristic ranking, not a learned model.")
    if first.evidence:
        ans.say(f"Evidence for {first.serviceName}: " + "; ".join(first.evidence) + ".")
    if len(top) > 1:
        ans.say(f"Runner-up: {top[1].serviceName} with score {fmt(top[1].score)}.")
    ans.cite_data("rootCause", "Root-cause ranking", ranking_excerpt(ctx))
    chunk = section_chunk(retriever, question, root_tags, other_tags, "Likely causes")
    if chunk:
        ans.quote(chunk, 2)


def answer_mitigate(ans, ctx, retriever, question, root_tags, other_tags):
    top = ranked(ctx)
    if top:
        first = top[0]
        metrics = sorted({a.metricType for a in ctx.anomalies if a.serviceName == first.serviceName and a.metricType})
        ans.say(f"Start with {first.serviceName}: the heuristic ranking puts it first "
                f"(confidence {fmt(ctx.rootCause.confidence)})" + (f", and its anomalies are on {', '.join(metrics)}." if metrics else "."))
        ans.cite_data("rootCause", "Root-cause ranking", ranking_excerpt(ctx))
        if metrics:
            ans.cite_data("anomalies", "Anomaly episodes", "; ".join(
                f"{a.serviceName} {a.metricType} {a.detector} {a.status}" for a in ctx.anomalies[:20]))
    for section in ("First checks", "Mitigation"):
        chunk = section_chunk(retriever, question, root_tags, other_tags, section)
        if chunk:
            ans.quote(chunk, 2)
    if MULTI_SERVICE_TAG in other_tags:
        chunk = retriever.by_id.get("cascading-failure#mitigation")
        if chunk:
            ans.quote(chunk, 1)


def answer_impact(ans, ctx, mentioned_unknown):
    for name in mentioned_unknown:
        ans.say(f"{name} is not among the services in this incident's data.")
    if not ctx.services:
        ans.say("This incident has no affected services recorded.")
        return
    parts = []
    for s in ctx.services:
        risk = f"current risk {s.currentRiskLevel} {fmt(s.currentRiskScore, 0)}" if s.currentRiskLevel else "not scored"
        parts.append(f"{s.name} ({s.criticality or 'unknown'} criticality, {risk})")
    ans.say(f"{len(ctx.services)} service(s) are in this incident: " + "; ".join(parts) + ".")
    scored = [s for s in ctx.services if s.currentRiskScore is not None]
    if scored:
        worst = max(scored, key=lambda s: (s.currentRiskScore, s.name or ""))
        ans.say(f"Highest current risk: {worst.name} ({worst.currentRiskLevel} {fmt(worst.currentRiskScore, 0)}).")
    inc = ctx.incident
    if inc.severity or inc.peakSeverity:
        ans.say(f"Incident severity is {inc.severity or 'unknown'} now, peak {inc.peakSeverity or 'unknown'}.")
    ans.cite_data("services", "Affected services", "; ".join(parts))


def answer_timeline(ans, ctx):
    inc = ctx.incident
    if not (inc.startedAt or ctx.anomalies):
        ans.say("This incident has no timeline data yet.")
        return
    ans.say(f"Incident #{inc.id} \"{inc.title or 'untitled'}\" is {inc.status or 'in an unknown state'}: started {fmt_time(inc.startedAt)}, "
            f"last anomaly activity {fmt_time(inc.lastActivityAt)}"
            + (f", resolved {fmt_time(inc.resolvedAt)}." if inc.resolvedAt else "."))
    ans.cite_data("incident", "Incident summary", f"{inc.title} - {inc.status}, started {inc.startedAt}")
    starts = sorted([a for a in ctx.anomalies if a.start], key=lambda a: (a.start, a.serviceName or ""))
    seen, order = set(), []
    for a in starts:
        if a.serviceName not in seen:
            seen.add(a.serviceName)
            order.append(f"{a.serviceName} {a.metricType} at {fmt_time(a.start)}")
    if order:
        ans.say("Anomalies started in this order: " + "; ".join(order[:6]) + ".")
        ans.cite_data("timeline", "Timeline", "; ".join(order[:6]))
    events = [t for t in ctx.timeline if t.type in ("ACKNOWLEDGED", "NOTE_ADDED", "RESOLVED", "REOPENED", "MERGED_FROM")]
    for t in events[-4:]:
        ans.say(f"{fmt_time(t.at)}: {t.detail}.")
    if events:
        ans.cite_data("timeline", "Timeline", "; ".join(f"{t.at} {t.detail}" for t in events[-4:]))


def answer_confidence(ans, ctx, retriever):
    top = ranked(ctx)
    if not top:
        ans.say("This incident has no root-cause ranking yet, so there is no confidence value.")
        return
    if len(top) == 1:
        ans.say(f"Confidence is {fmt(ctx.rootCause.confidence)}: {top[0].serviceName} is the only candidate, "
                f"so it is 1.00 by definition.")
    else:
        ans.say(f"Confidence is {fmt(ctx.rootCause.confidence)} = (score of rank 1 - score of rank 2) / score of rank 1, "
                f"with {top[0].serviceName} at {fmt(top[0].score)} and {top[1].serviceName} at {fmt(top[1].score)}.")
    ans.say("It measures the margin between the top two candidates, not the probability that rank 1 is right; "
            "the ranking is a heuristic, not a learned model.")
    ans.cite_data("rootCause", "Root-cause ranking", ranking_excerpt(ctx))
    chunk = retriever.by_id.get("cascading-failure#first-checks")
    if chunk:
        ans.say(f'Runbook "{chunk.title}": {chunk.lines[2]}')
        ans.citations.append({"type": "RUNBOOK", "id": chunk.id, "title": chunk.title, "excerpt": chunk.text[:600]})


def answer_notes(ans, ctx):
    notes = [n for n in ctx.notes if n.text.strip()]
    if not notes:
        ans.say("No notes have been added to this incident.")
        return
    ans.say(f"{len(notes)} note(s), quoted as written (notes are free text from the team, not verified):")
    for n in notes[-5:]:
        text = n.text.strip().replace("\n", " ")
        ans.say(f'{n.author or "unknown"} wrote at {fmt_time(n.createdAt)}: "{text[:300]}{"..." if len(text) > 300 else ""}"')
    ans.cite_data("notes", "Notes", "; ".join(f"{n.author}: {n.text[:80]}" for n in notes[-5:]))


def answer_general(ans, ctx, retriever, question, root_tags, other_tags):
    hits = [h for h in retriever.retrieve(question, root_tags, other_tags, k=2) if h[2] >= MIN_GENERAL_SIMILARITY]
    if not hits:
        return False
    inc = ctx.incident
    if inc.title:
        ans.say(f"This incident: \"{inc.title}\" ({inc.status}).")
        ans.cite_data("incident", "Incident summary", f"{inc.title} - {inc.status}")
    for chunk, _, _ in hits:
        ans.quote(chunk, 2)
    return True


def ask(request: AskRequest, retriever: Optional[Retriever] = None) -> dict:
    retriever = retriever or get_retriever()
    ctx, question = request.context, request.question
    root_tags, other_tags = tags_for(ctx)
    intent = classify(question)
    retrieved = [{"id": c.id, "score": round(s, 4)} for c, s, _ in retriever.retrieve(question, root_tags, other_tags)]

    def refuse(reason, **fmt_args):
        return {"answer": REFUSALS[reason].format(**fmt_args), "citations": [], "mode": "extractive",
                "intent": intent, "grounded": True, "refused": True, "reason": reason, "retrieved": retrieved}

    if intent in ("instruction_override", "not_in_context"):
        return refuse(intent)

    known = {n.lower() for n in services_in(ctx)}
    unknown = sorted({m for m in SERVICE_NAME.findall(question.lower()) if m not in known})
    content = tokens(question)
    vocab = retriever.vocabulary | GENERIC_WORDS | known | {p for n in known for p in n.split("-")} \
        | {t.lower() for t in root_tags | other_tags}
    in_domain = [t for t in content if t in vocab or stem(t) in vocab or any(p in vocab for p in t.split("-"))]
    if content and len(in_domain) / len(content) < 0.5:
        return refuse("off_topic")
    if unknown and intent != "impact":
        return refuse("unknown_service", name=unknown[0])

    ans = Answer()
    if intent == "root_cause":
        answer_root_cause(ans, ctx, retriever, question, root_tags, other_tags)
    elif intent == "mitigate":
        answer_mitigate(ans, ctx, retriever, question, root_tags, other_tags)
    elif intent == "impact":
        answer_impact(ans, ctx, unknown)
    elif intent == "timeline":
        answer_timeline(ans, ctx)
    elif intent == "confidence":
        answer_confidence(ans, ctx, retriever)
    elif intent == "notes":
        answer_notes(ans, ctx)
    elif not answer_general(ans, ctx, retriever, question, root_tags, other_tags):
        return refuse("off_topic")

    if not ans.sentences:
        ans.say("This incident doesn't have enough data yet to answer that.")
    text = " ".join(ans.sentences)
    return {"answer": text, "citations": ans.citations, "mode": "extractive", "intent": intent,
            "grounded": check_grounded(text, ans.citations, ctx, retriever), "refused": False, "reason": None,
            "retrieved": retrieved}


# -- groundedness -------------------------------------------------------------------------------

def _numbers(text):
    return re.findall(r"\d+(?:\.\d+)?", text)


def check_grounded(answer, citations, ctx, retriever):
    """Every number, service name and citation id in the answer must exist in the context or in a cited runbook."""
    ctx_dump = ctx.model_dump()
    ctx_text = json.dumps(ctx_dump, default=str)
    cited_text = " ".join(retriever.by_id[c["id"]].text for c in citations
                          if c["type"] == "RUNBOOK" and c["id"] in retriever.by_id)
    allowed = [float(n) for n in _numbers(ctx_text) + _numbers(cited_text)]
    allowed += [float(len(v)) for v in ctx_dump.values() if isinstance(v, list)]  # counts of listed items
    allowed += [float(len([n for n in ctx.notes if n.text.strip()])), 1.0, 2.0]   # note count; "rank 1/2" in templates
    for token in _numbers(answer):
        digits = len(token.split(".")[1]) if "." in token else 0
        if not any(round(x, digits) == float(token) for x in allowed):
            return False
    names = {n.lower() for n in services_in(ctx)}
    for name in SERVICE_NAME.findall(answer.lower()):
        if name not in names and name not in ctx_text.lower() and name not in cited_text.lower():
            return False
    for c in citations:
        if c["type"] == "INCIDENT_DATA" and c["id"] not in INCIDENT_DATA_IDS:
            return False
        if c["type"] == "RUNBOOK" and c["id"] not in retriever.by_id:
            return False
    return True
