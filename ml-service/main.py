from functools import lru_cache

from fastapi import FastAPI, HTTPException

from app.correlation import CorrelateRequest, correlate
from app.scoring import Scorer, ScoreRequest

app = FastAPI(title="IntelliGuard ML Service")


@lru_cache(maxsize=1)
def get_scorer() -> Scorer:
    return Scorer.load()


@app.get("/health")
def health():
    return {"status": "ok"}


# Internal only: backend-core calls this; the frontend never talks to ml-service directly.
@app.post("/score")
def score(request: ScoreRequest):
    return get_scorer().score(request)


# Heuristic, graph-based incident correlation and root-cause ranking (not a learned model).
@app.post("/correlate")
def correlate_episodes(request: CorrelateRequest):
    try:
        return correlate(request)
    except KeyError as e:
        raise HTTPException(status_code=422, detail=str(e))
