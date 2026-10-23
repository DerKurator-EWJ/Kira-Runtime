"""Opt-in budgets. Limits are resource policy, never ontological numerical bounds."""
from dataclasses import dataclass
from fractions import Fraction
from .core import Exact, Bound, parse_exact

class BudgetExceeded(RuntimeError): pass

@dataclass
class ArithmeticBudget:
    max_digits: int = 10000
    max_operations: int = 100000
    max_places: int = 10000
    operations: int = 0
    def __post_init__(self):
        if min(self.max_digits,self.max_operations,self.max_places)<1: raise ValueError('budgets must be positive')
    def check(self,*values):
        if self.operations >= self.max_operations: raise BudgetExceeded('operation quota exceeded')
        for value in values:
            v=parse_exact(value)
            if len(str(abs(v.numerator)))>self.max_digits or len(str(v.denominator))>self.max_digits:
                raise BudgetExceeded('rational digit quota exceeded')
        self.operations+=1
    def step(self, value, places, direction=1):
        if not isinstance(places,int) or places<0 or places>self.max_places: raise BudgetExceeded('decimal place quota exceeded')
        v=Exact(value)
        candidate=v.step(places,direction)
        self.check(v,candidate)
        return candidate
    def append_digit(self,value,digit,places):
        if not isinstance(places,int) or places<1 or places>self.max_places: raise BudgetExceeded('decimal place quota exceeded')
        v=Exact(value)
        candidate=v.append_digit(digit,places)
        self.check(v,candidate)
        return candidate
    def refine(self,bound,low,high):
        candidate=bound.refine(low,high)
        self.check(candidate.low,candidate.high)
        return candidate

def decimal_cell(prefix, places):
    """Decimal prefix as epistemic interval [prefix, prefix+10^-places)."""
    if not isinstance(places,int) or places<0: raise ValueError('places must be nonnegative')
    v=parse_exact(prefix)
    unit=Fraction(1,10**places)
    if v % unit: raise ValueError('prefix must align to decimal cell')
    return Bound(v,v+unit)
