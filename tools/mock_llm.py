"""本地假大模型服务器，用于验证 APK 的「搜索→喂资料→回答→存知识库」链路。

关键点：它会把收到的资料回显出来，这样就能在聊天记录里看到
网页摘要到底有没有真的传进模型。
"""
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        reply = "收到"
        try:
            req = json.loads(raw.decode("utf-8"))
            msgs = req.get("messages", [])
            system = msgs[0].get("content", "") if msgs else ""
            user = msgs[-1].get("content", "") if msgs else ""
            mode = "查资料" if "查资料助手" in system else "人设"
            has_style = "对方的说话习惯" in system
            hits = re.findall(r"^\[(\d+)\]\s*(.+)", user, re.MULTILINE)
            print(
                f"MODE={mode} STYLE_HINT={int(has_style)} HITS={len(hits)} "
                f"USER={user[:40]!r}",
                flush=True,
            )
            if hits:
                reply = f"{len(hits)}条资料，第一条：{hits[0][1][:36]}"
            else:
                reply = f"没人给资料，我只看到：{user[:30]}"
        except Exception as e:  # noqa: BLE001
            print(f"解析失败: {e}", flush=True)

        payload = json.dumps(
            {"choices": [{"message": {"role": "assistant", "content": reply}}]},
            ensure_ascii=False,
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()

    def log_message(self, fmt, *args):
        print("http:", fmt % args, flush=True)


if __name__ == "__main__":
    print("mock llm 0.0.0.0:8765", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()