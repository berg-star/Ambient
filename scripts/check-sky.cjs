// Explicit three-request probe (+ one optional weather control), never the user's position.
// Not run by builds. No retries, redirects, credential values, JWTs, or raw error bodies.
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
  const now = new Date();
  const unix = Math.floor(now.getTime()/1000);
  const header = Buffer.from(JSON.stringify({alg:'EdDSA',kid:c.credentialId})).toString('base64url');
  const body = Buffer.from(JSON.stringify({iss:c.developerId,sub:c.projectId,iat:unix-30,exp:unix+600})).toString('base64url');
  const input = `${header}.${body}`;
  const token = `${input}.${crypto.sign(null, Buffer.from(input), key).toString('base64url')}`;
  const local = new Date(now.getTime()+8*3600*1000);
  const date = local.toISOString().slice(0,10).replaceAll('-', '');
  const time = local.toISOString().slice(11,16).replace(':', '');
  const directory = path.join(root, '.tools', 'sky-probe');
  fs.mkdirSync(directory, {recursive:true});
  const routes = [
    ['sun', `/v7/astronomy/sun?location=116.41%2C39.92&date=${date}`],
    ['moon', `/v7/astronomy/moon?location=116.41%2C39.92&date=${date}&lang=zh`],
    ['solar', `/v7/astronomy/solar-elevation-angle?location=116.41%2C39.92&date=${date}&time=${time}&tz=0800&alt=0`]
  ];
  if (process.argv.includes('--control-weather')) routes.unshift(['weather-control', '/weather/v1/current/39.92/116.41?lang=zh']);
  for (const [name, route] of routes) {
    const response = await fetch(`https://${c.apiHost}${route}`, {
      headers: {Authorization:`Bearer ${token}`}, redirect:'error', signal:AbortSignal.timeout(20000)
    });
    const data = await response.json();
    const diagnostic = JSON.stringify(data).toLowerCase();
    const errorHints = response.ok ? undefined : ['permission','authoriz','host','project','credential','expired','subscription','balance','invalid','jwt']
      .filter(word => diagnostic.includes(word));
    const knownErrors = ['invalid-parameters','missing-parameters','unauthorized','no-credit','overdue','security-restriction','invalid-host','account-suspension','deprecated','forbidden','not-found'];
    const errorKind = knownErrors.find(name => data.error?.type === `https://dev.qweather.com/docs/resource/error-code/#${name}`);
    console.log(JSON.stringify({service:name,status:response.status,code:data.code,errorKind,errorHints,
      hasSunrise:!!data.sunrise,hasSunset:!!data.sunset,moonHours:data.moonPhase?.length,
      hasSolarElevation:data.solarElevationAngle !== undefined,hasSolarAzimuth:data.solarAzimuthAngle !== undefined}));
    if (!response.ok || (data.code !== undefined && data.code !== '200')) { process.exitCode = 1; return; }
    fs.writeFileSync(path.join(directory, `${name}.json`), JSON.stringify(data,null,2));
  }
}
main().catch(() => { console.error('Astronomy check failed (configuration, key access, or network). No secrets logged.'); process.exitCode=1; });
