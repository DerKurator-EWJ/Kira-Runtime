"""Kira Desktop 1.6.0: local Windows GUI and persistent event journal.
No model is claimed to be wired to chat; use the separate Core Bridge for API events.
"""
import json, os, queue, secrets, subprocess, sys, threading, uuid
from datetime import datetime, timezone
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox

ROOT = Path(__file__).resolve().parent
CORE = (Path(getattr(sys, '_MEIPASS')) if getattr(sys, 'frozen', False) else ROOT.parent / 'source' / 'python_core' / 'KIRA_UNIFIED_CORE_1_4')
sys.path.insert(0,str(CORE))
from kira_core_bridge import EventStore
DATA = Path(os.getenv('LOCALAPPDATA',str(Path.home()))) / 'KiraDesktop'
DATA.mkdir(parents=True,exist_ok=True)
TOKENFILE=DATA/'bridge_token.txt'
if not TOKENFILE.exists():
    TOKENFILE.write_text(secrets.token_urlsafe(40),encoding='utf-8')
    try:os.chmod(TOKENFILE,0o600)
    except OSError:pass
TOKEN=TOKENFILE.read_text(encoding='utf-8').strip()
DB=DATA/'kira_events.sqlite3'

class App:
    def __init__(self,root):
        self.root=root;root.title('KIRA // Desktop 1.6.0');root.geometry('980x670');root.minsize(650,450)
        root.configure(bg='#08120d');self.store=EventStore(str(DB));self.server=None;self.thread=None
        self.status=tk.StringVar(value='CORE: bereit | BRIDGE: aus | BLUETOOTH: noch nicht implementiert')
        head=tk.Frame(root,bg='#08120d');head.pack(fill='x',padx=18,pady=(14,6))
        tk.Label(head,text='KIRA  /  DESKTOP',font=('Consolas',20,'bold'),fg='#72ef9e',bg='#08120d').pack(side='left')
        tk.Label(root,textvariable=self.status,font=('Consolas',10),fg='#b0cfbb',bg='#08120d',anchor='w').pack(fill='x',padx=18)
        self.log=tk.Text(root,bg='#06100a',fg='#8ee6a6',insertbackground='#aafac5',font=('Consolas',11),wrap='word',relief='flat',state='disabled')
        self.log.pack(fill='both',expand=True,padx=18,pady=12)
        controls=tk.Frame(root,bg='#08120d');controls.pack(fill='x',padx=18,pady=(0,12))
        self.entry=tk.Entry(controls,font=('Consolas',12),bg='#13241a',fg='#dcffe5',insertbackground='white',relief='flat')
        self.entry.pack(side='left',fill='x',expand=True,ipady=9);self.entry.bind('<Return>',lambda e:self.send())
        ttk.Button(controls,text='Ereignis speichern',command=self.send).pack(side='left',padx=8)
        ttk.Button(controls,text='Bridge starten',command=self.start_bridge).pack(side='left',padx=4)
        ttk.Button(controls,text='Verlauf laden',command=self.reload).pack(side='left',padx=4)
        self.write('SYSTEM','Desktop-UI bereit. Ereignisse werden lokal gespeichert. Keine generative Chatantwort angeschlossen.')
        self.reload()
        root.protocol('WM_DELETE_WINDOW',self.close)
    def write(self,who,msg):
        self.log.configure(state='normal');self.log.insert('end',f'[{who}] {msg}\n\n');self.log.see('end');self.log.configure(state='disabled')
    def send(self):
        value=self.entry.get().strip()
        if not value:return
        self.entry.delete(0,'end')
        event={'event_id':str(uuid.uuid4()),'timestamp':datetime.now(timezone.utc).isoformat(),'actor_id':'Kurator','kind':'observation','scope':'EPISODIC','payload':{'text':value,'source':'desktop_ui'}}
        try:self.store.append(event);self.write('KURATOR',value);self.write('SYSTEM','Ereignis persistent gespeichert; keine automatische KI-Antwort.')
        except Exception as e:messagebox.showerror('Speicherfehler',str(e))
    def reload(self):
        self.log.configure(state='normal');self.log.delete('1.0','end');self.log.configure(state='disabled')
        try:
            for e in reversed(self.store.recent(100)):
                self.write(e['actor_id'],e['payload'].get('text',json.dumps(e['payload'],ensure_ascii=False)))
        except Exception as e:self.write('FEHLER',str(e))
    def start_bridge(self):
        if self.thread and self.thread.is_alive():return
        def serve():
            from http.server import ThreadingHTTPServer
            from kira_core_bridge import make_handler
            try:
                self.server=ThreadingHTTPServer(('127.0.0.1',8765),make_handler(self.store,TOKEN))
                self.root.after(0,lambda:self.status.set('CORE: bereit | BRIDGE: 127.0.0.1:8765 | BLUETOOTH: aus'))
                self.server.serve_forever()
            except Exception as exc:
                self.root.after(0,lambda e=str(exc):self.write('BRIDGE ERROR',e))
        self.thread=threading.Thread(target=serve,daemon=True);self.thread.start()
    def close(self):
        if self.server:self.server.shutdown();self.server.server_close()
        self.store.db.close();self.root.destroy()
if __name__=='__main__':
    window=tk.Tk();App(window);window.mainloop()
