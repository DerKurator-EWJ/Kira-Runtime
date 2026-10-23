import tempfile, unittest
from pathlib import Path
from kira_runtime_09 import KiraRuntime

class Runtime09Tests(unittest.TestCase):
    def test_end_to_end_reopen_and_learning(self):
        with tempfile.TemporaryDirectory() as d:
            with KiraRuntime(d) as k:
                self.assertTrue(k.event('n1','node','runtime',{'name':'thought','owner':'IQ','state':'1','drift':'0'}))
                self.assertTrue(k.event('n2','node','runtime',{'name':'affect','owner':'EQ','state':'1','drift':'0'}))
                self.assertTrue(k.event('obs','observation','sensor',{'content':'signal'}))
                self.assertEqual(len(k.state()['graph']['links']),0)
                self.assertTrue(k.event('t1','tick','clock',{}))
                self.assertTrue(k.event('t2','tick','clock',{}))
                after=k.state()
                self.assertGreater(len(after['graph']['links']),0)
                self.assertEqual(k.verify(),5)
                self.assertEqual(k.execute('hier(1 + 2)'),['3'])
            with KiraRuntime(d) as k:
                self.assertEqual(k.state(),after)
                self.assertEqual(k.verify(),5)
                self.assertFalse(k.event('t2','tick','clock',{}))
                self.assertEqual(len(k.history()),5)
    def test_bad_event_does_not_commit(self):
        with tempfile.TemporaryDirectory() as d:
            with KiraRuntime(d) as k:
                with self.assertRaises(Exception):k.event('broken','link','runtime',{'source':'missing','target':'missing','weight':'1'})
                self.assertEqual(k.verify(),0)

if __name__=='__main__':unittest.main()
