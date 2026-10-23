import json
import tempfile
import threading
import unittest
from pathlib import Path
from kira_desktop_client_093 import KiraAPI
from kira_service_092 import create_server

class DesktopClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.home=Path(self.tmp.name)
        self.token='t'*48
        (self.home/'api_token.txt').write_text(self.token)
        self.server=create_server(self.home,self.token,0)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.client=KiraAPI(self.home,self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown();self.thread.join(timeout=5)
        self.server.server_close();self.server.kira_service.close();self.tmp.cleanup()

    def test_health(self):
        self.assertEqual(self.client.request('/health')['status'],'ok')

    def test_uui_and_persistence(self):
        self.assertIn('output',self.client.request('/uui',{'source':'hier(kira_event_count())'}))
        self.assertIn('events',self.client.request('/history'))

    def test_auth_rejected(self):
        (self.home/'api_token.txt').write_text('wrong')
        with self.assertRaises(RuntimeError):self.client.request('/state')

    def test_route_allowlist(self):
        with self.assertRaises(ValueError):self.client.request('http://example.com')

    def test_verify(self):
        self.assertIn('verified_events',self.client.request('/verify'))

if __name__=='__main__':unittest.main()
