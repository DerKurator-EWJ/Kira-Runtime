"""Kira 0.9: unified process facade for UUI and the persistent TRIAD event store.

This is a tested orchestration layer, not a semantic AI or an atomic transaction
across the two existing SQLite databases.
"""
from pathlib import Path
from fractions import Fraction
import json
from uui_bridge import UUIBridge
from uui_lang import Runtime
from kira_experience_08 import ExperienceStore

class KiraRuntime:
    def __init__(self, home):
        self.home = Path(home)
        self.home.mkdir(parents=True, exist_ok=True)
        self.experience = ExperienceStore(self.home / 'experience.sqlite')
        try:
            self.bridge = UUIBridge(kira_db=str(self.home / 'knowledge.sqlite'))
        except BaseException:
            self.experience.close()
            raise

    def execute(self, source):
        """Run real UUI code using the existing bridge and VM.
        UUI operations on knowledge DB and TRIAD journal are separate commits.
        """
        return Runtime(self.bridge,host=self).run(source)

    def invoke_uui(self, name, args):
        """Bounded UUI access to the SAME persistent TRIAD journal as CLI.

        Explicit caller event IDs provide retry idempotency. Writes commit immediately;
        a subsequent UUI error cannot undo prior committed events.
        """
        arity = {'kira_event':4, 'kira_node_value':1, 'kira_link_count':0,
                 'kira_event_count':0, 'kira_node_exists':1,
                 'kira_history_count':1, 'kira_snapshot':0}
        if name not in arity: raise ValueError('Unknown persistent Kira function: '+name)
        if len(args)!=arity[name]:raise ValueError(f'{name} expects {arity[name]} arguments')
        if name=='kira_event':
            event_id,kind,source,payload=args
            if not all(isinstance(x,str) for x in (event_id,kind,source,payload)):
                raise TypeError('kira_event expects four strings')
            if len(payload)>65536:raise ValueError('Event payload exceeds 64 KiB')
            parsed=json.loads(payload)
            if not isinstance(parsed,dict):raise TypeError('Event payload must be a JSON object')
            return self.event(event_id,kind,source,parsed)
        if name=='kira_event_count':return Fraction(self.verify())
        if name=='kira_link_count':return Fraction(len(self.experience.triad.graph.links))
        if name=='kira_node_exists':
            if not isinstance(args[0],str):raise TypeError('Node name must be string')
            return args[0] in self.experience.triad.graph.nodes
        if name=='kira_node_value':
            if not isinstance(args[0],str):raise TypeError('Node name must be string')
            return self.experience.triad.graph.nodes[args[0]].state
        if name=='kira_history_count':
            n=args[0]
            if type(n) is not Fraction or n.denominator!=1:raise TypeError('Limit must be integer')
            return Fraction(len(self.history(int(n))))
        if name=='kira_snapshot':return json.dumps(self.state(),sort_keys=True,ensure_ascii=False)

    def event(self, event_id, kind, source, payload):
        return self.experience.append(event_id, kind, source, payload)

    def state(self):
        return self.experience.triad.snapshot()

    def history(self, limit=100):
        return self.experience.events(limit=limit)

    def verify(self):
        return self.experience.verify()

    def close(self):
        try:
            self.bridge.close()
        finally:
            self.experience.close()

    def __enter__(self): return self
    def __exit__(self, *args): self.close()
