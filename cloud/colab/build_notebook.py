#!/usr/bin/env python3
"""Generates TaskBaba_Colab_Server.ipynb. Edit this file, then run: python3 cloud/colab/build_notebook.py

The proxy source is embedded from cloud/proxy/taskbaba_proxy.py so there is a single implementation.
"""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROXY_SRC = open(os.path.join(HERE, "..", "proxy", "taskbaba_proxy.py"), encoding="utf-8").read()

cells = []


def md(text):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)})


def code(text):
    cells.append({
        "cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [],
        "source": text.strip("\n").splitlines(keepends=True),
    })


md("""
# 🧘 Task Baba: model server on Google Colab

This notebook keeps **Task Baba's language model in your Google Drive** and runs it on a Colab GPU, so nothing
heavy lives on your Mac.

**What it does**
1. Mounts your Drive and keeps the model in `MyDrive/TaskBaba/models` (downloaded once from Hugging Face at a pinned
   revision, resumable, SHA-256 verified before it becomes active; the previous model is kept in `backup/` for rollback).
2. Downloads a pinned, checksum-verified build of `llama.cpp` and starts `llama-server` on `127.0.0.1` only.
3. Starts a small auth/limits proxy (API key, 16 KB body limit, ≤200 tokens, rate limit, no prompt logging).
4. Opens a Cloudflare *quick tunnel* to the proxy and prints an `https://…trycloudflare.com` URL and an API key.
   Paste both into **Task Baba → AI & Model**.

**Please read: honest limits**
* **Drive only stores the file; Colab does the computing.** When this notebook stops, Task Baba falls back to its
  built-in lines automatically.
* Free Colab sessions disconnect when idle and have a maximum lifetime (often around 12 hours), and GPUs are not
  always available. Colab is designed for interactive use, not as an always-on server, so check the current
  [Colab FAQ / terms](https://research.google.com/colaboratory/faq.html) before relying on it.
* Cloudflare quick tunnels are meant for testing: no uptime guarantee, and **the URL changes every run**.
* Requests travel through Cloudflare and Google infrastructure. Task Baba only sends what you consented to in the app,
  and this proxy never logs prompts or replies.
* For dependable hosting, see `cloud/server/` in the repository (a paid server you would create yourself).

Use **Runtime → Change runtime type → T4 GPU** and then **Runtime → Run all**.
""")

code("""
# @title 1 · Settings { display-mode: "form" }
MODEL_CHOICE = "qwen3-4b-q4km"  # @param ["qwen3-4b-q4km", "qwen3-1.7b-q8"]
DRIVE_DIR = "/content/drive/MyDrive/TaskBaba/models"  # @param {type:"string"}
# Keep the same API key across sessions by saving it in Drive (TaskBaba/api_key.txt). Off = new key each run.
SAVE_KEY_TO_DRIVE = False  # @param {type:"boolean"}
CONTEXT_SIZE = 2048  # @param {type:"integer"}
MAX_TOKENS = 200  # hard cap, also enforced by the proxy

MODELS = {
    "qwen3-4b-q4km": {
        "repo": "Qwen/Qwen3-4B-GGUF", "revision": "bc640142c66e1fdd12af0bd68f40445458f3869b",
        "file": "Qwen3-4B-Q4_K_M.gguf", "size": 2497280256,
        "sha256": "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", "license": "Apache-2.0",
    },
    "qwen3-1.7b-q8": {
        "repo": "Qwen/Qwen3-1.7B-GGUF", "revision": "90862c4b9d2787eaed51d12237eafdfe7c5f6077",
        "file": "Qwen3-1.7B-Q8_0.gguf", "size": 1834426016,
        "sha256": "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", "license": "Apache-2.0",
    },
}

# Pinned third-party binaries (GitHub release assets) with their published SHA-256 digests.
LLAMA_BUILD = "b11355"
LLAMA_ASSETS = {
    "cuda": ("llama-b11355-bin-ubuntu-cuda-12.8-x64.tar.gz", "cb0da078ea93c64a57138cb811df26099195bd8db7eef2fefd0661fc3e982d59"),
    "cudart": ("cudart-llama-b11355-bin-ubuntu-cuda-12.8-x64.tar.gz", "619f42ab920777ceb05e96a840cb6941a1c3963118ad564de017f1ec3ef51f0f"),
    "cpu": ("llama-b11355-bin-ubuntu-x64.tar.gz", "4a21de9ce9f0cbb9cd2bb81d241919c8ab62c498e867a57bddbcf16cfcef1dbf"),
}
CLOUDFLARED_VERSION = "2026.10.0"
CLOUDFLARED_SHA256 = "d33ff2d14475178d2012c2c56beba87389ac5ded27649519f198a7d3134a99db"

spec = MODELS[MODEL_CHOICE]
print(f"Model: {spec['file']} ({spec['size']/1e9:.2f} GB, {spec['license']}) from {spec['repo']}@{spec['revision'][:12]}")
""")

