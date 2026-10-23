import unittest
from fractions import Fraction
from tempfile import TemporaryDirectory
from pathlib import Path
from kira_relational_triad import RelationalTriad, demo

class RelationalTriadTests(unittest.TestCase):
    def test_direct_eq_iq_before_meta(self):
        t=demo();t.refine('affect','1.5');t.tick()
        self.assertEqual(t.nodes['reason'].state,Fraction(1,2))
    def test_internal_drift_without_external_event(self):
        t=demo();t.tick();self.assertEqual(t.nodes['affect'].state,Fraction(1,10000))
    def test_meta_lag(self):
        t=demo();t.refine('affect','1');t.tick()
        self.assertEqual(t.nodes['reflect'].state,Fraction(10001,80000))
        self.assertEqual(t.nodes['reason'].state,Fraction(1,3))
    def test_independent_nodes_and_dynamic_structure(self):
        t=demo();t.add_node('memory','EQ');t.link('memory','affect','2/3')
        t.tick({'memory':'3/2'});t.tick()
        self.assertEqual(t.nodes['affect'].state,Fraction(1)+Fraction(2,10000))
    def test_arbitrary_decimal_depth(self):
        t=demo();s='0.'+'0'*70+'1';t.refine('affect',s)
        self.assertEqual(t.nodes['affect'].state,Fraction(1,10**71))
    def test_no_float(self):
        t=demo()
        with self.assertRaises(TypeError):t.tick({'affect':0.1})
    def test_plasticity_updates_after_tick(self):
        t=demo();t.links[0].plasticity=Fraction(1,100)
        t.tick({'reason':1,'affect':1})
        self.assertNotEqual(t.links[0].weight,Fraction(1,4))
    def test_atomic_budget_failure(self):
        t=demo();t.max_bits=8;before=t.snapshot()
        with self.assertRaises(RuntimeError):t.tick({'affect':'0.'+'0'*40+'1'})
        self.assertEqual(t.snapshot(),before)
    def test_roundtrip_and_continuation(self):
        t=demo();t.tick({'reason':'1.221'});t.tick()
        with TemporaryDirectory() as d:
            p=Path(d)/'state.json';t.save(p);u=RelationalTriad.load(p)
            self.assertEqual(t.snapshot(),u.snapshot())
            self.assertEqual(t.tick(),u.tick())
    def test_owner_validation(self):
        t=demo()
        with self.assertRaises(ValueError):t.add_node('wrong','UNKNOWN')

if __name__=='__main__':unittest.main()
