"""KIRA 0.7: bounded relational learning atop the 0.6 graph.

Exact state and epistemic knowledge remain distinct. Structural growth uses
explicit, reproducible coactivation criteria, not claims of emotional experience.
"""
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
import json
from kira_triad import q, encode, decode
from kira_relational_triad import RelationalTriad
from rrm_arithmetic import Bound, Exact

@dataclass
class AdaptiveTriad:
    graph: RelationalTriad = field(default_factory=RelationalTriad)
    knowledge: dict = field(default_factory=dict)
    coactivation: dict = field(default_factory=dict)
    auto_links: set = field(default_factory=set)
    threshold: Fraction = Fraction(1, 2)
    prune_below: Fraction = Fraction(1, 10000)
    min_observations: int = 2
    max_auto_links: int = 128
    max_coactivation: int = 8192

    def add_node(self, name, owner, state=0, drift=0, low=None, high=None):
        n=self.graph.add_node(name,owner,state,drift)
        if low is not None or high is not None:
            if low is None or high is None: raise ValueError('Both bounds required')
            self.set_knowledge(name,low,high)
        return n

    def set_knowledge(self,name,low,high):
        if name not in self.graph.nodes: raise KeyError(name)
        b=Bound(low,high)
        self.graph._check([b.low,b.high])
        if name in self.knowledge:
            b=self.knowledge[name].refine(b.low,b.high)
        self.knowledge[name]=b
        return b

    def step(self, impulses=None):
        # Compute on a copy: budget failure never partially commits graph or learning.
        clone=self.from_snapshot(self.snapshot())
        result=clone.graph.tick(impulses)
        active=sorted(k for k,n in clone.graph.nodes.items() if abs(n.state)>=clone.threshold)
        for source in active:
            for target in active:
                if source==target: continue
                if clone.graph.nodes[source].owner=='META' or clone.graph.nodes[target].owner=='META':
                    continue
                key=(source,target)
                if key not in clone.coactivation and len(clone.coactivation)>=clone.max_coactivation:
                    continue
                clone.coactivation[key]=clone.coactivation.get(key,0)+1
                if clone.coactivation[key]<clone.min_observations:continue
                if len(clone.auto_links)>=clone.max_auto_links:continue
                if any(e.source==source and e.target==target for e in clone.graph.links):continue
                clone.graph.link(source,target,'1/100',plasticity='1/1000')
                clone.auto_links.add(key)
        # Remove only autonomously created links when their weight becomes negligible.
        retained=[]
        for e in clone.graph.links:
            key=(e.source,e.target)
            if key in clone.auto_links and abs(e.weight)<clone.prune_below:
                clone.auto_links.remove(key)
            else:retained.append(e)
        clone.graph.links=retained
        self.__dict__.update(clone.__dict__)
        return result

    def snapshot(self):
        return {'schema':2,'graph':self.graph.snapshot(),
            'knowledge':{k:v.to_json() for k,v in sorted(self.knowledge.items())},
            'coactivation':[[a,b,n] for (a,b),n in sorted(self.coactivation.items())],
            'auto_links':[list(x) for x in sorted(self.auto_links)],
            'config':{'threshold':encode(self.threshold),'prune_below':encode(self.prune_below),
              'min_observations':self.min_observations,'max_auto_links':self.max_auto_links,
              'max_coactivation':self.max_coactivation}}

    @classmethod
    def from_snapshot(cls,s):
        if s.get('schema')!=2:raise ValueError('Unsupported snapshot')
        # Existing 0.6 loader already validates node/link identities and history.
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as tmp:
            p=Path(tmp)/'graph.json';p.write_text(json.dumps(s['graph']))
            g=RelationalTriad.load(p)
        cfg=s['config'];o=cls(g,threshold=decode(cfg['threshold']),prune_below=decode(cfg['prune_below']),
            min_observations=cfg['min_observations'],max_auto_links=cfg['max_auto_links'],
            max_coactivation=cfg['max_coactivation'])
        for k,b in s['knowledge'].items():
            if k not in g.nodes:raise ValueError('Unknown knowledge node')
            o.knowledge[k]=Bound(Fraction(int(b['low']['n']),int(b['low']['d'])),
                                  Fraction(int(b['high']['n']),int(b['high']['d'])))
        o.coactivation={(a,b):int(n) for a,b,n in s['coactivation']}
        o.auto_links={tuple(x) for x in s['auto_links']}
        if not o.auto_links.issubset({(e.source,e.target) for e in g.links}):
            raise ValueError('Unknown auto link')
        return o

    def save(self,path):
        path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
        tmp=path.with_name(path.name+'.tmp')
        tmp.write_text(json.dumps(self.snapshot(),sort_keys=True),encoding='utf-8')
        tmp.replace(path)

    @classmethod
    def load(cls,path):return cls.from_snapshot(json.loads(Path(path).read_text(encoding='utf-8')))
