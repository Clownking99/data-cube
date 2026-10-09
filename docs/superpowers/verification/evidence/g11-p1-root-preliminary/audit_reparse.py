"""Observe ancestor-first refusal of a junction to an owned synthetic target."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

parser = argparse.ArgumentParser()
parser.add_argument('--tool', required=True)
parser.add_argument('--sha256', required=True)
parser.add_argument('--link-child', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
if hashlib.sha256(Path(args.tool).read_bytes()).hexdigest() != args.sha256:
    raise RuntimeError('FROZEN_TOOL_IDENTITY_MISMATCH')
spec = importlib.util.spec_from_file_location('frozen_evidence', args.tool)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
calls = []
original = Path.lstat

def observed(path, *params, **kwargs):
    calls.append(str(path))
    return original(path, *params, **kwargs)

with patch.object(Path, 'lstat', observed):
    try:
        module.no_links(Path(args.link_child))
    except module.Refusal as error:
        if str(error) != 'REPARSE_PATH':
            raise RuntimeError('WRONG_REFUSAL') from error
    else:
        raise RuntimeError('JUNCTION_ACCEPTED')
if str(Path(args.link_child)) in calls or calls[-1] != str(Path(args.link_child).parent):
    raise RuntimeError('LEAF_INSPECTED_BEFORE_LINK_REFUSAL')
result = {'passed': True, 'refusal': 'REPARSE_PATH', 'lstatCalls': calls,
          'leafMetadataAccessed': False, 'toolSha256': args.sha256}
with Path(args.out).open('x', encoding='utf-8') as stream:
    json.dump(result, stream, ensure_ascii=False, indent=2)
print(json.dumps({'passed': True, 'refusal': 'REPARSE_PATH', 'leafMetadataAccessed': False}))
