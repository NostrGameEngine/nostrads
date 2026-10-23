import { readFile, writeFile } from 'node:fs/promises';

// The deployment job receives only the public output of the isolated signing job.
const advertiser = process.env.NOSTRADS_DEMO_PUBLIC_KEY;
if (!/^[0-9a-f]{64}$/.test(advertiser ?? '')) throw new Error('A valid demo public key is required.');
const path = new URL('../src/demo/network.json', import.meta.url);
const config = JSON.parse(await readFile(path, 'utf8'));
config.advertiser = advertiser;
await writeFile(path, `${JSON.stringify(config, null, 2)}\n`);
