#!/usr/bin/env python3
"""Deterministic local HTTP fixture for the Kiln conversation contract."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = (("search_offers", {"itemId": "item-1"}),
         ("get_quote", {"offerId": "offer-1"}),
         ("propose_purchase", {"quoteId": "quote-1"}))

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        try:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            stage = sum(message.get("role") == "tool" for message in request["messages"])
            if stage < 3:
                name, args = TOOLS[stage]
                assert [tool["function"]["name"] for tool in request["tools"]] == [name]
                choice = {"finish_reason": "tool_calls", "message": {"tool_calls": [{"id": f"fixture-{stage + 1}",
                          "type": "function", "function": {"name": name,
                          "arguments": json.dumps(args, separators=(",", ":"))}}]}}
            else:
                assert stage == 3 and "tools" not in request
                choice = {"finish_reason": "stop", "message": {"content": "Review only."}}
            result = {"model": "qwen3-32b", "choices": [choice],
                      "usage": {"prompt_tokens": 12, "completion_tokens": 6, "total_tokens": 18}}
            body = json.dumps(result).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Neocloud-Generation-Id", f"local-fixture-{stage + 1}")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (ValueError, KeyError, AssertionError, TypeError):
            self.send_error(400)
    def log_message(self, *_args):
        pass

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18082)
    args = parser.parse_args()
    print(f"local Kiln HTTP fixture listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
