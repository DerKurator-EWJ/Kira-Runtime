"""KIRA 0.9.2: persistent loopback-only JSON API for the unified runtime.

Single runtime instance; a process-local lock serializes all SQLite access.
The token is required for ALL routes. Never expose this HTTP server to the Internet.
"""
import argparse
import hmac
import json
import os
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from kira_runtime_09 import KiraRuntime

MAX_REQUEST = 65536

class KiraService:
    def __init__(self, home, token):
        if not isinstance(token, str) or len(token) < 24:
            raise ValueError('API token must be at least 24 characters')
        self.runtime = KiraRuntime(home)
        self.token = token
        self.lock = threading.RLock()

    def dispatch(self, method, path, payload):
        with self.lock:
            if method == 'GET' and path == '/health':
                return {'status':'ok','version':'0.9.2'}
            if method == 'GET' and path == '/state':
                return {'state':self.runtime.state()}
            if method == 'GET' and path == '/verify':
                return {'verified_events':self.runtime.verify()}
            if method == 'GET' and path == '/history':
                return {'events':self.runtime.history(100)}
            if method == 'POST' and path == '/event':
                if not isinstance(payload,dict):raise ValueError('JSON object required')
                if set(payload) != {'id','kind','source','payload'}:raise ValueError('Expected id, kind, source, payload')
                return {'inserted':self.runtime.event(payload['id'],payload['kind'],payload['source'],payload['payload'])}
            if method == 'POST' and path == '/uui':
                if not isinstance(payload,dict) or set(payload) != {'source'} or not isinstance(payload['source'],str):
                    raise ValueError('Expected JSON object with source string')
                return {'output':self.runtime.execute(payload['source'])}
            raise LookupError('Unknown route')

    def close(self):
        with self.lock:self.runtime.close()


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Do not log API tokens or user-provided UUI content.
        def _reply(self, code, data):
            body=json.dumps(data,ensure_ascii=False,default=str).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store')
            self.end_headers()
            self.wfile.write(body)
        def _handle(self):
            token=self.headers.get('Authorization','')
            if not hmac.compare_digest(token, 'Bearer '+service.token):
                return self._reply(401,{'error':'unauthorized'})
            path=self.path
            if '?' in path:return self._reply(404,{'error':'unknown route'})
            payload=None
            if self.command=='POST':
                try:length=int(self.headers.get('Content-Length','-1'))
                except ValueError:length=-1
                if length<0 or length>MAX_REQUEST:return self._reply(413,{'error':'invalid request size'})
                try:payload=json.loads(self.rfile.read(length))
                except (ValueError,UnicodeDecodeError):return self._reply(400,{'error':'invalid JSON'})
            try:
                result=service.dispatch(self.command,path,payload)
            except LookupError:return self._reply(404,{'error':'unknown route'})
            except (ValueError,TypeError,KeyError,IndexError) as exc:
                return self._reply(400,{'error':type(exc).__name__,'detail':str(exc)[:300]})
            except Exception:
                return self._reply(500,{'error':'internal error'})
            return self._reply(200,result)
        def do_GET(self):self._handle()
        def do_POST(self):self._handle()
    return Handler


def create_server(home, token, port=8765):
    service=KiraService(home,token)
    try:server=ThreadingHTTPServer(('127.0.0.1',port),make_handler(service))
    except BaseException:service.close();raise
    server.kira_service=service
    return server

def main():
    parser=argparse.ArgumentParser(description='Kira local authenticated runtime')
    parser.add_argument('--home',default='kira_state')
    parser.add_argument('--port',type=int,default=8765)
    args=parser.parse_args()
    if not 1<=args.port<=65535:parser.error('port must be 1..65535')
    home=Path(args.home);home.mkdir(parents=True,exist_ok=True)
    token_path=home/'api_token.txt'
    if token_path.exists():token=token_path.read_text(encoding='utf-8').strip()
    else:
        token=secrets.token_urlsafe(32)
        fd=os.open(str(token_path),os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'w',encoding='utf-8') as f:f.write(token+'\n')
    server=create_server(home,token,args.port)
    print('Kira 0.9.2 on http://127.0.0.1:'+str(args.port),flush=True)
    print('Token stored in '+str(token_path)+' (do not share)',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close();server.kira_service.close()

if __name__=='__main__':main()
