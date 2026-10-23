import unittest
import tempfile
from pathlib import Path
from fractions import Fraction
from kira_triad import Triad

class TriadTests(unittest.TestCase):
    def test_direct_eq_affects_iq_without_meta(self):
        t=Triad(eq=Fraction(3,2),meta_gain=0,meta_learning=0)
        t.tick()
        self.assertEqual(t.iq,Fraction(1,2))
    def test_direct_iq_affects_eq_without_meta(self):
        t=Triad(iq=2,meta_gain=0,meta_learning=0)
        t.tick()
        self.assertEqual(t.eq,Fraction(1,2))
    def test_internal_without_stimulus(self):
        t=Triad();e=t.tick(internal='0.001')
        self.assertTrue(e['unexplained_internal'])
        self.assertEqual(t.eq,Fraction(1,1000))
        self.assertEqual(t.iq,0)
    def test_meta_lags_direct(self):
        t=Triad();t.tick(internal=1)
        self.assertEqual(t.eq,1)
        self.assertEqual(t.meta,Fraction(1,8))
    def test_no_float(self):
        with self.assertRaises(TypeError): Triad().tick(stimulus=0.1)
    def test_exact_arbitrary_refinement(self):
        t=Triad();t.tick(internal='0.00000000000000000000000000000000000001')
        self.assertEqual(t.eq,Fraction(1,10**38))
    def test_persistence(self):
        t=Triad();t.tick(internal='1.221');t.tick(stimulus='0.002')
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'triad.json';t.save(p);r=Triad.load(p)
            self.assertEqual(t.snapshot(),r.snapshot())
            self.assertEqual(t.tick(internal='0.003'),r.tick(internal='0.003'))
    def test_meta_changes_coupling(self):
        t=Triad();a=t.direct_iq_to_eq;t.tick(internal=1)
        self.assertNotEqual(t.direct_iq_to_eq,a)
    def test_budget(self):
        t=Triad();t.budget.max_operations=1
        t.tick()
        with self.assertRaises(RuntimeError):t.tick()
    def test_zero_stable(self):
        t=Triad()
        for _ in range(5):t.tick()
        self.assertEqual((t.iq,t.eq,t.meta),(0,0,0))

if __name__=='__main__':unittest.main()
