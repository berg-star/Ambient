// Explicit two-request live probe at the official Beijing example coordinates, never phone location.
// No redirects, retries, raw errors, credential identifiers or tokens are logged.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
async function main() {
  const c = JSON.parse(fs.readFileSync(path.join(root, 'qweather.local.json'), 'utf8').replace(/^\uFEFF/, ''));
  if (!/^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.qweatherapi\.com$/i.test(c.apiHost)) throw Error();
  for (const field of ['developerId', 'projectId', 'credentialId']) if (!/^[A-Za-z0-9]+$/.test(c[field])) throw Error();
  const key = crypto.createPrivateKey(fs.readFileSync(path.resolve(root, c.privateKeyFile)));
  if (key.asymmetricKeyType !== 'ed25519') throw Error();
  const unix = Math.floor(Date.now()/1000);
  const header = Buffer.from(JSON.stringify({alg:'EdDSA',kid:c.credentialId})).toString('base64url');
  const body = Buffer.from(JSON.stringify({iss:c.developerId,sub:c.projectId,iat:unix-30,exp:unix+600})).toString('base64url');
  const input = `${header}.${body}`;
  const token = `${input}.${crypto.sign(null, Buffer.from(input), key).toString('base64url')}`;
  const folder = path.join(root, '.tools', 'precipitation-probe');
  fs.mkdirSync(folder, {recursive:true});
  for (const [name, route] of [
    ['minutely', '/v7/minutely/5m?location=116.41%2C39.92&lang=zh'],
    ['hourly', '/weather/v1/hourly/39.92/116.41?hours=72&lang=zh']
  ]) {
    const response = await fetch(`https://${c.apiHost}${route}`, {
      headers:{Authorization:`Bearer ${token}`}, redirect:'error', signal:AbortSignal.timeout(25000)
    });
    const data = await response.json();
    const known = ['security-restriction','no-credit','overdue','invalid-host','unauthorized','invalid-parameters'];
    const errorKind = known.find(v => data.error?.type === `https://dev.qweather.com/docs/resource/error-code/#${v}`);
    console.log(JSON.stringify({service:name,status:response.status,code:data.code,errorKind,
      points:data.minutely?.length ?? data.hours?.length,hasUpdateTime:!!data.updateTime}));
    if (!response.ok || (data.code !== undefined && data.code !== '200')) { process.exitCode=1; return; }
    fs.writeFileSync(path.join(folder, `${name}.json`), JSON.stringify(data,null,2));
  }
}
main().catch(() => { console.error('Precipitation probe failed (configuration or network). No secrets logged.'); process.exitCode=1; });
