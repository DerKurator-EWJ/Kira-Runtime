"""KIRA TRIAD 0.6: exact rational, sparse, adaptive relational process graph.

Research prototype: states and couplings are computational, not evidence of feelings.
The direct IQ↔EQ channel runs before META. All updates are simultaneous per phase.
"""
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
import json
from kira_triad import q, encode, decode


@dataclass
class Node:
    name: str
    owner: str
    state: Fraction = Fraction(0)
    drift: Fraction = Fraction(0)
    def __post_init__(self):
        if self.owner not in ('IQ', 'EQ', 'META'):
            raise ValueError('owner must be IQ, EQ, or META')
        self.state, self.drift = q(self.state), q(self.drift)


@dataclass
class Link:
    source: str
    target: str
    weight: Fraction
    plasticity: Fraction = Fraction(0)
    def __post_init__(self):
        self.weight, self.plasticity = q(self.weight), q(self.plasticity)


@dataclass
class RelationalTriad:
    nodes: dict = field(default_factory=dict)
    links: list = field(default_factory=list)
    ticks: int = 0
    history: list = field(default_factory=list)
    max_nodes: int = 1024
    max_links: int = 8192
    max_bits: int = 4096
    max_history: int = 10000

    def add_node(self, name, owner, state=0, drift=0):
        if name in self.nodes: raise ValueError('duplicate node')
        if len(self.nodes) >= self.max_nodes: raise RuntimeError('node budget exceeded')
        self.nodes[name] = Node(name, owner, state, drift)
        return self.nodes[name]

    def link(self, source, target, weight, plasticity=0):
        if source not in self.nodes or target not in self.nodes: raise KeyError('unknown node')
        if len(self.links) >= self.max_links: raise RuntimeError('link budget exceeded')
        edge = Link(source, target, weight, plasticity)
        self.links.append(edge)
        return edge

    def refine(self, node, decimal_delta):
        """Exact decimal state change; not falsely described as precision gain."""
        candidate = self.nodes[node].state + q(decimal_delta)
        self._check([candidate])
        self.nodes[node].state = candidate

    def _check(self, values):
        for v in values:
            if v.numerator.bit_length() > self.max_bits or v.denominator.bit_length() > self.max_bits:
                raise RuntimeError('rational bit budget exceeded')

    def tick(self, impulses=None):
        impulses = impulses or {}
        if not set(impulses).issubset(self.nodes): raise KeyError('unknown impulse target')
        old = {k:n.state for k,n in self.nodes.items()}
        # Direct phase: IQ/EQ interactions, without META arbitration.
        direct = {k:old[k]+n.drift+q(impulses.get(k,0)) for k,n in self.nodes.items()}
        for e in self.links:
            if self.nodes[e.source].owner != 'META' and self.nodes[e.target].owner != 'META':
                direct[e.target] += e.weight * old[e.source]
        # META reads the result after direct phase. Its output cannot veto direct updates.
        nxt = dict(direct)
        for e in self.links:
            if self.nodes[e.target].owner == 'META':
                nxt[e.target] += e.weight * direct[e.source]
            elif self.nodes[e.source].owner == 'META':
                nxt[e.target] += e.weight * old[e.source]
        # Plasticity uses observed direct state correlations, affects only future ticks.
        weights = []
        for e in self.links:
            weights.append(e.weight + e.plasticity * direct[e.source] * direct[e.target])
        self._check(list(nxt.values())+weights)
        if len(self.history) >= self.max_history: raise RuntimeError('history budget exceeded')
        # Commit only after all calculations and budget checks succeed.
        for k,v in nxt.items(): self.nodes[k].state=v
        for e,w in zip(self.links,weights): e.weight=w
        self.ticks += 1
        record={'tick':self.ticks,'states':{k:encode(v) for k,v in sorted(nxt.items())},
                'impulses':{k:encode(q(v)) for k,v in sorted(impulses.items())}}
        self.history.append(record)
        return record

    def snapshot(self):
        return {'schema':1,'ticks':self.ticks,
                'nodes':[{'name':n.name,'owner':n.owner,'state':encode(n.state),'drift':encode(n.drift)}
                         for n in self.nodes.values()],
                'links':[{'source':e.source,'target':e.target,'weight':encode(e.weight),
                          'plasticity':encode(e.plasticity)} for e in self.links],
                'history':self.history,
                'limits':{'max_nodes':self.max_nodes,'max_links':self.max_links,
                          'max_bits':self.max_bits,'max_history':self.max_history}}

    def save(self,path):
        path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
        tmp=path.with_name(path.name+'.tmp')
        tmp.write_text(json.dumps(self.snapshot(),ensure_ascii=False,sort_keys=True),encoding='utf-8')
        tmp.replace(path)

    @classmethod
    def load(cls,path):
        data=json.loads(Path(path).read_text(encoding='utf-8'))
        if data.get('schema')!=1: raise ValueError('unsupported schema')
        obj=cls(**data['limits'])
        for n in data['nodes']: obj.add_node(n['name'],n['owner'],decode(n['state']),decode(n['drift']))
        for e in data['links']: obj.link(e['source'],e['target'],decode(e['weight']),decode(e['plasticity']))
        obj.ticks=data['ticks'];obj.history=data['history']
        if len(obj.history)!=obj.ticks: raise ValueError('inconsistent history')
        return obj


def demo():
    t=RelationalTriad()
    t.add_node('reason','IQ')
    t.add_node('affect','EQ',drift='0.0001')
    t.add_node('reflect','META')
    t.link('reason','affect','1/4')
    t.link('affect','reason','1/3')
    t.link('affect','reflect','1/8')
    t.link('reflect','reason','1/16')
    return t
