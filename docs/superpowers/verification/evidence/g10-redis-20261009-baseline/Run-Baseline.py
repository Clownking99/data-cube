"""Compile only the current RESP decoder and synthetic diagnostic; never use saved profiles."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, os, subprocess, uuid

e = Path(__file__).resolve().parent
r = e.parents[4]
owned = r / 'build' / ('owned-g10-baseline-' + uuid.uuid4().hex)
owned.mkdir(parents=True)
for folder in ['home', 'temp', 'classes']:
    (owned / folder).mkdir()
jdk = Path('D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8')
env = {key: os.environ[key] for key in ['SystemRoot', 'WINDIR', 'ComSpec', 'PATHEXT', 'OS'] if key in os.environ}
env.update(JAVA_HOME=str(jdk), PATH=str(jdk / 'bin'), TEMP=str(owned / 'temp'), TMP=str(owned / 'temp'),
           USERPROFILE=str(owned / 'home'), HOME=str(owned / 'home'))
sources = [r / 'src/com/datacube/redis/RespCodec.java', r / 'src/com/datacube/redis/RedisException.java',
           e / 'RedisBudgetBaselineProbe.java']
inputs = [dict(path=f.relative_to(r).as_posix(), bytes=f.stat().st_size,
               sha256=hashlib.sha256(f.read_bytes()).hexdigest().upper()) for f in sources]
def run(name, argv):
    proc = subprocess.run(argv, cwd=owned, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    (e / (name + '.stdout.raw')).write_bytes(proc.stdout)
    (e / (name + '.stderr.raw')).write_bytes(proc.stderr)
    (e / (name + '.command.json')).write_bytes((json.dumps(dict(argv=argv, exitCode=proc.returncode,
        utc=datetime.now(timezone.utc).isoformat(), environment='cleared; OS allowlist and owned profile/temp only'),
        ensure_ascii=False, indent=2) + '\n').encode())
    assert proc.returncode == 0, name
    return proc.stdout.decode('utf-8').strip()

run('compile', [str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(owned / 'classes'), *map(str, sources)])
results = []
for name in ['array-header', 'deep-array', 'long-line']:
    result = run(name, [str(jdk / 'bin/java.exe'), '-Xmx32m', '-Xss256k',
        '-Duser.home=' + str(owned / 'home'), '-Djava.io.tmpdir=' + str(owned / 'temp'),
        '-cp', str(owned / 'classes'), 'com.datacube.redis.RedisBudgetBaselineProbe', name])
    results.append(json.loads(result))
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=r, text=True).strip()
receipt = dict(head=head, utc=datetime.now(timezone.utc).isoformat(), owned=str(owned), inputs=inputs,
               results=results, productChanged=False, newBaselineOnly=True,
               note='Expected unsafe baseline observed; these are not passing G10 acceptance tests')
(e / 'baseline.json').write_bytes((json.dumps(receipt, ensure_ascii=False, indent=2) + '\n').encode())
print(json.dumps(receipt, ensure_ascii=False))
