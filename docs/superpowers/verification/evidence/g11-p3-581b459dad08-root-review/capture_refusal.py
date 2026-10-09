"""Capture the existing immutable audit refusal and exact-shell identity diagnostic."""
import json
from pathlib import Path
import subprocess

base = Path(__file__).absolute().parent
python = 'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
pwsh = 'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
repo = 'D:/Projects/朝花夕拾'
scope = repo + '/docs/superpowers/verification/evidence/g11-p3-581b459dad08-full-9931c5051ca841719751e79dd32bff01/926ed1f62d8e48c99ef77fd7593d5039'
commands = [
    ('full-review-refusal', [python, '-I', '-S', '-B', str(base/'review_stage.py'), '--repo', repo, '--scope', scope, '--out', str(base/'full-review.json')], 1),
    ('identity-coercion', [pwsh, '-NoLogo', '-NoProfile', '-NonInteractive', '-File', str(base/'probe_identity.ps1'), '-Identity', scope+'/processes/java-version/root-identity.json'], 0),
]
for name, argv, expected in commands:
    result = subprocess.run(argv, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    for suffix, raw in (('stdout.raw', result.stdout), ('stderr.raw', result.stderr)):
        with (base/(name+'-'+suffix)).open('xb') as stream:
            stream.write(raw)
    with (base/(name+'-command.json')).open('x', encoding='utf-8') as stream:
        json.dump(dict(argv=argv, actualExitCode=result.returncode,
                       note='Diagnostic capture after first interactive audit refusal; immutable engineering run not repeated.'), stream, ensure_ascii=False, indent=2)
    if result.returncode != expected:
        raise RuntimeError('unexpected diagnostic exit: '+name)
    print(json.dumps(dict(diagnostic=name, actualExitCode=result.returncode, stdout=result.stdout.decode('utf-8-sig'))))
