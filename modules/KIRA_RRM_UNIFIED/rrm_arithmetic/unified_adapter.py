"""Non-breaking adapter to the original Unified Core 1.4 NumericCore schema."""
from .core import Exact,Bound,Process

class UnifiedRRMAdapter:
    def __init__(self,numeric_core):
        self.core=numeric_core
    def observe_exact(self,value,identity=None,source='rrm'):
        return self.core.observe(Exact(value).value,identity=identity,source=source)
    def read_exact(self,identity):
        return Exact(self.core.value(identity))
    def query_bound(self,bound):
        if not isinstance(bound,Bound): raise TypeError('Bound required')
        # Core 1.4 range query is centered; Fraction midpoint avoids floats.
        midpoint=(bound.low+bound.high)/2
        radius=(bound.high-bound.low)/2
        return [(identity,Exact(v)) for identity,v in self.core.range(midpoint,radius)]
    def observe_process(self,process,source='rrm_process'):
        """Only an exact current state enters NumericCore. The full history stays in Process."""
        if not isinstance(process,Process) or process.mode!='state':
            raise TypeError('state Process required; bounds are not point observations')
        return self.observe_exact(process.current,source=source)
