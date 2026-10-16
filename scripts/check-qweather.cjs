// Local credential smoke check. Never prints credentials, private keys, or JWTs.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
async function main() {
  const c = JSON.parse(fs.readFileSync(path.join(root, 'qweather.local.json'), 'utf8').replace(/^\uFEFF/, ''));
  if (!/^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.qweatherapi\.com$/i.test(c.apiHost)) throw new Error('API Host must be the console domain only.');
  for (const field of ['developerId', 'projectId', 'credentialId']) if (!/^[A-Za-z0-9]+$/.test(c[field])) throw new Error(`Invalid ${field}`);
  const key = crypto.createPrivateKey(fs.readFileSync(path.resolve(root, c.privateKeyFile)));
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Expected Ed25519 private key.');
  const now = Math.floor(Date.now()/1000);
  const header = Buffer.from(JSON.stringify({alg:'EdDSA',kid:c.credentialId})).toString('base64url');
  const body = Buffer.from(JSON.stringify({iss:c.developerId,sub:c.projectId,iat:now-30,exp:now+600})).toString('base64url');
  const input = `${header}.${body}`;
  const token = `${input}.${crypto.sign(null,Buffer.from(input),key).toString('base64url')}`;
  const out = path.join(root,'.tools','qweather-probe');
  fs.mkdirSync(out,{recursive:true});
  // Official documentation example coordinates: Beijing; not the user's location.
  for (const [name,route] of [
    ['weather','/weather/v1/current/39.92/116.41?lang=zh'],
    ['air','/airquality/v1/current/39.92/116.41?lang=zh'],
    ['geo','/geo/v2/city/lookup?location=116.41%2C39.92&lang=zh&number=3']
  ]) {
    const response = await fetch(`https://${c.apiHost}${route}`,{headers:{Authorization:`Bearer ${token}`},redirect:'error',signal:AbortSignal.timeout(20000)});
    const data = await response.json();
    console.log(JSON.stringify({service:name,status:response.status,fields:Object.keys(data),code:data.code,indexes:data.indexes?.map(x=>({code:x.code,name:x.name})),locations:data.location?.length}));
    // Only successful public environmental data for documentation example coordinates.
    if(response.ok) fs.writeFileSync(path.join(out,`${name}.json`),JSON.stringify(data,null,2));
    if (!response.ok) process.exitCode=1;
  }
}
main().catch(()=>{console.error('QWeather validation failed (configuration, key access, or network). No secrets logged.');process.exitCode=1;});
