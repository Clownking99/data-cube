import pathlib,json,sys
check=pathlib.Path(sys.argv[1]);record=json.loads(check.read_text());directory=pathlib.Path(record['receipt']['directory']);request=json.loads((directory/'request.json').read_text());gate=pathlib.Path(request['argv'][-2]);result=dict(path=str(gate),pathLength=len(str(gate)),pythonVersion=sys.version,isFile=gate.is_file(),matchesHostGate=str(gate).replace('\\','/')==(request['identity']+'.tail-release').replace('\\','/'))
try:result['statLength']=gate.stat().st_size
except OSError as error:result['statFailure']=dict(type=type(error).__name__,errno=error.errno,winerror=error.winerror,message=str(error))
print(json.dumps(result,ensure_ascii=False,indent=2))