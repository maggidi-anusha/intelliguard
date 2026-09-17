from fastapi import FastAPI

app = FastAPI(title="IntelliGuard ML Service")


@app.get("/health")
def health():
    return {"status": "ok"}