code("""
# @title 2 · Mount Google Drive
import os
from google.colab import drive
drive.mount("/content/drive")
os.makedirs(os.path.join(DRIVE_DIR, "backup"), exist_ok=True)
print("Model folder:", DRIVE_DIR)
""")

code("""
# @title 3 · Helpers (download with resume, verify)
import hashlib, json, os, shutil, time, urllib.request, urllib.error

def sha256_file(path, chunk=8 * 1024 * 1024):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()

def download(url, dest, expected_size=None, retries=8):
    \"\"\"Download to dest with HTTP Range resume. Returns when the file has expected_size bytes.\"\"\"
    for attempt in range(retries):
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        if expected_size and have == expected_size:
            return
        if expected_size and have > expected_size:
            os.remove(dest); have = 0
        req = urllib.request.Request(url, headers={"User-Agent": "TaskBaba-Colab/1.0"})
        if have:
            req.add_header("Range", f"bytes={have}-")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                if have and r.status != 206:      # server ignored Range: start over
                    have = 0
                mode = "ab" if have else "wb"
                total = expected_size or int(r.headers.get("Content-Length", 0)) + have
                last = time.time()
                with open(dest, mode) as f:
                    while True:
                        b = r.read(4 * 1024 * 1024)
                        if not b:
                            break
                        f.write(b); have += len(b)
                        if time.time() - last > 10:
                            print(f"  {have/1e9:.2f} / {total/1e9:.2f} GB", flush=True); last = time.time()
            if not expected_size or os.path.getsize(dest) == expected_size:
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            print(f"  interrupted ({e.__class__.__name__}); resuming in {2 ** attempt}s…")
            time.sleep(2 ** attempt)
    raise RuntimeError(f"Download did not complete after {retries} attempts: {url}")

def verify_asset(path, sha):
    got = sha256_file(path)
    if got != sha:
        os.remove(path)
        raise RuntimeError(f"Checksum mismatch for {os.path.basename(path)}: expected {sha}, got {got}. File removed.")

def marker_path(path):
    return path + ".verified.json"

def is_verified(path, spec):
    m = marker_path(path)
    if not (os.path.exists(path) and os.path.exists(m)):
        return False
    try:
        info = json.load(open(m))
    except Exception:
        return False
    st = os.stat(path)
    return info.get("sha256") == spec["sha256"] and info.get("size") == st.st_size == spec["size"] and info.get("mtime") == int(st.st_mtime)

def write_marker(path, spec):
    st = os.stat(path)
    json.dump({"sha256": spec["sha256"], "size": st.st_size, "mtime": int(st.st_mtime),
               "repo": spec["repo"], "revision": spec["revision"], "license": spec["license"]}, open(marker_path(path), "w"))
print("Helpers ready.")
""")

