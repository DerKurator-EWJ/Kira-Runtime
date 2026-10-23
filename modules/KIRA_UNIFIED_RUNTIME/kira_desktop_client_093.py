"""Kira 0.9.3 desktop UI. Localhost client, never binds or opens ports."""
import argparse
import json
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk, messagebox
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

class KiraAPI:
    def __init__(self, home='kira_state', port=8765, timeout=5):
        if not isinstance(port,int) or not 1 <= port <= 65535: raise ValueError('invalid port')
        self.home=Path(home)
        self.port=port
        self.timeout=timeout

    def request(self, path, payload=None):
        if path not in ('/health','/state','/verify','/history','/event','/uui'):
            raise ValueError('unsupported route')
        token=(self.home/'api_token.txt').read_text(encoding='utf-8').strip()
        if not token:raise ValueError('empty API token')
        data=None if payload is None else json.dumps(payload,ensure_ascii=False).encode('utf-8')
        req=Request(f'http://127.0.0.1:{self.port}{path}', data=data, method='GET' if data is None else 'POST',
                    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        try:
            with urlopen(req,timeout=self.timeout) as resp:
                return json.load(resp)
        except HTTPError as exc:
            try: details=json.load(exc)
            except (ValueError,OSError):details={'error':str(exc.code)}
            raise RuntimeError(f'API {exc.code}: {details.get("error","error")}') from None
        except URLError as exc:
            raise ConnectionError('Kira-Dienst nicht erreichbar. Zuerst kira_service_092.py starten.') from exc

class KiraDesktop(tk.Tk):
    def __init__(self, api):
        super().__init__()
        self.api=api
        self.title('KIRA Desktop 0.9.3 — UUI / TRIAD')
        self.geometry('950x670')
        self.configure(bg='#101810')
        self.status=tk.StringVar(value='Nicht verbunden')
        self._build()
        self.after(150,self.refresh)

    def _build(self):
        style=ttk.Style(self)
        style.theme_use('clam')
        style.configure('TFrame',background='#101810')
        style.configure('TLabel',background='#101810',foreground='#8fe08f')
        style.configure('TButton',padding=7)
        header=ttk.Frame(self);header.pack(fill='x',padx=15,pady=12)
        ttk.Label(header,text='KIRA // DESKTOP 0.9.3',font=('Consolas',18,'bold')).pack(side='left')
        ttk.Label(header,textvariable=self.status).pack(side='right')
        buttons=ttk.Frame(self);buttons.pack(fill='x',padx=15)
        for label,path in [('Verbindung','/health'),('TRIAD-Zustand','/state'),('Ereignisse','/history'),('Journal prüfen','/verify')]:
            ttk.Button(buttons,text=label,command=lambda p=path:self.fetch(p)).pack(side='left',padx=(0,7))
        ttk.Label(self,text='UUI-Programm').pack(anchor='w',padx=15,pady=(15,5))
        self.editor=tk.Text(self,height=9,bg='#081008',fg='#b2f6a2',insertbackground='#b2f6a2',font=('Consolas',11))
        self.editor.pack(fill='both',padx=15)
        self.editor.insert('1.0','hier(kira_event_count())')
        ttk.Button(self,text='UUI ausführen',command=self.execute).pack(anchor='w',padx=15,pady=10)
        ttk.Label(self,text='Antwort / Protokoll').pack(anchor='w',padx=15)
        self.output=tk.Text(self,height=15,bg='#081008',fg='#b2f6a2',font=('Consolas',10),state='disabled')
        self.output.pack(fill='both',expand=True,padx=15,pady=(5,15))

    def log(self, content):
        self.output.configure(state='normal')
        self.output.insert('end',content+'\n\n')
        self.output.see('end')
        self.output.configure(state='disabled')

    def _work(self, path, payload=None):
        def job():
            try:result=self.api.request(path,payload)
            except Exception as exc:
                msg=f'{type(exc).__name__}: {exc}'
                self.after(0,lambda:self._show_error(msg))
            else:self.after(0,lambda:self._show_result(path,result))
        threading.Thread(target=job,daemon=True).start()

    def _show_error(self,msg):
        self.status.set('Fehler / offline')
        self.log(msg)

    def _show_result(self,path,result):
        self.status.set('Verbunden')
        self.log(path+'\n'+json.dumps(result,indent=2,ensure_ascii=False,default=str))

    def fetch(self,path):self._work(path)
    def refresh(self):self.fetch('/health')
    def execute(self):
        source=self.editor.get('1.0','end-1c')
        if source.strip():self._work('/uui',{'source':source})

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--home',default='./kira_state')
    parser.add_argument('--port',type=int,default=8765)
    args=parser.parse_args()
    KiraDesktop(KiraAPI(args.home,args.port)).mainloop()

if __name__=='__main__':main()
