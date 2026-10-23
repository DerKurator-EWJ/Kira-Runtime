"""UUI native RRM functions: exact values, epistemic bounds, and state processes."""
from fractions import Fraction
from rrm_arithmetic import Exact,Bound,Process,ArithmeticBudget

FUNCTIONS=frozenset(('rrm_exact','rrm_bound','rrm_low','rrm_high','rrm_step','rrm_append','rrm_refine','rrm_shift','rrm_state','rrm_knowledge','rrm_next','rrm_current','rrm_history'))

def _number(v):
    if type(v) is Fraction:return v
    if isinstance(v,Exact):return v.value
    raise ValueError('RRM numeric argument required')
def _integer(v):
    x=_number(v)
    if x.denominator!=1:raise ValueError('Integer argument required')
    return int(x)
def _exact(v):
    if isinstance(v,str):return Exact(v)
    return Exact(_number(v))
def _bound(v):
    if not isinstance(v,Bound):raise ValueError('RRM bound required')
    return v

def invoke(name,args,runtime):
    arities={'rrm_exact':1,'rrm_bound':2,'rrm_low':1,'rrm_high':1,'rrm_step':3,'rrm_append':3,'rrm_refine':3,'rrm_shift':2,'rrm_state':1,'rrm_knowledge':2,'rrm_next':2,'rrm_current':1,'rrm_history':1}
    if len(args)!=arities[name]:raise ValueError(f'{name} expects {arities[name]} arguments')
    b=runtime.rrm_budget
    if name=='rrm_exact':return _exact(args[0])
    if name=='rrm_bound':
        r=Bound(_exact(args[0]),_exact(args[1]));b.check(r.low,r.high);return r
    if name=='rrm_low':return Exact(_bound(args[0]).low)
    if name=='rrm_high':return Exact(_bound(args[0]).high)
    if name=='rrm_step':return b.step(_exact(args[0]),_integer(args[1]),_integer(args[2]))
    if name=='rrm_append':return b.append_digit(_exact(args[0]),_integer(args[1]),_integer(args[2]))
    if name=='rrm_refine':return b.refine(_bound(args[0]),_exact(args[1]),_exact(args[2]))
    if name=='rrm_shift':
        r=_bound(args[0]).shift(_exact(args[1]));b.check(r.low,r.high);return r
    if name=='rrm_state':return Process('state',_exact(args[0]))
    if name=='rrm_knowledge':return Process('knowledge',Bound(_exact(args[0]),_exact(args[1])))
    if name=='rrm_next':
        proc=args[0]
        if not isinstance(proc,Process):raise ValueError('RRM process required')
        # Pure update: return a new process, never mutate an alias.
        copy=Process.from_json(proc.to_json())
        nextval=_exact(args[1]) if copy.mode=='state' else _bound(args[1])
        if isinstance(nextval,Exact):b.check(nextval.value)
        else:b.check(nextval.low,nextval.high)
        copy.transition(nextval)
        return copy
    if name=='rrm_current':
        if not isinstance(args[0],Process):raise ValueError('RRM process required')
        return args[0].current
    if name=='rrm_history':
        if not isinstance(args[0],Process):raise ValueError('RRM process required')
        return Fraction(len(args[0].history))
    raise ValueError('Unknown RRM function')
