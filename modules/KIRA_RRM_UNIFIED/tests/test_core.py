import unittest
from fractions import Fraction
from rrm_arithmetic import Exact, Bound, Process

class ArithmeticTests(unittest.TestCase):
    def test_exact(self):
        self.assertEqual((Exact('1.22')+Exact('0.001')).value,Fraction(1221,1000))
        self.assertEqual(Exact('1.22').step(2).value,Fraction(123,100))
        self.assertEqual(Exact('1.22').step(2,-1).value,Fraction(121,100))
        self.assertEqual(Exact('1.22').append_digit(1,3).value,Fraction(1221,1000))
    def test_float_rejected(self):
        with self.assertRaises(TypeError): Exact(1.22)
    def test_bound(self):
        b=Bound('1.22','1.23')
        self.assertEqual(b.refine('1.221','1.222').low,Fraction(1221,1000))
        with self.assertRaises(ValueError): b.refine('1.21','1.22')
        self.assertEqual((b-Bound('0.1','0.2')).low,Fraction(102,100))
        with self.assertRaises(ZeroDivisionError): _=b/Bound('-1','1')
    def test_process_persistence(self):
        p=Process('state',Exact('1.22'))
        for x in ['1.23','1.21','1.221']: p.transition(x)
        restored=Process.from_json(p.to_json())
        self.assertEqual(restored.current.value,Fraction(1221,1000))
        self.assertEqual(len(restored.history),4)
        q=Process('knowledge',Bound('1.2','1.3'))
        q.transition(Bound('1.22','1.23'))
        self.assertEqual(Process.from_json(q.to_json()).current.high,Fraction(123,100))
    def test_large_precision(self):
        x=Exact('1.22')
        for n in range(3,101): x=x.append_digit(1,n)
        self.assertEqual(len(str(x.value.denominator)),101)

if __name__=='__main__': unittest.main()
