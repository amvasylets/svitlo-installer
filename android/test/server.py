# Імітація бота: /p/3.2/espN → 200 OK, інші черги → 404. Кожен запит пишемо в hits.log (час, шлях, User-Agent).
import http.server, sys, time
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        ok = self.path.startswith("/p/3.2/") or self.path.startswith("/extra")
        with open("hits.log", "a") as f: f.write(f"{time.time():.1f} {self.path} {self.headers.get('User-Agent','')}\n")
        b = b"OK" if ok else b"unknown queue"
        self.send_response(200 if ok else 404); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
