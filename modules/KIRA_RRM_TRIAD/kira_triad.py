"""Experimental IQ↔EQ + META coupled runtime. Functional dynamics, not sentience."""
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
import json
from rrm_arithmetic import Exact, Process, ArithmeticBudget


def q(v):
    if isinstance(v, Exact): return v.value
    if isinstance(v, Fraction): return v
    if isinstance(v, (int,str)): return Fraction(str(v))
    raise TypeError('Only exact rational inputs, no floats')


def encode(v):
    v=q(v)
    return {'n':str(v.numerator),'d':str(v.denominator)}


def decode(v):
    return Fraction(int(v['n']),int(v['d']))

@dataclass
class Triad:
    """Direct channel acts before META observes; META has no veto over direct dynamics."""
    iq: Fraction = Fraction(0)
    eq: Fraction = Fraction(0)
    meta: Fraction = Fraction(0)
    direct_iq_to_eq: Fraction = Fraction(1,4)
    direct_eq_to_iq: Fraction = Fraction(1,3)
    meta_gain: Fraction = Fraction(1,8)
    meta_learning: Fraction = Fraction(1,100)
    tick_count: int = 0
    events: list = field(default_factory=list)
    budget: ArithmeticBudget = field(default_factory=ArithmeticBudget,repr=False)

    def __post_init__(self):
        for name in ('iq','eq','meta','direct_iq_to_eq','direct_eq_to_iq','meta_gain','meta_learning'):
            setattr(self,name,q(getattr(self,name)))

    def tick(self, stimulus=0, internal=0):
        """Simultaneous direct coupling using OLD IQ/EQ states, then META interpretation.
        internal can perturb EQ independently of external stimulus; not called an emotion.
        """
        s, n=q(stimulus),q(internal)
        old_iq,old_eq,old_meta=self.iq,self.eq,self.meta
        # Direct channel: does not require labels, narratives, or META approval.
        next_iq=old_iq+s+self.direct_eq_to_iq*old_eq
        next_eq=old_eq+n+self.direct_iq_to_eq*old_iq
        # Meta observes discrepancy AFTER direct updates; interpretation is partial.
        discrepancy=next_eq-next_iq
        next_meta=old_meta+self.meta_gain*discrepancy
        # Slow feedback modifies next tick's direct coupling, not this tick's values.
        next_ie=self.direct_iq_to_eq+self.meta_learning*next_meta
        next_ei=self.direct_eq_to_iq-self.meta_learning*next_meta
        self.budget.check(next_iq,next_eq,next_meta,next_ie,next_ei)
        self.iq,self.eq,self.meta=next_iq,next_eq,next_meta
        self.direct_iq_to_eq,self.direct_eq_to_iq=next_ie,next_ei
        self.tick_count+=1
        event={'tick':self.tick_count,'stimulus':encode(s),'internal':encode(n),
               'iq':encode(next_iq),'eq':encode(next_eq),'meta':encode(next_meta),
               'iq_to_eq':encode(next_ie),'eq_to_iq':encode(next_ei),
               'unexplained_internal': bool(n and not s)}
        self.events.append(event)
        return event

    def snapshot(self):
        return {'version':1,'tick_count':self.tick_count,'iq':encode(self.iq),
                'eq':encode(self.eq),'meta':encode(self.meta),
                'direct_iq_to_eq':encode(self.direct_iq_to_eq),
                'direct_eq_to_iq':encode(self.direct_eq_to_iq),
                'meta_gain':encode(self.meta_gain),'meta_learning':encode(self.meta_learning),
                'events':self.events}

    def save(self,path):
        path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
        # atomic replace to avoid partial snapshots
        tmp=path.with_name(path.name+'.tmp')
        tmp.write_text(json.dumps(self.snapshot(),sort_keys=True),encoding='utf-8')
        tmp.replace(path)

    @classmethod
    def load(cls,path):
        obj=json.loads(Path(path).read_text(encoding='utf-8'))
        if obj.get('version')!=1: raise ValueError('Unsupported TRIAD snapshot')
        fields=('iq','eq','meta','direct_iq_to_eq','direct_eq_to_iq','meta_gain','meta_learning')
        t=cls(**{k:decode(obj[k]) for k in fields})
        t.tick_count=obj['tick_count'];t.events=obj['events']
        if len(t.events)!=t.tick_count: raise ValueError('Invalid event count')
        return t
