"""Require fresh Java exports and compare every vector/audit field to Python."""
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'services/ml-service'))
from app.features.trace_link_features import build_features

rows=json.loads((ROOT/'services/processor/target/trace-link-shadow-java.json').read_text())
for row in rows:
    expected=build_features(row['context']);actual=row['features']
    for key in expected.keys()-{'featureValues'}:
        if actual[key]!=expected[key]:raise ValueError('Audit parity mismatch: '+key)
    if len(actual['featureValues'])!=len(expected['featureValues']):raise ValueError('Vector length differs')
    for name,a,b in zip(expected['featureNames'],actual['featureValues'],expected['featureValues']):
        if (a is None)!=(b is None) or (b is not None and (a!=b if isinstance(b,int) else abs(a-b)>1e-9)):
            raise ValueError('Vector parity mismatch: '+name)
print(f'PASS: {len(rows)} fresh Java/Python vectors; exact integers and float tolerance 1e-9')
