FROM python:3.11-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 SIMULATOR_PATH=/opt/simulator MODEL_DIR=/app/models
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# The simulator's offline generator, used only to produce normal training data at build time.
# Provided by compose as an extra build context (infra/docker-compose.yml: additional_contexts).
COPY --from=simulator . /opt/simulator
COPY . .
# Train the default detector (settings in app/model_config.json) and save it with joblib.
RUN python -m app.train
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
