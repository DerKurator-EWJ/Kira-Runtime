import os,sys,tempfile,unittest
from fractions import Fraction
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'unified_core'))
from numeric_core import NumericCore
from rrm_arithmetic import Exact,Bound,Process,ArithmeticBudget,BudgetExceeded,UnifiedRRMAdapter,decimal_cell

class IntegrationTests(unittest.TestCase):
    def test_budget(self):
        b=ArithmeticBudget(max_digits=8,max_operations=2,max_places=5)
        self.assertEqual(b.step('1.22',2).value,Fraction(123,100))
        self.assertEqual(b.append_digit('1.22',1,3).value,Fraction(1221,1000))
        with self.assertRaises(BudgetExceeded): b.step('1',1)
        with self.assertRaises(BudgetExceeded): ArithmeticBudget(max_places=2).step('1',3)
        with self.assertRaises(BudgetExceeded): ArithmeticBudget(max_digits=3).append_digit('1.22',1,3)
    def test_decimal_cell(self):
        c=decimal_cell('1.22',2)
        self.assertEqual(c.low,Fraction(122,100))
        self.assertEqual(c.high,Fraction(123,100))
        self.assertEqual(c.refine('1.221','1.222').low,Fraction(1221,1000))
        with self.assertRaises(ValueError): decimal_cell('1.221',2)
    def test_existing_core_persistence_and_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=os.path.join(tmp,'core.sqlite')
            core=NumericCore(path); adapter=UnifiedRRMAdapter(core)
            identity=adapter.observe_exact('1.22',source='sensor')
            adapter.observe_exact('1.221',identity=identity,source='refined_observation')
            self.assertEqual(adapter.read_exact(identity).value,Fraction(122,100))
            self.assertEqual(len(core.evidence(identity)),2)
            self.assertEqual([i for i,_ in adapter.query_bound(Bound('1.21','1.23'))],[identity])
            process=Process('state',Exact('1.22'));process.transition('1.221')
            other=adapter.observe_process(process)
            self.assertEqual(adapter.read_exact(other).value,Fraction(1221,1000))
            with self.assertRaises(TypeError): adapter.observe_process(Process('knowledge',Bound('1','2')))
            core.close()
            core=NumericCore(path)
            self.assertEqual(core.value(identity),Fraction(122,100))
            self.assertEqual(len(core.evidence(identity)),2)
            core.close()
    def test_negative_and_arbitrary_depth(self):
        b=ArithmeticBudget(max_digits=120,max_places=100)
        x=Exact('-1.22')
        self.assertEqual(b.step(x,2,-1).value,Fraction(-123,100))
        x=Exact('1.22')
        for n in range(3,61):x=b.append_digit(x,1,n)
        self.assertEqual(len(str(x.value.denominator)),61)

if __name__=='__main__': unittest.main()
