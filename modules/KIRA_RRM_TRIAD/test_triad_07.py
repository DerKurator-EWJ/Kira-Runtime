import unittest
from tempfile import TemporaryDirectory
from pathlib import Path
from fractions import Fraction
from kira_triad_07 import AdaptiveTriad
from uui_lang import Runtime

class AdaptiveTests(unittest.TestCase):
 def test_refinement_is_not_motion(self):
  t=AdaptiveTriad();t.add_node('a','EQ','1.22',low='1.2',high='1.3')
  t.set_knowledge('a','1.21','1.23')
  self.assertEqual(t.graph.nodes['a'].state,Fraction(122,100))
  self.assertEqual(t.knowledge['a'].low,Fraction(121,100))
  with self.assertRaises(ValueError):t.set_knowledge('a','1','2')
 def test_autonomous_link_growth(self):
  t=AdaptiveTriad();t.add_node('a','IQ',1);t.add_node('b','EQ',1)
  t.step();self.assertEqual(len(t.graph.links),0)
  t.step();self.assertEqual(len(t.graph.links),2)
 def test_no_meta_autolink(self):
  t=AdaptiveTriad();t.add_node('a','IQ',1);t.add_node('b','META',1)
  t.step();t.step();self.assertEqual(len(t.graph.links),0)
 def test_budget_atomic(self):
  t=AdaptiveTriad();t.add_node('a','IQ',1);t.add_node('b','EQ',1)
  t.graph.max_links=1;t.step();before=t.snapshot()
  with self.assertRaises(RuntimeError):t.step()
  self.assertEqual(before,t.snapshot())
 def test_snapshot(self):
  t=AdaptiveTriad();t.add_node('a','EQ',1,low=0,high=2);t.step()
  with TemporaryDirectory() as d:
   p=Path(d)/'t.json';t.save(p);u=AdaptiveTriad.load(p)
   self.assertEqual(t.snapshot(),u.snapshot())
 def test_uui(self):
  source=Path(__file__).with_name('triad_demo.uui').read_text()
  # UUI CLI is the supported full execution path, tested in subprocess.
  import subprocess,sys
  p=subprocess.run([sys.executable,'run_uui.py','triad_demo.uui'],cwd=Path(__file__).parent,capture_output=True,text=True)
  self.assertEqual(p.returncode,0,p.stderr)
  self.assertIn('2',p.stdout)
if __name__=='__main__':unittest.main()
