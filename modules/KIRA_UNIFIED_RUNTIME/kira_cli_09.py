"""Command-line launcher for the unified Kira 0.9 experimental runtime."""
import argparse, json
from pathlib import Path
from kira_runtime_09 import KiraRuntime

def main():
    p=argparse.ArgumentParser(description='Kira 0.9 integrated runtime')
    p.add_argument('--home',default='kira_state')
    sub=p.add_subparsers(dest='cmd',required=True)
    u=sub.add_parser('uui');u.add_argument('file',type=Path)
    e=sub.add_parser('event');e.add_argument('id');e.add_argument('kind',choices=['node','link','tick','refinement','observation']);e.add_argument('source');e.add_argument('payload',help='JSON object')
    sub.add_parser('state');sub.add_parser('verify');sub.add_parser('history')
    args=p.parse_args()
    with KiraRuntime(args.home) as k:
        if args.cmd=='uui': value=k.execute(args.file.read_text(encoding='utf-8'))
        elif args.cmd=='event': value={'inserted':k.event(args.id,args.kind,args.source,json.loads(args.payload))}
        elif args.cmd=='state':value=k.state()
        elif args.cmd=='verify':value={'verified_events':k.verify()}
        else:value=k.history()
        print(json.dumps(value,ensure_ascii=False,default=str,indent=2))
if __name__=='__main__':main()
