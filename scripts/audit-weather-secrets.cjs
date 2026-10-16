// Audits the working-tree candidate files and actual APK, using the local config only as needles.
// Outputs booleans/counts only. Never outputs secrets or matching contents.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const cp = require('node:child_process');
const root = path.resolve(__dirname,'..');
function main() {
  const c = JSON.parse(fs.readFileSync(path.join(root,'qweather.local.json'),'utf8').replace(/^\uFEFF/,''));
  const pem = fs.readFileSync(path.resolve(root,c.privateKeyFile),'utf8');
  const key = crypto.createPrivateKey(pem);
  const der = key.export({type:'pkcs8',format:'der'});
  const needles = [pem,pem.replace(/-----[^\n]+-----/g,'').replace(/\s/g,''),der,der.subarray(-32),
    c.developerId,c.projectId,c.credentialId,c.apiHost].filter(x=>x.length).map(x=>Buffer.isBuffer(x)?x:Buffer.from(x));
  const contains = b => needles.some(n=>b.includes(n));
  const candidates = cp.execFileSync('git',['ls-files','--cached','--others','--exclude-standard','-z'],{cwd:root}).toString().split('\0').filter(Boolean);
  for (const file of candidates) {
    if(contains(fs.readFileSync(path.join(root,file)))) throw new Error('A repository candidate contains local credential material.');
  }
  console.log(`PASS ${candidates.length} repository candidate files contain no local credentials or signing key`);
  const apk = fs.readFileSync(path.join(root,'app/build/outputs/apk/debug/app-debug.apk'));
  let eocd=-1;
  for(let i=apk.length-22;i>=Math.max(0,apk.length-65557);i--) if(apk.readUInt32LE(i)===0x06054b50) {eocd=i;break;}
  if(eocd<0) throw new Error('APK central directory missing');
  const entries=apk.readUInt16LE(eocd+10);
  let pos=apk.readUInt32LE(eocd+16);
  for(let i=0;i<entries;i++) {
    if(apk.readUInt32LE(pos)!==0x02014b50) throw new Error('Invalid APK central directory');
    const method=apk.readUInt16LE(pos+10),size=apk.readUInt32LE(pos+20),name=apk.readUInt16LE(pos+28),extra=apk.readUInt16LE(pos+30),comment=apk.readUInt16LE(pos+32),offset=apk.readUInt32LE(pos+42);
    const start=offset+30+apk.readUInt16LE(offset+26)+apk.readUInt16LE(offset+28);
    const compressed=apk.subarray(start,start+size);
    const content=method===0?compressed:method===8?zlib.inflateRawSync(compressed):null;
    if(!content || contains(content)) throw new Error('APK audit failed: unsupported entry or local credential material.');
    pos+=46+name+extra+comment;
  }
  console.log(`PASS ${entries} decompressed APK entries contain no local credentials or signing key`);
  if(process.argv.includes('--emulator')) {
    const adb=path.join(process.env.ANDROID_HOME,'platform-tools','adb.exe');
    if(!cp.execFileSync(adb,['-s','emulator-5560','emu','avd','name']).toString().startsWith('Ambient_API_31')) throw new Error('Unexpected emulator');
    const bytes=cp.execFileSync(adb,['-s','emulator-5560','shell','run-as','com.ambient.app','cat','no_backup/weather/credentials']);
    if(bytes.length<29 || contains(bytes)) throw new Error('Encrypted storage audit failed');
    console.log('PASS emulator credential file contains no plaintext local credentials or signing key');
  }
}
try { main(); } catch(e) { console.error(e.message==='A repository candidate contains local credential material.'?e.message:'Credential audit failed; no secret contents were printed.'); process.exitCode=1; }