code("""
# @title 4 · Install or verify the model in Drive
active = os.path.join(DRIVE_DIR, spec["file"])
part = active + ".part"
backup_dir = os.path.join(DRIVE_DIR, "backup")

def move_to_backup(path):
    dest = os.path.join(backup_dir, os.path.basename(path))
    if os.path.exists(dest):
        os.remove(dest)
    shutil.move(path, dest)
    if os.path.exists(marker_path(path)):
        shutil.move(marker_path(path), marker_path(dest))
    print("  kept previous copy in backup/:", os.path.basename(path))

if is_verified(active, spec):
    print("✅ Model already in Drive and verified:", spec["file"])
else:
    if os.path.exists(active):
        print("Checking the existing file (takes a minute for a few GB)…")
        if os.path.getsize(active) == spec["size"] and sha256_file(active) == spec["sha256"]:
            write_marker(active, spec)
            print("✅ Existing file verified.")
        else:
            bad = active + ".corrupt"
            os.replace(active, bad)
            print("⚠️ Existing file failed verification; moved aside as", os.path.basename(bad))
    if not is_verified(active, spec):
        url = f"https://huggingface.co/{spec['repo']}/resolve/{spec['revision']}/{spec['file']}"
        print(f"Downloading {spec['file']} into Drive (resumable)…")
        download(url, part, spec["size"])
        print("Verifying SHA-256…")
        got = sha256_file(part)
        if got != spec["sha256"]:
            os.remove(part)
            raise RuntimeError(f"Checksum mismatch (expected {spec['sha256']}, got {got}). The partial file was deleted; run this cell again.")
        # Keep any other catalog model that was active as the rollback copy.
        for other in MODELS.values():
            p = os.path.join(DRIVE_DIR, other["file"])
            if other["file"] != spec["file"] and os.path.exists(p):
                move_to_backup(p)
        os.replace(part, active)
        write_marker(active, spec)
        print("✅ Model installed and verified in Drive:", active)
""")

code("""
# @title 5 · (Optional) Restore the previous model from backup/
RESTORE = False  # @param {type:"boolean"}
if RESTORE:
    backups = [m for m in MODELS.values() if os.path.exists(os.path.join(backup_dir, m["file"]))]
    if not backups:
        print("No backup found.")
    else:
        b = backups[0]
        src = os.path.join(backup_dir, b["file"])
        print("Verifying backup before restoring…")
        if os.path.getsize(src) != b["size"] or sha256_file(src) != b["sha256"]:
            raise RuntimeError("The backup failed verification; not restoring it.")
        for m in MODELS.values():
            p = os.path.join(DRIVE_DIR, m["file"])
            if os.path.exists(p) and m["file"] != b["file"]:
                move_to_backup(p)
        dest = os.path.join(DRIVE_DIR, b["file"])
        shutil.move(src, dest)
        write_marker(dest, b)
        MODEL_CHOICE = [k for k, v in MODELS.items() if v is b][0]
        spec = b; active = dest
        print("✅ Restored", b["file"], "as the active model. Continue with cell 6.")
else:
    print("Skipped (tick RESTORE to roll back).")
""")

code("""
# @title 6 · (Optional) Remove model files from Drive
CONFIRM_REMOVE = False  # @param {type:"boolean"}
ALSO_REMOVE_BACKUP = False  # @param {type:"boolean"}
if CONFIRM_REMOVE:
    for p in [active, marker_path(active), part]:
        if os.path.exists(p):
            os.remove(p); print("Removed", p)
    if ALSO_REMOVE_BACKUP:
        for m in MODELS.values():
            for p in [os.path.join(backup_dir, m["file"]), marker_path(os.path.join(backup_dir, m["file"]))]:
                if os.path.exists(p):
                    os.remove(p); print("Removed", p)
    print("Done. Files go to Drive's trash, which Google empties after 30 days.")
else:
    print("Skipped (tick CONFIRM_REMOVE to delete).")
""")

