import json
import tempfile
import unittest
import uuid
from pathlib import Path
from kira_core_bridge import EventStore

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=str(Path(self.temp.name)/'events.db');self.store=EventStore(self.path)
        self.event=dict(event_id=str(uuid.uuid4()),timestamp='2026-10-15T10:00:00Z',actor_id='Kira',kind='observation',scope='EPISODIC',payload={'text':'Oma ruft an'})
    def tearDown(self):self.store.db.close();self.temp.cleanup()
    def test_persist_and_restart(self):
        self.assertTrue(self.store.append(self.event));self.store.db.close();self.store=EventStore(self.path)
        self.assertEqual(self.store.get(self.event['event_id'])['payload'],self.event['payload'])
    def test_idempotent(self):
        self.assertTrue(self.store.append(self.event));self.assertFalse(self.store.append(self.event))
    def test_conflict(self):
        self.store.append(self.event);other={**self.event,'payload':{'text':'anders'}}
        with self.assertRaises(RuntimeError):self.store.append(other)
    def test_invalid(self):
        with self.assertRaises(ValueError):self.store.append({**self.event,'scope':'INVALID'})
if __name__=='__main__':unittest.main()
