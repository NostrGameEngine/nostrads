import {createHash} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';

// Keep published loader versions so existing snippets retain their SRI hashes.
const file = 'embed.v2.js';
const root = new URL('../src/', import.meta.url);
const bytes = await readFile(new URL(file, root));
const integrity = 'sha384-' + createHash('sha384').update(bytes).digest('base64');
await writeFile(new URL('embed-integrity.json', root), JSON.stringify({file, integrity}, null, 2) + '\n');
