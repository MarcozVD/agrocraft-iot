# Contexto de construcción: raíz del repositorio (reutiliza devices/fleet/base.py)
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1
WORKDIR /app
COPY platform/control/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY devices/fleet/base.py ./
COPY platform/control/*.py ./
COPY platform/control/static ./static
CMD ["python", "app.py"]
