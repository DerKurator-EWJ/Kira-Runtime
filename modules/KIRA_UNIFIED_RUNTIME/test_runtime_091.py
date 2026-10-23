import tempfile,unittest
from pathlib import Path
from kira_runtime_09 import KiraRuntime
from uui_lang import UUIError

class PersistentUUITests(unittest.TestCase):
    def test_uui_persistence_idempotency_and_direct_read(self):
        with tempfile.TemporaryDirectory() as d:
            demo=Path(__file__).with_name('persistent_demo.uui').read_text()
            with KiraRuntime(d) as k:
                result=k.execute(demo)
                self.assertEqual(result[:4],['true']*4)
                self.assertEqual(result[4],'2')
                self.assertEqual(result[6],'4')
                self.assertEqual(k.verify(),4)
            with KiraRuntime(d) as k:
                self.assertEqual(k.execute('hier(kira_event_count())\nhier(kira_link_count())'),['4','2'])
                self.assertEqual(k.execute(demo)[:4],['false']*4)
                self.assertEqual(k.verify(),4)
                self.assertEqual(k.execute('hier(kira_node_exists("thought"))'),['true'])
    def test_invalid_event_and_duplicate_conflict_do_not_commit(self):
        with tempfile.TemporaryDirectory() as d:
            with KiraRuntime(d) as k:
                with self.assertRaises(UUIError):k.execute('hier(kira_event("x","node","uui","{}"))')
                self.assertEqual(k.verify(),0)
                k.execute('hier(kira_event("x","observation","uui","{\\"content\\":\\"first\\"}"))')
                with self.assertRaises(UUIError):k.execute('hier(kira_event("x","tick","uui","{}"))')
                self.assertEqual(k.verify(),1)
    def test_hostless_uui_is_unchanged(self):
        from uui_lang import Runtime
        from uui_bridge import UUIBridge
        b=UUIBridge()
        try:
            with self.assertRaises(UUIError):Runtime(b).run('hier(kira_event_count())')
        finally:b.close()
    def test_persisted_write_before_later_script_error(self):
        with tempfile.TemporaryDirectory() as d:
            with KiraRuntime(d) as k:
                with self.assertRaises(UUIError):
                    k.execute('hier(kira_event("o1","observation","uui","{\\"content\\":\\"hi\\"}"))\nhier(unknown())')
                self.assertEqual(k.verify(),1)
if __name__=='__main__':unittest.main()
