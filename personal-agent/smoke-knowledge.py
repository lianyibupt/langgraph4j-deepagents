#!/usr/bin/env python3
"""Core HTTP acceptance using an isolated synthetic PERSONAL_KNOWLEDGE_ROOT fixture only."""
import json
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8082"


def api(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + "/api/" + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def refused(body, expected):
    try:
        api("knowledge/runs", body)
    except urllib.error.HTTPError as error:
        assert error.code == expected, (error.code, error.read().decode())
    else:
        raise AssertionError("request unexpectedly started a model run")


state = api("knowledge")
assert Path(state["stats"]["root"]).parent.name.startswith("personal-knowledge-web-test-"), "Use an isolated synthetic test project, never the user's vault"
assert state["configured"] and state["stats"]["documents"] == 2, state
assert state["stats"]["skipped"].get("older") == 1, state
search = api("knowledge/search", {"query": "Agent 上下文", "folder": ""})
assert len(search["matches"]) == 1, search
match = search["matches"][0]
preview = api("knowledge/preview", {"id": match["id"], "version": search["version"]})
assert preview["path"] == match["path"] and any("仅供测试" in line["text"] for line in preview["lines"])
body = {"query": search["query"], "folder": "", "version": search["version"], "documentIds": [match["id"]],
        "question": "整理 Agent 上下文资料并注明来源。", "provider": "deepseek", "confirmExternal": False}
refused(body, 400)
refused({**body, "provider": "replay", "documentIds": []}, 400)
refused({**body, "provider": "replay", "query": "不可能命中的词"}, 400)
print("PASS local recent-only search and preview; no selection or no consent cannot start a model")

run = api("knowledge/runs", {**body, "provider": "replay"})
run_id = run["run"]["id"]
for _ in range(120):
    run = api("runs/" + run_id)
    if run["run"]["status"] not in ("RUNNING", "QUEUED"):
        break
    time.sleep(.1)
assert run["run"]["status"] == "COMPLETED", run
assert "[[" + match["path"] + ":" in run["answer"], run
assert "未选中标记" not in json.dumps(run, ensure_ascii=False)
assert all(any(event["text"].startswith(prefix) for event in run["events"]) for prefix in ("knowledge.search", "knowledge.selected", "knowledge.snapshot"))
assert run["background"].find(match["path"]) >= 0
snapshot = Path(run["inputPath"])
assert [str(path.relative_to(snapshot)) for path in snapshot.rglob("*.md")] == [match["path"]]
assert Path(state["stats"]["root"], match["path"]).read_bytes() == (snapshot / match["path"]).read_bytes()
print("PASS selected knowledge runs through visible retrieval events, real tools and cited publication")

source = Path(state["stats"]["root"], match["path"])
source.write_text(source.read_text() + "\n更新后的资料需要重新预览。\n")
refused({**body, "provider": "replay"}, 409)
print("PASS changed original is rejected before another model run")
print(json.dumps({"run": run_id, "snapshot": str(snapshot)}, ensure_ascii=False))
