#!/usr/bin/env bash
# Starts llama-server on localhost and the Task Baba proxy on $PORT.
#
# Required environment:
#   TB_API_KEY          client API key (inject from Secret Manager; never bake into the image)
# Model source (one of):
#   MODEL_URL           e.g. https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/<revision>/Qwen3-4B-Q4_K_M.gguf
#   or a file already present at $MODEL_DIR/$MODEL_FILE (e.g. a mounted volume / bucket synced from your Drive backup)
#   MODEL_FILE          default Qwen3-4B-Q4_K_M.gguf
#   MODEL_SHA256        required: expected SHA-256 of the model file
# Optional: CTX_SIZE (2048), MAX_TOKENS (200), GPU_LAYERS (99), TB_RATE_PER_MIN (30), TB_MAX_CONCURRENT (2)
set -euo pipefail

: "${TB_API_KEY:?TB_API_KEY must be provided (use a secret manager)}"
: "${MODEL_SHA256:?MODEL_SHA256 must be set so the model can be verified}"
MODEL_FILE="${MODEL_FILE:-Qwen3-4B-Q4_K_M.gguf}"
MODEL_PATH="${MODEL_DIR:-/models}/${MODEL_FILE}"
PORT="${PORT:-8080}"

verify() {
  echo "${MODEL_SHA256}  $1" | sha256sum -c --status -
}

if [[ -f "$MODEL_PATH" ]] && verify "$MODEL_PATH"; then
  echo "model verified: $MODEL_FILE"
else
  : "${MODEL_URL:?model missing or invalid and MODEL_URL not set}"
  echo "downloading model (resumable)…"
  curl -fL --retry 8 --retry-delay 5 -C - -o "${MODEL_PATH}.part" "$MODEL_URL"
  if ! verify "${MODEL_PATH}.part"; then
    echo "checksum mismatch; refusing to start" >&2
    rm -f "${MODEL_PATH}.part"
    exit 1
  fi
  [[ -f "$MODEL_PATH" ]] && mv -f "$MODEL_PATH" "${MODEL_PATH}.previous"
  mv -f "${MODEL_PATH}.part" "$MODEL_PATH"
fi

UPSTREAM_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"

/opt/llama/llama-server -m "$MODEL_PATH" --host 127.0.0.1 --port 8081 \
  --api-key "$UPSTREAM_KEY" -c "${CTX_SIZE:-2048}" -np 1 -n "${MAX_TOKENS:-200}" \
  -ngl "${GPU_LAYERS:-99}" --no-webui --no-slots --reasoning off --timeout 120 &
LLAMA_PID=$!

TB_UPSTREAM_KEY="$UPSTREAM_KEY" TB_UPSTREAM="http://127.0.0.1:8081" \
TB_LISTEN_HOST="0.0.0.0" TB_LISTEN_PORT="$PORT" \
  python3 /opt/taskbaba/taskbaba_proxy.py &
PROXY_PID=$!

shutdown() {
  kill -TERM "$PROXY_PID" "$LLAMA_PID" 2>/dev/null || true
  wait || true
}
trap shutdown TERM INT

# Exit (and let the platform restart us) if either process dies.
wait -n "$LLAMA_PID" "$PROXY_PID"
shutdown
exit 1
