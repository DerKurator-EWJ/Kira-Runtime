"""KIRA 0.8: transactional episodic event journal and TRIAD checkpoint.

Observations are attributed records, not proof of semantic understanding.
Each accepted event atomically persists its post-event graph snapshot.
"""
import hashlib
import json
import sqlite3
from pathlib import Path
from kira_triad_07 import AdaptiveTriad
from kira_triad import encode, q


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


class ExperienceStore:
    def __init__(self, path):
        self.path = str(Path(path))
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('PRAGMA busy_timeout=5000')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS events (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              event_id TEXT NOT NULL UNIQUE,
              kind TEXT NOT NULL,
              source TEXT NOT NULL,
              payload TEXT NOT NULL,
              prev_hash TEXT NOT NULL,
              event_hash TEXT NOT NULL,
              snapshot TEXT NOT NULL
            );
        ''')
        row = self.db.execute('SELECT snapshot FROM events ORDER BY seq DESC LIMIT 1').fetchone()
        self.triad = AdaptiveTriad.from_snapshot(json.loads(row[0])) if row else AdaptiveTriad()
        self.verify()

    def close(self):
        self.db.close()

    def verify(self):
        previous = 'GENESIS'
        rows = self.db.execute('SELECT seq,event_id,kind,source,payload,prev_hash,event_hash,snapshot FROM events ORDER BY seq')
        count = 0
        for seq, event_id, kind, source, payload, prev, digest, snapshot in rows:
            if prev != previous:
                raise ValueError('Event chain discontinuity')
            envelope = {'event_id':event_id,'kind':kind,'source':source,'payload':json.loads(payload),'snapshot':json.loads(snapshot),'prev_hash':prev}
            expected = hashlib.sha256(canonical(envelope).encode('utf-8')).hexdigest()
            if digest != expected:
                raise ValueError('Event chain integrity failure')
            previous = digest
            count += 1
        if count and self.triad.snapshot() != json.loads(self.db.execute('SELECT snapshot FROM events ORDER BY seq DESC LIMIT 1').fetchone()[0]):
            raise ValueError('Checkpoint mismatch')
        return count

    def append(self, event_id, kind, source, payload):
        if not isinstance(event_id, str) or not event_id or not isinstance(source,str) or not source:
            raise ValueError('event_id and source required')
        if kind not in ('node','link','observation','tick','refinement'):
            raise ValueError('Unsupported event kind')
        if not isinstance(payload, dict):
            raise TypeError('payload must be a dictionary')
        old = self.db.execute('SELECT kind,source,payload FROM events WHERE event_id=?',(event_id,)).fetchone()
        if old:
            if old != (kind, source, canonical(payload)):
                raise ValueError('Conflicting duplicate event_id')
            return False
        candidate = AdaptiveTriad.from_snapshot(self.triad.snapshot())
        normalized = dict(payload)
        if kind == 'node':
            candidate.add_node(payload['name'],payload['owner'],payload.get('state',0),payload.get('drift',0),payload.get('low'),payload.get('high'))
        elif kind == 'link':
            candidate.graph.link(payload['source'],payload['target'],payload['weight'],payload.get('plasticity',0))
        elif kind == 'tick':
            impulses = payload.get('impulses',{})
            if not isinstance(impulses,dict): raise TypeError('impulses must be a dictionary')
            candidate.step(impulses)
        elif kind == 'refinement':
            candidate.set_knowledge(payload['name'],payload['low'],payload['high'])
        elif kind == 'observation':
            # External observation is recorded as evidence, NOT an inferred state change.
            if not isinstance(payload.get('content'),str):
                raise ValueError('Observation requires string content')
            if 'confidence' in payload:
                confidence = q(payload['confidence'])
                if not 0 <= confidence <= 1: raise ValueError('Confidence outside [0,1]')
        snapshot = candidate.snapshot()
        last = self.db.execute('SELECT event_hash FROM events ORDER BY seq DESC LIMIT 1').fetchone()
        prev = last[0] if last else 'GENESIS'
        envelope = {'event_id':event_id,'kind':kind,'source':source,'payload':normalized,'snapshot':snapshot,'prev_hash':prev}
        digest = hashlib.sha256(canonical(envelope).encode('utf-8')).hexdigest()
        with self.db:
            self.db.execute('INSERT INTO events(event_id,kind,source,payload,prev_hash,event_hash,snapshot) VALUES(?,?,?,?,?,?,?)',
                            (event_id,kind,source,canonical(normalized),prev,digest,canonical(snapshot)))
        self.triad = candidate
        return True

    def events(self, kind=None, source=None, limit=100):
        if not isinstance(limit,int) or not 1 <= limit <= 10000: raise ValueError('Invalid limit')
        sql = 'SELECT seq,event_id,kind,source,payload,event_hash FROM events WHERE 1=1'
        params=[]
        if kind is not None: sql+=' AND kind=?';params.append(kind)
        if source is not None: sql+=' AND source=?';params.append(source)
        sql+=' ORDER BY seq DESC LIMIT ?';params.append(limit)
        return [{'seq':s,'event_id':i,'kind':k,'source':o,'payload':json.loads(p),'hash':h}
                for s,i,k,o,p,h in self.db.execute(sql,params)]

    def snapshot_at(self, event_id):
        row=self.db.execute('SELECT snapshot FROM events WHERE event_id=?',(event_id,)).fetchone()
        if not row: raise KeyError(event_id)
        return AdaptiveTriad.from_snapshot(json.loads(row[0]))
