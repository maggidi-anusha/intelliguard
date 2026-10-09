from functools import lru_cache

from fastapi import FastAPI

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
