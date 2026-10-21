# Portable inference server (optional, not deployed)

The default setup runs the model in **Google Colab** with the weights stored in **your Google Drive**
(`cloud/colab/TaskBaba_Colab_Server.ipynb`). Colab sessions are temporary. If you later want an always-on endpoint,
this folder contains a container you can deploy yourself. **Task Baba never creates cloud infrastructure for you,
and running this costs money.**

## What's inside

| File | Purpose |
| --- | --- |
| `Dockerfile` | CUDA 12.8 runtime base (pinned by digest) plus the official llama.cpp `b11355` CUDA build (SHA-256 checked) and the Task Baba proxy |
| `entrypoint.sh` | Verifies or downloads the model (resumable, SHA-256 required), starts `llama-server` on `127.0.0.1:8081` and the proxy on `$PORT` |
| `../proxy/taskbaba_proxy.py` | Bearer-key auth (constant-time), 16 KB body cap, max 200 tokens, field whitelist, rate limits, concurrency cap, no prompt logging |

Only the proxy listens on a public interface. `llama-server` stays on localhost behind its own random key.

## Build and run locally (needs an NVIDIA GPU and Docker)

```bash
docker build -f cloud/server/Dockerfile -t task-baba-inference .
docker run --gpus all -p 8080:8080 \
  -e TB_API_KEY="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')" \
  -e MODEL_URL="https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf" \
  -e MODEL_SHA256=7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5 \
  -v task-baba-models:/models task-baba-inference
```

Health checks: `GET /livez` (unauthenticated; returns only `ok`) and `GET /health` (authenticated; 200 = model loaded, 503 = loading).

## Getting the model from your Drive instead of Hugging Face

Drive is storage, not compute. Options:
1. **Hugging Face at the pinned revision** (simplest; the SHA-256 is the same as the Drive copy).
2. **A service account**: share only the `TaskBaba/models` folder with a service account that has no other access,
   copy the file to a bucket or volume once (for example with `rclone copy` using the `drive.readonly` scope), and mount it at `/models`.
   Don't make the Drive file public, and don't treat a sharing link as a download URL.

Either way the entrypoint refuses to start unless the file matches `MODEL_SHA256`.

## Deploying on Google Cloud (manual, your approval and billing)

**Cloud Run with GPU** (scale to zero):
```bash
gcloud secrets create taskbaba-api-key --data-file=-   # paste the key, then Ctrl-D
gcloud run deploy task-baba-inference --image REGION-docker.pkg.dev/PROJECT/REPO/task-baba-inference \
  --gpu 1 --gpu-type nvidia-l4 --cpu 4 --memory 16Gi --no-cpu-throttling \
  --min-instances 0 --max-instances 1 --concurrency 2 --timeout 120 \
  --set-secrets TB_API_KEY=taskbaba-api-key:latest \
  --set-env-vars MODEL_SHA256=7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5,MODEL_URL=https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf \
  --allow-unauthenticated   # the proxy does the authentication; Cloud Run IAM can't be used by the desktop app
```
Mount a Cloud Storage volume at `/models` so restarts don't re-download 2.5 GB.

**Compute Engine VM** (always on): a `g2-standard-4` (1× L4) or `n1-standard-4` + T4, the container-optimized OS,
the same container, and an HTTPS load balancer or Caddy in front for TLS.

### Rough costs (check current pricing before deploying)

| Option | Compute | Notes |
| --- | --- | --- |
| Colab free tier | $0 | Sessions end; GPU not guaranteed; URL changes each run |
| Cloud Run + L4, scale to zero | about $0.70–$1.00 per GPU-hour while an instance is up | Cold start 1–3 min (image pull + model load); idle = ~$0 plus storage |
| GCE T4 VM, always on | about $250–$400 per month | Plus disk (~$5/month) and egress |
| Storage | about $0.05/month for a 2.5 GB bucket | Drive storage counts against your Google quota |

Memory: Qwen3-4B Q4_K_M needs about 3 GB VRAM plus about 0.5 GB KV cache at 2048 context. Qwen3-1.7B Q8_0 runs on CPU (8 GB RAM) at a few tokens per second.

## Updates and rollback

* **Model**: deploy a new revision with the new `MODEL_URL` and `MODEL_SHA256`. The previous file is kept as `*.previous`
  on the volume, and on Cloud Run `gcloud run services update-traffic task-baba-inference --to-revisions PREV=100` rolls back instantly.
* **llama.cpp**: bump `LLAMA_BUILD`, `LLAMA_ASSET` and `LLAMA_SHA256` together (digests are on the GitHub release page), rebuild, deploy as a new revision.
* **API key rotation**: add a new secret version, redeploy, update the key in Task Baba, then disable the old version.

## Monitoring

* Uptime check on `/livez`. Alert on the 5xx rate and p95 latency from the platform's request logs.
* Proxy logs contain only `METHOD PATH STATUS LATENCY`. Prompts, replies, headers and keys are never logged.
* Keep `--max-instances` low and the proxy's `TB_RATE_PER_MIN` modest to bound spend if the key leaks, and rotate the key if you suspect it has.
