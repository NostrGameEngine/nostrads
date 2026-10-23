import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function prepareDemoSite(root, previousConfig, commit) {
    if (!/^[0-9a-f]{40}$/.test(commit ?? '')) throw new Error('A source commit is required.');
    const path = join(root, 'console/config.json');
    const config = JSON.parse(await readFile(path, 'utf8'));
    config.testmode = true;
    // Preserve only the fake wallet already published by the demo, never a runner secret.
    const publicTestWallet = previousConfig?.testmode_config?.nwc;
    if (previousConfig?.testmode && typeof publicTestWallet === 'string') {
        config.testmode_config = {...config.testmode_config, nwc: publicTestWallet};
    }
    await writeFile(path, `${JSON.stringify(config, null, 2)}\n`);
    for (const page of ['index.html', 'console/index.html', 'console/edit/index.html', 'console/debug.html']) {
        const path = join(root, page);
        const html = await readFile(path, 'utf8');
        const versioned = html.replace(/\b(src|href)="((?!https?:)[^"?]+\.(?:js|css))(?:\?v=[0-9a-f]{40})?"/g,
            (_, attribute, url) => `${attribute}="${url}?v=${commit}"`);
        await writeFile(path, versioned);
    }
    await writeFile(join(root, '_headers'), '/*\n  Cache-Control: no-cache\n');
    await writeFile(join(root, '.nojekyll'), '');
    await writeFile(join(root, 'deployment.json'), `${JSON.stringify({sourceCommit: commit, mode: 'demo'}, null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [root, previousPath] = process.argv.slice(2);
    const previousConfig = JSON.parse(await readFile(previousPath, 'utf8'));
    await prepareDemoSite(root, previousConfig, process.env.GITHUB_SHA);
}
