'use strict';
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const crypto=require('node:crypto'), cp=require('node:child_process');
const [repoArg, evidenceArg, output]=process.argv.slice(2);
assert(repoArg&&evidenceArg&&output,'usage: repo evidence output');
const repo=fs.realpathSync(repoArg), evidence=fs.realpathSync(evidenceArg);
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function manifest(base,items){for(const f of items){const p=path.resolve(base,f.path);assert(p.startsWith(base+path.sep)&&!/[\\/]\.testagent([\\/]|$)/i.test(p));assert.equal(fs.statSync(p).size,f.bytes);assert.equal(sha(p),f.sha256.toLowerCase());}return items.length;}
let oldCount=0;
for(const name of ['workspace-native-exit-coordination','workspace-native-completion-coordination','workspace-native-20261004','workspace-native-20261005','workspace-native-escape-20261005']){
 const d=path.join(repo,'docs/superpowers/verification/evidence',name);
 oldCount+=manifest(name.endsWith('-coordination')?repo:d,json(path.join(d,'raw-manifest.json')).files);
}
assert.equal(oldCount,1087);
const worker=path.join(repo,'docs/superpowers/verification/evidence/formal-launcher-20261005-worker');
const workerCount=manifest(worker,json(path.join(worker,'raw-manifest.json')));
assert.equal(workerCount,26);
const baseline=json(path.join(evidence,'baseline.json'));
for(const f of baseline.files){const p=f.path.startsWith('build/')?path.join('D:/Projects/朝花夕拾',f.path):path.join(repo,f.path);assert.equal(sha(p),f.sha256.toLowerCase());}
const prepared=json(path.join(evidence,'prepared.json'));
assert.equal(sha(path.join(worker,'gate.jar')),prepared.gateSha.toLowerCase());
const changed=cp.execFileSync('git',['-C',repo,'diff','--name-only',baseline.main,'--','src','test','buildSrc','build.gradle','settings.gradle','gradle.properties','gradle','gradlew','gradlew.bat'],{encoding:'utf8'}).trim();
assert.equal(changed,'','Product/test/build must not change during tools-only acceptance');
const head=cp.execFileSync('git',['-C',repo,'rev-parse','HEAD'],{encoding:'utf8'}).trim();
const good=path.join(evidence,'runs/probe-good'), bad=path.join(evidence,'runs/probe-bad');
assert.equal(json(path.join(good,'exit.json')).exitCode,0);
assert.equal(json(path.join(bad,'exit.json')).exitCode,1);
const positive=fs.readFileSync(path.join(good,'stdout.txt'),'utf8');
assert(positive.includes('PROBE_CONFIRMED_REAL_CONNECT_403'));
const negative=fs.readFileSync(path.join(bad,'stdout.txt'),'utf8');
assert(negative.includes('Profile ownership/home mismatch')&&!negative.includes('PROBE_MAIN'));
assert(fs.readFileSync(path.join(good,'gate.log'),'utf8').includes('PROXY_REJECT CONNECT acceptance.invalid:443 HTTP/1.1'));
const launches=[];
for(const [name,closeFile] of [['first-launch','009-close-request.json'],['second-launch','017-close-request.json']]){
 const d=path.join(evidence,'runs',name), launch=json(path.join(d,'launch.json')), exit=json(path.join(d,'exit.json'));
 assert.equal(launch.action,'Launch');assert(launch.exe.endsWith('DataCube.exe'));assert.equal(launch.home,json(path.join(evidence,'runs/first-launch/launch.json')).home);
 assert.equal(exit.exitCode,0);assert(!fs.existsSync(path.join(d,'forced-stop.json')));
 assert.equal(launch.gateSha,prepared.gateSha);
 const log=fs.readFileSync(path.join(d,'gate.log'),'utf8');
 const sequence=['GATE_BEGIN','GATE_READY','PROXY_SELECT scheme=https host=api.github.com port=-1','PROXY_REJECT CONNECT api.github.com:443 HTTP/1.1','JVM_SHUTDOWN'];
 let last=-1;for(const token of sequence){const at=log.indexOf(token);assert(at>last,token);assert.equal(log.indexOf(token,at+token.length),-1);last=at;}
 assert.equal((log.match(/PROXY_SELECT /g)||[]).length,1);
 assert.equal((log.match(/PROXY_REJECT /g)||[]).length,1);
 const close=json(path.join(evidence,'native',closeFile));assert.equal(close.key,'Alt_L+F4');
 const shutdown=log.split(/\r?\n/).find(l=>l.includes('JVM_SHUTDOWN')).split(' ')[0];
 assert(Date.parse(close.utc)<Date.parse(shutdown));assert(Date.parse(shutdown)<=Date.parse(exit.utc));
 for(let i=0;i<3;i++){assert.equal(exit.artifactsAfter[i].sha256,prepared.artifacts[i].sha256);assert.equal(launch.artifacts[i].sha256,prepared.artifacts[i].sha256);}
 const stderr=fs.readFileSync(path.join(d,'stderr.txt'),'utf8');assert(stderr.includes('Picked up JAVA_TOOL_OPTIONS:'));assert(!/shutdown failure|GUI 启动失败|Exception in thread/.test(stderr));
 launches.push({name,launcherPid:exit.pid,jvmPid:Number(log.match(/GATE_BEGIN pid=(\d+)/)[1]),exitCode:exit.exitCode,shutdown,updateRequestRejectedLocally:true});
}
const first=json(path.join(evidence,'decoded-first.json')), second=json(path.join(evidence,'decoded-second.json'));
assert(first.passed&&second.passed);assert.deepEqual(first.drafts,second.drafts);
assert.deepEqual(first.workspace.entries,second.workspace.entries);
assert.equal(first.workspace.selectedIndex,0);assert.equal(second.workspace.selectedIndex,0);
for(const snapshot of ['first','second']){
 const decoded=snapshot==='first'?first:second, dir=path.join(evidence,`after-${snapshot}-close`);
 for(const draft of decoded.drafts){assert.equal(sha(path.join(dir,draft.name)),draft.sha256);assert.deepEqual(draft.connectionMetadata,[null,null,null,'']);}
 assert.equal(sha(path.join(dir,'workspace.bin')),decoded.workspace.sha256);
 for(const e of decoded.workspace.entries)assert(e.anchor===55&&e.caret===55);
}
let shots=0, states=0;
const nativeDir=path.join(evidence,'native');
for(const filename of fs.readdirSync(nativeDir).filter(n=>/^\d.*\.json$/.test(n))){
 const r=json(path.join(nativeDir,filename));
 assert.equal(r.window.app,'process:D:\\Projects\\朝花夕拾\\build\\jpackage\\DataCube\\DataCube.exe');
 if(r.screenshots){states++;for(const shot of r.screenshots){assert.equal(sha(path.join(nativeDir,shot.file)),shot.sha256);assert.equal(fs.statSync(path.join(nativeDir,shot.file)).size,shot.bytes);shots++;}}
}
assert.equal(states,15);assert.equal(shots,18);
const exited=json(path.join(evidence,'process-exit-check.json'));assert.equal(exited.forcedStop,false);assert.deepEqual(exited.remaining,[]);
const frozen=manifest(evidence,json(path.join(evidence,'raw-manifest.json')).files);
const matrix=json(path.join(evidence,'native-matrix.json'));assert.equal(matrix.passed,6);assert.equal(matrix.M8Complete,false);assert.equal(matrix.splashVisualAccepted,false);
const result={utc:new Date().toISOString(),head,passed:true,oldRawVerified:oldCount,workerRawVerified:workerCount,ownFrozenFilesVerified:frozen,nativeStates:states,screenshots:shots,launches,unchangedImageArtifacts:3,unchangedDraftFiles:2,workspaceOrderSelectionPositionsMatch:true,productChanges:false,newGradleTests:false,splashVisualAccepted:false,M8Complete:false};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(result,null,2));