code("""
# @title 7 · Install llama.cpp (pinned, checksum-verified)
import subprocess
gpu = subprocess.run(["nvidia-smi"], capture_output=True).returncode == 0
print("GPU runtime:" if gpu else "No GPU found; using the CPU build (slow, use the 1.7B model).")
base = f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_BUILD}/"
name, sha = LLAMA_ASSETS["cuda" if gpu else "cpu"]
os.makedirs("/content/llama", exist_ok=True)
tgz = f"/content/{name}"
if not os.path.exists(f"/content/llama/llama-{LLAMA_BUILD}/llama-server"):
    download(base + name, tgz)
    verify_asset(tgz, sha)
    subprocess.run(["tar", "-xzf", tgz, "-C", "/content/llama"], check=True)
LLAMA_DIR = f"/content/llama/llama-{LLAMA_BUILD}"
SERVER = os.path.join(LLAMA_DIR, "llama-server")
env = dict(os.environ, LD_LIBRARY_PATH=LLAMA_DIR + ":" + os.environ.get("LD_LIBRARY_PATH", ""))
v = subprocess.run([SERVER, "--version"], capture_output=True, text=True, env=env)
if v.returncode != 0 and gpu:
    print("Fetching the matching CUDA runtime libraries…")
    cname, csha = LLAMA_ASSETS["cudart"]
    ctgz = f"/content/{cname}"
    download(base + cname, ctgz)
    verify_asset(ctgz, csha)
    subprocess.run(["tar", "-xzf", ctgz, "-C", "/content/llama"], check=True)
    v = subprocess.run([SERVER, "--version"], capture_output=True, text=True, env=env)
if v.returncode != 0:
    raise RuntimeError("llama-server failed to start: " + (v.stderr or v.stdout)[-500:])
print("✅", (v.stdout or v.stderr).strip().splitlines()[-1])
""")

code("""
# @title 8 · Start llama-server (localhost only)
import secrets, urllib.request
local_model = "/content/model.gguf"
free = shutil.disk_usage("/content").free
if not (os.path.exists(local_model) and os.path.getsize(local_model) == spec["size"]):
    if free > spec["size"] * 1.2:
        print("Copying the model from Drive to the Colab VM's disk for faster loading…")
        shutil.copyfile(active, local_model)
    else:
        local_model = active  # not enough local disk: load straight from Drive (slower)
UPSTREAM_KEY = secrets.token_urlsafe(32)
try:
    llama_proc.terminate()
except NameError:
    pass
llama_log = open("/content/llama-server.log", "w")
llama_proc = subprocess.Popen([
    SERVER, "-m", local_model, "--host", "127.0.0.1", "--port", "8081",
    "--api-key", UPSTREAM_KEY, "-c", str(CONTEXT_SIZE), "-np", "1", "-n", str(MAX_TOKENS),
    "-ngl", "99" if gpu else "0", "--no-webui", "--no-slots", "--reasoning", "off", "--timeout", "120",
], stdout=llama_log, stderr=subprocess.STDOUT, env=env)

for i in range(180):
    try:
        with urllib.request.urlopen("http://127.0.0.1:8081/health", timeout=2) as r:
            if r.status == 200:
                print("✅ Model loaded."); break
    except Exception:
        pass
    if llama_proc.poll() is not None:
        raise RuntimeError("llama-server exited; see /content/llama-server.log")
    time.sleep(2)
else:
    raise RuntimeError("Model did not load within 6 minutes; see /content/llama-server.log")
""")

code("""
# @title 9 · Start the Task Baba auth proxy
PROXY_SOURCE = r'''
""" + PROXY_SRC.replace("'''", '"""') + """
'''
open("/content/taskbaba_proxy.py", "w").write(PROXY_SOURCE)

key_file = "/content/drive/MyDrive/TaskBaba/api_key.txt"
API_KEY = None
try:
    from google.colab import userdata          # optional Colab secret named TASKBABA_API_KEY
    API_KEY = userdata.get("TASKBABA_API_KEY")
except Exception:
    pass
if not API_KEY and SAVE_KEY_TO_DRIVE and os.path.exists(key_file):
    API_KEY = open(key_file).read().strip()
if not API_KEY:
    API_KEY = secrets.token_urlsafe(32)
    if SAVE_KEY_TO_DRIVE:
        with open(key_file, "w") as f:
            f.write(API_KEY)
        os.chmod(key_file, 0o600)

try:
    proxy_proc.terminate()
except NameError:
    pass
proxy_proc = subprocess.Popen(
    ["python3", "/content/taskbaba_proxy.py"],
    env=dict(os.environ, TB_API_KEY=API_KEY, TB_UPSTREAM="http://127.0.0.1:8081", TB_UPSTREAM_KEY=UPSTREAM_KEY,
             TB_LISTEN_HOST="127.0.0.1", TB_LISTEN_PORT="8080", TB_RATE_PER_MIN="30", TB_MAX_CONCURRENT="2"),
    stdout=open("/content/proxy.log", "w"), stderr=subprocess.STDOUT,
)
time.sleep(1.5)
with urllib.request.urlopen("http://127.0.0.1:8080/livez", timeout=5) as r:
    assert r.status == 200
print("✅ Proxy running on 127.0.0.1:8080 (logs: method, path, status, latency only).")
""")

