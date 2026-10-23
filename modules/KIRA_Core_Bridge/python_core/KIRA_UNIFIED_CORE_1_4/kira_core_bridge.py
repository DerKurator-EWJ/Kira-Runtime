"""Localhost-only, token-authenticated event bridge. Python 3.9+, stdlib only."""
import argparse
import hashlib
import hmac
import json
import os
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCOPES = {"BASE", "PERSONAL", "DERIVED", "ORIGIN", "EPISODIC"}
KINDS = {"observation", "expectation", "action", "outcome", "reflection"}

class EventStore:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False, timeout=10)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS bridge_events (
            event_id TEXT PRIMARY KEY, timestamp TEXT NOT NULL,
            actor_id TEXT NOT NULL, kind TEXT NOT NULL, scope TEXT NOT NULL,
            payload TEXT NOT NULL, payload_hash TEXT NOT NULL,
            received_at TEXT NOT NULL)""")
        self.db.commit()

    def append(self, data):
        if not isinstance(data, dict): raise ValueError("event must be an object")
        required = ("event_id", "timestamp", "actor_id", "kind", "scope", "payload")
        if any(k not in data for k in required): raise ValueError("missing required event field")
        if not isinstance(data['event_id'], str): raise ValueError("invalid event_id")
        try: uuid.UUID(data['event_id'])
        except (ValueError, AttributeError): raise ValueError("event_id must be UUID")
        if not isinstance(data['timestamp'],str) or not data['timestamp'].strip(): raise ValueError("invalid timestamp")
        if not isinstance(data['actor_id'],str) or not 0 < len(data['actor_id']) <= 100: raise ValueError("invalid actor")
        if data['kind'] not in KINDS or data['scope'] not in SCOPES: raise ValueError("invalid kind or scope")
        if not isinstance(data['payload'],dict): raise ValueError("payload must be object")
        payload=json.dumps(data['payload'],ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
        digest=hashlib.sha256(payload.encode()).hexdigest()
        with self.lock, self.db:
            existing=self.db.execute('SELECT payload_hash,actor_id,kind,scope,timestamp FROM bridge_events WHERE event_id=?',(data['event_id'],)).fetchone()
            if existing:
                if existing != (digest,data['actor_id'],data['kind'],data['scope'],data['timestamp']):
                    raise RuntimeError('event ID conflict')
                return False
            self.db.execute('INSERT INTO bridge_events VALUES(?,?,?,?,?,?,?,?)',
                (data['event_id'],data['timestamp'],data['actor_id'],data['kind'],data['scope'],payload,digest,datetime.now(timezone.utc).isoformat()))
            return True

    def get(self, event_id):
        with self.lock:
            row=self.db.execute('SELECT event_id,timestamp,actor_id,kind,scope,payload FROM bridge_events WHERE event_id=?',(event_id,)).fetchone()
        return dict(zip(('event_id','timestamp','actor_id','kind','scope','payload'),row[:5]+(json.loads(row[5]),))) if row else None

    def recent(self,limit=20):
        with self.lock:
            ids=[r[0] for r in self.db.execute('SELECT event_id FROM bridge_events ORDER BY rowid DESC LIMIT ?', (limit,))]
        return [self.get(i) for i in ids]


def make_handler(store,token):
    class Handler(BaseHTTPRequestHandler):
        def reply(self,status,body):
            data=json.dumps(body,ensure_ascii=False).encode('utf-8')
            self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        def authorized(self):
            return hmac.compare_digest(self.headers.get('Authorization',''), 'Bearer '+token)
        def do_GET(self):
            if not self.authorized():return self.reply(401,{'error':'unauthorized'})
            if self.path=='/v1/health':return self.reply(200,{'status':'ok','version':'1.5.0'})
            if self.path=='/v1/events':return self.reply(200,{'events':store.recent()})
            if self.path.startswith('/v1/events/'):
                try: ident=str(uuid.UUID(self.path.split('/')[-1]))
                except ValueError:return self.reply(400,{'error':'invalid id'})
                event=store.get(ident)
                return self.reply(200,{'event':event}) if event else self.reply(404,{'error':'not found'})
            return self.reply(404,{'error':'not found'})
        def do_POST(self):
            if not self.authorized():return self.reply(401,{'error':'unauthorized'})
            if self.path!='/v1/events':return self.reply(404,{'error':'not found'})
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0 < size <= 65536: return self.reply(413,{'error':'body size out of range'})
                event=json.loads(self.rfile.read(size).decode('utf-8'))
                created=store.append(event)
                return self.reply(201 if created else 200,{'event_id':event['event_id'],'created':created})
            except RuntimeError as exc:return self.reply(409,{'error':str(exc)})
            except (ValueError,KeyError,TypeError,UnicodeDecodeError) as exc:return self.reply(400,{'error':str(exc)})
        def log_message(self,format,*args):pass
    return Handler

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--db',default='kira_bridge.sqlite3');parser.add_argument('--port',type=int,default=8765)
    args=parser.parse_args()
    token=os.environ.get('KIRA_BRIDGE_TOKEN','')
    if len(token)<24:parser.error('Set KIRA_BRIDGE_TOKEN to a random secret of at least 24 characters')
    server=ThreadingHTTPServer(('127.0.0.1',args.port),make_handler(EventStore(args.db),token))
    print('Kira bridge 1.5.0 listening on 127.0.0.1:'+str(args.port),flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
if __name__=='__main__':main()
