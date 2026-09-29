#!/usr/bin/env python3
"""Local-only merchant contract fixture. Never represents a real merchant or payment."""
import argparse
import json
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path)
        query = parse_qs(path.query)
        if path.path == "/offers" and query.get("itemId") == ["item-1"]:
            result = {"offers": [{"offerId": "offer-1", "itemId": "item-1"}]}
        elif path.path == "/quotes" and query.get("offerId") == ["offer-1"]:
            expiry = (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat().replace("+00:00", "Z")
            result = {"quoteId": "quote-1", "offerId": "offer-1", "itemId": "item-1",
                      "totalCost": "9.50", "currency": "TEST_USDC", "recipient": "merchant_good",
                      "expiresAt": expiry}
        else:
            self.send_error(404)
            return
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *_args):
        pass

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    print(f"local_test_merchant listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