code("""
# @title 10 · Open the HTTPS tunnel and print your endpoint
import re
cf = "/content/cloudflared"
if not os.path.exists(cf):
    download(f"https://github.com/cloudflare/cloudflared/releases/download/{CLOUDFLARED_VERSION}/cloudflared-linux-amd64", cf)
    verify_asset(cf, CLOUDFLARED_SHA256)
    os.chmod(cf, 0o755)
try:
    tunnel_proc.terminate()
except NameError:
    pass
tunnel_log = "/content/cloudflared.log"
tunnel_proc = subprocess.Popen([cf, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8080"],
                               stdout=open(tunnel_log, "w"), stderr=subprocess.STDOUT)
ENDPOINT = None
for _ in range(60):
    time.sleep(1)
    m = re.search(r"https://[a-z0-9-]+\\.trycloudflare\\.com", open(tunnel_log).read())
    if m:
        ENDPOINT = m.group(0); break
if not ENDPOINT:
    raise RuntimeError("Tunnel did not start; see /content/cloudflared.log")
print("Paste these into Task Baba → AI & Model:\\n")
print("  Endpoint URL:", ENDPOINT)
print("  API key:     ", API_KEY)
print("\\nKeep this tab open. The URL stops working when the session ends.")
""")

code("""
# @title 11 · Self-test through the tunnel
import json
time.sleep(3)
hdr = {"Authorization": "Bearer " + API_KEY, "Content-Type": "application/json"}
with urllib.request.urlopen(urllib.request.Request(ENDPOINT + "/health", headers=hdr), timeout=20) as r:
    print("health:", r.status)
body = json.dumps({"messages": [
    {"role": "system", "content": "You are Baba, a witty, kind monk. One short sentence."},
    {"role": "user", "content": "Remind me to drink water. /no_think"}],
    "max_tokens": 60, "chat_template_kwargs": {"enable_thinking": False}}).encode()
t = time.time()
with urllib.request.urlopen(urllib.request.Request(ENDPOINT + "/v1/chat/completions", data=body, headers=hdr), timeout=60) as r:
    reply = json.load(r)["choices"][0]["message"]["content"]
print(f"Baba says ({time.time() - t:.1f}s):", reply.strip())
""")

code("""
# @title 12 · Status / stop
STOP = False  # @param {type:"boolean"}
for name in ["tunnel_proc", "proxy_proc", "llama_proc"]:
    p = globals().get(name)
    state = "not started" if p is None else ("running" if p.poll() is None else f"exited ({p.returncode})")
    print(f"{name:12s} {state}")
    if STOP and p is not None and p.poll() is None:
        p.terminate(); print("  stopped")
""")

nb = {
    "nbformat": 4,
    "nbformat_minor": 5,
    "metadata": {
        "accelerator": "GPU",
        "colab": {"provenance": [], "gpuType": "T4", "name": "TaskBaba_Colab_Server.ipynb"},
        "kernelspec": {"display_name": "Python 3", "name": "python3"},
        "language_info": {"name": "python"},
    },
    "cells": cells,
}
for i, c in enumerate(nb["cells"]):
    c["id"] = f"cell-{i:02d}"

out = os.path.join(HERE, "TaskBaba_Colab_Server.ipynb")
with open(out, "w", encoding="utf-8") as f:
    json.dump(nb, f, indent=1, ensure_ascii=False)
    f.write("\n")
print("wrote", out)
