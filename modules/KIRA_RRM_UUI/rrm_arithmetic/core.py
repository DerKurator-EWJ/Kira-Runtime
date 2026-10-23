"""RRM prototype: no float coercion; exact rational arithmetic and explicit epistemic bounds."""
from dataclasses import dataclass, field
from fractions import Fraction
from decimal import Decimal
import json


def parse_exact(value):
    if isinstance(value, float):
        raise TypeError('Binary floats are forbidden: supply a decimal string or Fraction')
    if isinstance(value, Exact):
        return value.value
    if isinstance(value, Fraction):
        return value
    if isinstance(value, (int, str, Decimal)):
        return Fraction(str(value))
    raise TypeError(f'Unsupported exact value: {type(value).__name__}')


@dataclass(frozen=True)
class Exact:
    value: Fraction

    def __init__(self, value):
        object.__setattr__(self, 'value', parse_exact(value))

    def __add__(self, other): return Exact(self.value + parse_exact(other))
    def __sub__(self, other): return Exact(self.value - parse_exact(other))
    def __mul__(self, other): return Exact(self.value * parse_exact(other))
    def __truediv__(self, other): return Exact(self.value / parse_exact(other))
    def step(self, places: int, direction: int = 1):
        if places < 0 or direction not in (-1, 1): raise ValueError('Invalid step')
        return Exact(self.value + direction * Fraction(1, 10 ** places))
    def append_digit(self, digit: int, places: int):
        """Extend a decimal prefix at specified NEW position, not arbitrary fraction digits."""
        if not 0 <= digit <= 9 or places < 1: raise ValueError('Invalid decimal digit/place')
        unit = Fraction(1, 10 ** (places - 1))
        if self.value % unit: raise ValueError('Value is not aligned with decimal prefix')
        return Exact(self.value + Fraction(digit, 10 ** places))
    def to_json(self): return {'kind':'exact','n':str(self.value.numerator),'d':str(self.value.denominator)}
    def __str__(self): return str(self.value)


@dataclass(frozen=True)
class Bound:
    low: Fraction
    high: Fraction

    def __init__(self, low, high):
        a,b = parse_exact(low),parse_exact(high)
        if a > b: raise ValueError('Lower bound exceeds upper bound')
        object.__setattr__(self,'low',a)
        object.__setattr__(self,'high',b)

    def refine(self, low, high):
        a,b = parse_exact(low),parse_exact(high)
        if a < self.low or b > self.high or a > b:
            raise ValueError('Refinement must be a nested nonempty interval')
        return Bound(a,b)

    def shift(self, delta):
        d=parse_exact(delta)
        return Bound(self.low+d,self.high+d)

    def __add__(self, other):
        o = other if isinstance(other,Bound) else Bound(other,other)
        return Bound(self.low+o.low,self.high+o.high)
    def __sub__(self, other):
        o = other if isinstance(other,Bound) else Bound(other,other)
        return Bound(self.low-o.high,self.high-o.low)
    def __mul__(self, other):
        o = other if isinstance(other,Bound) else Bound(other,other)
        p=[a*b for a in (self.low,self.high) for b in (o.low,o.high)]
        return Bound(min(p),max(p))
    def __truediv__(self, other):
        o = other if isinstance(other,Bound) else Bound(other,other)
        if o.low <= 0 <= o.high: raise ZeroDivisionError('Divisor interval includes zero')
        return self * Bound(1/o.high,1/o.low)
    def to_json(self): return {'kind':'bound','low':Exact(self.low).to_json(),'high':Exact(self.high).to_json()}


@dataclass
class Process:
    """Persistent series of exact states OR nested knowledge bounds, never conflated."""
    mode: str
    current: object
    history: list = field(default_factory=list)
    max_steps: int = 10000

    def __post_init__(self):
        if self.mode not in ('state','knowledge'): raise ValueError('Invalid process mode')
        if self.mode == 'state' and not isinstance(self.current,Exact): raise TypeError('State needs Exact')
        if self.mode == 'knowledge' and not isinstance(self.current,Bound): raise TypeError('Knowledge needs Bound')
        if not self.history: self.history.append(self.current.to_json())

    def transition(self, next_value):
        if len(self.history)>=self.max_steps: raise RuntimeError('Process step budget exceeded')
        if self.mode=='state':
            self.current = Exact(next_value)
        else:
            if not isinstance(next_value,Bound): raise TypeError('Knowledge needs Bound')
            self.current = self.current.refine(next_value.low,next_value.high)
        self.history.append(self.current.to_json())
        return self.current

    def to_json(self):
        return json.dumps({'version':1,'mode':self.mode,'history':self.history,'max_steps':self.max_steps},sort_keys=True)

    @classmethod
    def from_json(cls, payload):
        obj=json.loads(payload)
        def unpack(x):
            if x['kind']=='exact': return Exact(Fraction(int(x['n']),int(x['d'])))
            return Bound(unpack(x['low']).value,unpack(x['high']).value)
        if obj['version'] != 1 or not obj['history']: raise ValueError('Unsupported/empty process')
        p=cls(obj['mode'],unpack(obj['history'][0]),max_steps=obj['max_steps'])
        for x in obj['history'][1:]: p.transition(unpack(x) if obj['mode']=='knowledge' else unpack(x).value)
        return p
