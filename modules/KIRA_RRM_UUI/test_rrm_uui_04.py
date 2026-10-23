import unittest
from fractions import Fraction
from uui_lang import Runtime,UUIError
from uui_compiler import compile_source,serialize,deserialize
from rrm_arithmetic import Exact,Bound,Process
class Bridge:
    pass
class RRMUUITest(unittest.TestCase):
    def run_uui(self,code):
        r=Runtime(Bridge());r.run(code);return r
    def test_exact(self):
        r=self.run_uui('es_sei x = rrm_exact("1.22")\nes_sei y = rrm_step(x,2,1)\nhier(y)')
        self.assertEqual(r.vars['y'].value,Fraction(123,100))
    def test_append(self):
        r=self.run_uui('es_sei x = rrm_append(rrm_exact("1.22"),1,3)')
        self.assertEqual(r.vars['x'].value,Fraction(1221,1000))
    def test_refinement(self):
        r=self.run_uui('es_sei b = rrm_refine(rrm_bound("1.22","1.23"),"1.221","1.222")')
        self.assertEqual(r.vars['b'].low,Fraction(1221,1000))
    def test_invalid_refinement(self):
        with self.assertRaises(UUIError):self.run_uui('es_sei b = rrm_refine(rrm_bound("1.22","1.23"),"1.21","1.22")')
    def test_state_history_and_purity(self):
        r=self.run_uui('es_sei a = rrm_state("1.22")\nes_sei b = rrm_next(a,rrm_exact("1.23"))\nhier(rrm_history(b))')
        self.assertEqual(len(r.vars['a'].history),1)
        self.assertEqual(len(r.vars['b'].history),2)
    def test_compiled(self):
        code='es_sei x = rrm_append(rrm_exact("1.22"),1,3)'
        r=Runtime(Bridge());r.run_compiled(deserialize(serialize(compile_source(code))))
        self.assertEqual(r.vars['x'].value,Fraction(1221,1000))
    def test_legacy(self):
        r=self.run_uui('es_sei x = 1.22 + 0.01\nhier(x)')
        self.assertEqual(r.vars['x'],Fraction(123,100))
    def test_budget(self):
        r=Runtime(Bridge());r.rrm_budget.max_places=2
        with self.assertRaises(UUIError):r.run('es_sei x = rrm_step(rrm_exact("1.22"),3,1)')
if __name__=='__main__':unittest.main()
