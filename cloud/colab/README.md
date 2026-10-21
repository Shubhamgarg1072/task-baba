# Running Baba's model in Google Colab

Your model file lives in **your Google Drive**. **Colab** runs it while the notebook is open, and the Mac only
sends small HTTPS requests. Nothing is downloaded to, or executed on, your Mac.

```
Task Baba (Mac) ──HTTPS + API key──▶ Cloudflare quick tunnel ──▶ proxy :8080 ──▶ llama-server 127.0.0.1:8081
                                                                   (Colab VM)        model loaded from MyDrive/TaskBaba/models
```

1. Open `TaskBaba_Colab_Server.ipynb` in Colab (**File → Upload notebook**, or open it from GitHub).
2. **Runtime → Change runtime type → T4 GPU**, then **Runtime → Run all**. Approve the Drive mount.
3. The first run downloads the pinned model (2.5 GB for Qwen3-4B Q4_K_M) into `MyDrive/TaskBaba/models`,
   resuming if interrupted, and activates it only after its SHA-256 matches. Later runs reuse it.
4. Cell 10 prints an **Endpoint URL** and an **API key**. Paste them into **Task Baba → AI & Model**, then press
   **Check connection** and **Test inference**.

**Rollback**: tick `RESTORE` in cell 5. **Remove**: tick `CONFIRM_REMOVE` in cell 6.
**Stable key across sessions**: set `SAVE_KEY_TO_DRIVE`, or add a Colab secret named `TASKBABA_API_KEY`.

Pinned components: llama.cpp `b11355` (official CUDA 12.8 or CPU build, SHA-256 checked) and cloudflared `2026.10.0`
(SHA-256 checked). Model licenses: Qwen3 models are Apache-2.0 (see the model card), separate from Task Baba's MIT license.

Limits: free Colab sessions end when idle or after their maximum lifetime, GPUs aren't guaranteed, and quick-tunnel
URLs change every run. Task Baba detects the outage through `/health` and falls back to built-in lines.

To change the notebook, edit `build_notebook.py` and run `python3 cloud/colab/build_notebook.py`.
