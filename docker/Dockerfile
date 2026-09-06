FROM eclipse-temurin:21-jdk-jammy AS jdk
FROM python:3.11-slim-bookworm
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
ENV PYTHONPATH=/artifact/clients/python:/artifact/scripts
ENV CUBLAS_WORKSPACE_CONFIG=:4096:8
WORKDIR /artifact
RUN apt-get update && apt-get install -y --no-install-recommends git make libgomp1 && rm -rf /var/lib/apt/lists/*
COPY env/requirements.lock env/requirements.lock
RUN pip install --no-cache-dir -r env/requirements.lock
ARG TORCH_INDEX=https://download.pytorch.org/whl/cu126
RUN pip install --no-cache-dir torch==2.13.0 --index-url ${TORCH_INDEX}
COPY . .
RUN chmod +x gradlew && ./gradlew :modules:aether-training-cache:paperRuntimeClasspath --no-daemon
CMD ["make", "artifact-smoke"]
