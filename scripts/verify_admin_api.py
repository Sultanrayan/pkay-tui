#!/usr/bin/env python3
"""End-to-end verification of the proxy admin HTTP API.

Spawns the release proxy with a temporary database, exercises every admin
endpoint, and reports PASS/FAIL per check.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

PORT = 8231
BASE = f"http://127.0.0.1:{PORT}"
PROXY = os.path.join(os.path.dirname(__file__), "..", "rust-core", "target", "release", "proxy-server.exe")

passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


def main():
    tmpdir = tempfile.mkdtemp(prefix="admin_api_test_")
    db_path = os.path.join(tmpdir, "db.sqlite")
    env = dict(os.environ, DATABASE_PATH=db_path, PORT=str(PORT))
    proc = subprocess.Popen([PROXY, "--config", "config.json"], cwd=".", env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # wait for startup
        for _ in range(50):
            try:
                urllib.request.urlopen(BASE + "/health", timeout=1)
                break
            except Exception:
                time.sleep(0.2)
        else:
            print("FAIL  proxy did not start")
            return 1

        # --- auth ---
        st, _ = req("GET", "/admin/providers")
        check("no token -> 401", st == 401)
        st, _ = req("POST", "/admin/login", {"username": "admin", "password": "wrong"})
        check("wrong password -> 401", st == 401)
        st, body = req("POST", "/admin/login", {"username": "admin", "password": "admin"})
        check("login -> 200 + token", st == 200 and "token" in body)
        token = body["token"]

        # --- providers ---
        st, body = req("POST", "/admin/providers", {"name": "mock", "url": "http://127.0.0.1:9999"}, token)
        check("add provider -> 201", st == 201)
        st, _ = req("POST", "/admin/providers", {"name": "mock", "url": "http://127.0.0.1:9999"}, token)
        check("duplicate provider -> 400", st == 400)

        st, body = req("POST", "/admin/providers/mock/keys", {"key": "sk-test-12345"}, token)
        check("add provider key -> 201", st == 201)
        st, body = req("POST", "/admin/providers/mock/models",
                       {"models": [{"name": "gpt-test"}, {"name": "gpt-test"}, {"name": "claude-test"}]}, token)
        check("import models -> 2 added (dup skipped)", st == 200 and body.get("imported") == 2)

        st, body = req("GET", "/admin/providers", token=token)
        ok = st == 200 and len(body["providers"]) == 1 and body["providers"][0]["models"] == ["claude-test", "gpt-test"]
        masked = body["providers"][0]["keys"][0]["key"] != "sk-test-12345" and "…" in body["providers"][0]["keys"][0]["key"]
        check("list providers (models sorted, key masked)", ok and masked)

        # --- users + keys ---
        st, _ = req("POST", "/admin/users", {"username": "bob", "password": "secret", "tier": "premium"}, token)
        check("add user -> 201", st == 201)
        st, _ = req("POST", "/admin/users", {"username": "bob", "password": "x"}, token)
        check("duplicate user -> 400", st == 400)

        st, body = req("POST", "/admin/users/bob/keys", {"tier": "premium"}, token)
        check("generate key -> 201 + pkay_", st == 201 and body["key"].startswith("pkay_"))
        user_key = body["key"]

        st, body = req("GET", "/admin/users", token=token)
        ok = st == 200 and body["users"][0]["username"] == "bob" and body["users"][0]["keys"][0]["key"] != user_key
        check("list users (key masked)", ok)

        # --- use the generated key through the proxy ---
        st, _ = req("POST", "/mock/chat/completions", {"model": "gpt-test", "messages": []}, user_key)
        check("proxy forwards with generated key (503 = no upstream, but authenticated)", st in (200, 502, 503))

        st, _ = req("POST", "/mock/chat/completions", {"model": "gpt-test"}, "pkay_bogus")
        check("bogus user key -> 401", st == 401)

        # --- revoke ---
        st, _ = req("POST", "/admin/keys/revoke", {"key": user_key}, token)
        check("revoke key -> 200", st == 200)
        st, _ = req("POST", "/mock/chat/completions", {"model": "gpt-test"}, user_key)
        check("revoked key -> 401", st == 401)

        # --- stats ---
        st, body = req("GET", "/admin/stats", token=token)
        check("stats -> counts present", st == 200 and body["users"] == 1 and body["user_keys"] == 1)

        # --- delete provider key + provider ---
        st, body = req("GET", "/admin/providers", token=token)
        key_id = body["providers"][0]["keys"][0]["id"]
        st, _ = req("DELETE", f"/admin/providers/mock/keys/{key_id}", token=token)
        check("delete provider key -> 200", st == 200)
        st, _ = req("DELETE", "/admin/providers/mock", token=token)
        check("delete provider -> 200", st == 200)
        st, body = req("GET", "/admin/providers", token=token)
        check("provider gone", st == 200 and body["providers"] == [])

        # --- logout ---
        st, _ = req("POST", "/admin/logout", token=token)
        check("logout -> 200", st == 200)
        st, _ = req("GET", "/admin/providers", token=token)
        check("token invalid after logout -> 401", st == 401)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())