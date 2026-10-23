import {createServer} from 'node:http';
import {readFile, stat} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {resolve, sep, extname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

export const moduleRoot = fileURLToPath(new URL('../', import.meta.url));

export async function browserRuntime() {
    const root = resolve(moduleRoot);
    const mime = {'.html':'text/html', '.js':'text/javascript', '.mjs':'text/javascript', '.css':'text/css',
        '.json':'application/json', '.svg':'image/svg+xml', '.png':'image/png', '.webp':'image/webp'};
    const server = createServer(async (request, response) => {
        try {
            let path = resolve(root, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname));
            if (!path.startsWith(root + sep)) throw new Error('Outside root');
            if ((await stat(path)).isDirectory()) path = resolve(path, 'index.html');
            const data = await readFile(path);
            response.writeHead(200, {'Content-Type':mime[extname(path)] || 'application/octet-stream', 'Cache-Control':'no-store'});
            response.end(data);
        } catch (_) { response.writeHead(404); response.end('Not found'); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const executablePath = process.env.CHROME_BIN || (process.platform === 'darwin'
        ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
        : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync));
    try {
        const browser = await chromium.launch({headless:true, ...(executablePath ? {executablePath} : {})});
        return {browser, base, close: async () => { await browser.close(); await new Promise(resolve => server.close(resolve)); }};
    } catch (error) {
        await new Promise(resolve => server.close(resolve));
        throw error;
    }
}
