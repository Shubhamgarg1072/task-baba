# Task Baba inference server: llama.cpp + the Task Baba auth proxy.
# Portable alternative to the Colab notebook. NOT deployed by this project; see README.md.
#
# Build from the repository root:
#   docker build -f cloud/server/Dockerfile -t task-baba-inference .

# Pinned by digest (nvidia/cuda:12.8.1-runtime-ubuntu24.04).
FROM nvidia/cuda@sha256:ebef3c171eeef0298e4eb2e4be843105edf3b8b0ac45e0b43acee358e8046867

ARG LLAMA_BUILD=b11355
ARG LLAMA_ASSET=llama-b11355-bin-ubuntu-cuda-12.8-x64.tar.gz
ARG LLAMA_SHA256=cb0da078ea93c64a57138cb811df26099195bd8db7eef2fefd0661fc3e982d59

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl python3 tini \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/llama.tgz "https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_BUILD}/${LLAMA_ASSET}" \
 && echo "${LLAMA_SHA256}  /tmp/llama.tgz" | sha256sum -c - \
 && mkdir -p /opt \
 && tar -xzf /tmp/llama.tgz -C /opt \
 && mv "/opt/llama-${LLAMA_BUILD}" /opt/llama \
 && rm /tmp/llama.tgz

COPY cloud/proxy/taskbaba_proxy.py /opt/taskbaba/taskbaba_proxy.py
COPY cloud/server/entrypoint.sh /opt/taskbaba/entrypoint.sh
RUN chmod 755 /opt/taskbaba/entrypoint.sh \
 && useradd --system --create-home --home-dir /home/baba baba \
 && mkdir -p /models && chown baba /models

USER baba
ENV LD_LIBRARY_PATH=/opt/llama \
    MODEL_DIR=/models \
    PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=300s CMD curl -fsS "http://127.0.0.1:${PORT}/livez" || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/taskbaba/entrypoint.sh"]
