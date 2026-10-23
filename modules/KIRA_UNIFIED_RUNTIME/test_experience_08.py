import json
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from fractions import Fraction
from kira_experience_08 import ExperienceStore

class ExperienceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=TemporaryDirectory()
        self.path=Path(self.tmp.name)/'events.sqlite'
        self.s=ExperienceStore(self.path)
    def tearDown(self):
        self.s.close();self.tmp.cleanup()
    def test_restart_and_exact_state(self):
        self.s.append('n1','node','runtime',{'name':'affect','owner':'EQ','state':'1.22','drift':'0.001'})
        self.s.append('t1','tick','clock',{})
        self.assertEqual(self.s.triad.graph.nodes['affect'].state,Fraction(1221,1000))
        self.s.close();self.s=ExperienceStore(self.path)
        self.assertEqual(self.s.triad.graph.nodes['affect'].state,Fraction(1221,1000))
        self.assertEqual(self.s.verify(),2)
    def test_duplicate_idempotency(self):
        p={'content':'observed rain','confidence':'1/2'}
        self.assertTrue(self.s.append('obs','observation','camera',p))
        self.assertFalse(self.s.append('obs','observation','camera',p))
        with self.assertRaises(ValueError):self.s.append('obs','observation','camera',{'content':'different'})
        self.assertEqual(len(self.s.events()),1)
    def test_failed_tick_atomic(self):
        self.s.append('n','node','runtime',{'name':'a','owner':'IQ','state':1})
        before=self.s.triad.snapshot()
        with self.assertRaises(KeyError):self.s.append('bad','tick','clock',{'impulses':{'missing':'1'}})
        self.assertEqual(before,self.s.triad.snapshot())
        self.assertEqual(len(self.s.events()),1)
    def test_provenance_and_no_auto_inference(self):
        self.s.append('o','observation','microphone',{'content':'sound event','confidence':'1/3'})
        self.assertEqual(len(self.s.triad.graph.nodes),0)
        self.assertEqual(self.s.events(source='microphone')[0]['payload']['content'],'sound event')
    def test_snapshot_history(self):
        self.s.append('n','node','runtime',{'name':'a','owner':'EQ','state':'1.22','low':'1','high':'2'})
        self.s.append('r','refinement','sensor',{'name':'a','low':'1.2','high':'1.3'})
        self.assertEqual(self.s.snapshot_at('n').knowledge['a'].low,Fraction(1))
        self.assertEqual(self.s.snapshot_at('r').knowledge['a'].low,Fraction(6,5))
    def test_integrity_detection(self):
        self.s.append('o','observation','camera',{'content':'image'})
        self.s.db.execute("UPDATE events SET source='forged' WHERE event_id='o'");self.s.db.commit()
        with self.assertRaises(ValueError):self.s.verify()
    def test_coactivation_is_only_heuristic(self):
        for n,owner in [('a','IQ'),('b','EQ')]:
            self.s.append('node_'+n,'node','runtime',{'name':n,'owner':owner,'state':1})
        self.s.append('t1','tick','clock',{})
        self.s.append('t2','tick','clock',{})
        self.assertEqual(len(self.s.triad.graph.links),2)
        self.assertEqual(len(self.s.events(kind='tick')),2)

if __name__=='__main__': unittest.main()
