#!/usr/bin/env python3
"""Share a PC folder with a phone's browser, no app needed on the phone.

Run:   python pc_share.py "D:\\GTAV Android" 8090
Phone: open http://PC_IP:8090/?k=KEY in any browser (same Wi-Fi). The key is printed at start.
Only the standard library is used, so nothing has to be installed on Windows.
"""
import html, http.server, os, secrets, socket, sys, urllib.parse

root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.getcwd())
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8090
key = secrets.token_hex(3)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def safe(rel):
    p = os.path.abspath(os.path.join(root, rel.lstrip("/\\")))
    if p != root and not p.startswith(root + os.sep):
        raise PermissionError("outside the shared folder")
    return p


class Handler(http.server.BaseHTTPRequestHandler):
    def _q(self):
        u = urllib.parse.urlparse(self.path)
        return u.path, urllib.parse.parse_qs(u.query)

    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _auth(self, q):
        if q.get("k", [""])[0] != key:
            self._send(403, "Forbidden: open the link with ?k=KEY from the PC window")
            return False
        return True

    def do_GET(self):
        path, q = self._q()
        if not self._auth(q):
            return
        rel = q.get("p", ["/"])[0]
        try:
            target = safe(rel)
        except PermissionError as e:
            return self._send(403, str(e))
        if os.path.isfile(target):
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(os.path.getsize(target)))
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + urllib.parse.quote(os.path.basename(target)))
            self.end_headers()
            with open(target, "rb") as f:
                while chunk := f.read(1 << 20):
                    self.wfile.write(chunk)
            return
        if not os.path.isdir(target):
            return self._send(404, "Not found")
        rows = []
        if target != root:
            parent = os.path.dirname(rel.rstrip("/\\")) or "/"
            rows.append(f'<a href="?k={key}&p={urllib.parse.quote(parent)}">.. (up)</a>')
        for name in sorted(os.listdir(target), key=lambda n: (not os.path.isdir(os.path.join(target, n)), n.lower())):
            full = os.path.join(target, name)
            link = rel.rstrip("/\\") + "/" + name
            size = "" if os.path.isdir(full) else f" <small>{os.path.getsize(full) // 1024} KB</small>"
            label = html.escape(name) + ("/" if os.path.isdir(full) else "")
            rows.append(f'<a href="?k={key}&p={urllib.parse.quote(link)}">{label}</a>{size}')
        page = (
            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>PC share</title><style>body{font:16px system-ui;margin:16px;background:#fff;color:#111}"
            "a{display:block;padding:10px 4px;border-bottom:1px solid #eee;color:#0067c0;text-decoration:none}</style>"
            f"<h3>{html.escape(root)}{html.escape(rel)}</h3>" + "".join(rows)
        )
        self._send(200, page)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"Sharing {root}")
    print(f"Phone browser: http://{lan_ip()}:{port}/?k={key}")
    print("Ctrl+C to stop.")
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
