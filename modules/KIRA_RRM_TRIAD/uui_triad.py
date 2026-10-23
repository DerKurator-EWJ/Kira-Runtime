"""Native UUI functions for the adaptive TRIAD 0.7 graph."""
from fractions import Fraction
from kira_triad_07 import AdaptiveTriad
from rrm_arithmetic import Exact,Bound
FUNCTIONS=frozenset(('triad_new','triad_node','triad_link','triad_bound','triad_tick','triad_value','triad_low','triad_high','triad_links'))
ARITIES={'triad_new':0,'triad_node':5,'triad_link':5,'triad_bound':4,'triad_tick':1,'triad_value':2,'triad_low':2,'triad_high':2,'triad_links':1}
def num(x):
    if isinstance(x,Exact):return x.value
    if type(x) is Fraction:return x
    if isinstance(x,str):return Fraction(x)
    raise TypeError('Expected exact rational')
def invoke(name,args,runtime):
    if len(args)!=ARITIES[name]:raise ValueError(f'{name} expects {ARITIES[name]} arguments')
    if name=='triad_new':return AdaptiveTriad()
    t=args[0]
    if not isinstance(t,AdaptiveTriad):raise TypeError('Expected TRIAD instance')
    if name=='triad_node':
        _,key,owner,state,drift=args
        t.add_node(key,owner,num(state),num(drift));return t
    if name=='triad_link':
        _,source,target,weight,plasticity=args
        t.graph.link(source,target,num(weight),num(plasticity));return t
    if name=='triad_bound':
        _,key,low,high=args;t.set_knowledge(key,num(low),num(high));return t
    if name=='triad_tick':t.step();return t
    if name=='triad_value':return Exact(t.graph.nodes[args[1]].state)
    if name=='triad_low':return Exact(t.knowledge[args[1]].low)
    if name=='triad_high':return Exact(t.knowledge[args[1]].high)
    if name=='triad_links':return Fraction(len(t.graph.links))
    raise ValueError(name)
