import http.client
import json
import tempfile
import threading
import unittest
from kira_service_092 import create_server

class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.token='test-secret-token-at-least-24-chars'
        self.server=create_server(self.tmp.name,self.token,0)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.thread.join(timeout=5)
        self.server.server_close();self.server.kira_service.close();self.tmp.cleanup()
    def request(self,method,path,payload=None,token=None):
        conn=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        headers={'Authorization':'Bearer '+(self.token if token is None else token)}
        body=None
        if payload is not None:
            body=json.dumps(payload);headers['Content-Type']='application/json'
        conn.request(method,path,body,headers)
        resp=conn.getresponse();status=resp.status;data=json.loads(resp.read());conn.close()
        return status,data
    def test_authorization(self):
        self.assertEqual(self.request('GET','/health',token='wrong')[0],401)
        self.assertEqual(self.request('GET','/health')[1]['status'],'ok')
    def test_event_uui_and_persistence(self):
        e={'id':'a','kind':'observation','source':'test','payload':{'content':'hello'}}
        self.assertEqual(self.request('POST','/event',e)[1]['inserted'],True)
        self.assertEqual(self.request('POST','/event',e)[1]['inserted'],False)
        self.assertEqual(self.request('POST','/uui',{'source':'hier(kira_event_count())'})[1]['output'],['1'])
        self.assertEqual(self.request('GET','/verify')[1]['verified_events'],1)
        self.assertEqual(len(self.request('GET','/history')[1]['events']),1)
        self.assertEqual(self.request('POST','/event',{**e,'payload':{'content':'changed'}})[0],400)
    def test_validation(self):
        self.assertEqual(self.request('POST','/event',{'invalid':1})[0],400)
        self.assertEqual(self.request('GET','/missing')[0],404)
        self.assertEqual(self.request('POST','/uui',{'source':'hier(unknown())'})[0],500)

if __name__=='__main__':unittest.main()
