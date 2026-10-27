FROM python:3.11-slim

WORKDIR /app
COPY mock_cas_server.py /app/mock_cas_server.py

EXPOSE 18080
CMD ["python", "/app/mock_cas_server.py"]
