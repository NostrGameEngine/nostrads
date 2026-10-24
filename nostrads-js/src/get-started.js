import {adSizes, adContent, isAppKey, websiteOptions} from './website-options.js';

const form = document.querySelector('#website-code-form');
const keyInput = document.querySelector('#website-appkey');
const generateButton = document.querySelector('#generate-code');
const status = document.querySelector('#setup-status');
const result = document.querySelector('#website-code-result');
const code = document.querySelector('#website-code');
const copyButton = document.querySelector('#copy-website-code');
const preview = document.querySelector('#website-preview');
let generation = 0;
let activeClient;
let previewTimer;

function reset() {
    generation++;
    clearInterval(previewTimer);
    activeClient?.close();
    activeClient = null;
    result.hidden = true;
    code.textContent = '';
    preview.replaceChildren();
    status.textContent = '';
    delete status.dataset.state;
    keyInput.removeAttribute('aria-invalid');
    generateButton.disabled = false;
    generateButton.textContent = 'Generate code for my website';
    copyButton.textContent = 'Copy code';
}

function watchPreview(frame, attempt) {
    const message = document.createElement('p');
    message.id = 'website-preview-status';
    message.setAttribute('role', 'status');
    message.textContent = 'Loading demo preview…';
    const started = Date.now();
    let timer;
    function update() {
        if (attempt !== generation || !frame.isConnected) { clearInterval(timer); return; }
        let placement;
        let slot;
        try {
            placement = frame.contentDocument?.querySelector('iframe');
            slot = placement?.contentDocument?.querySelector('#website-ad');
            if (placement && placement.ownerDocument.defaultView.getComputedStyle(placement).display === 'none') {
                message.dataset.state = 'error';
                message.textContent = 'Your browser is hiding the preview. Check its content blockers and try again.';
                return;
            }
        } catch (_) {}
        if (slot?.style.backgroundImage || (slot?.firstChild?.nodeType === Node.TEXT_NODE && slot.getAttribute('role') === 'link')) {
            message.hidden = true;
            clearInterval(timer);
            return;
        }
        if (slot?.textContent === 'Ad unavailable') {
            message.dataset.state = 'error';
            message.textContent = 'The demo preview could not load. Try generating the code again.';
        } else if (!slot && Date.now() - started >= 15000) {
            message.dataset.state = 'error';
            message.textContent = 'The demo preview could not start. Check your browser’s content blockers and try again.';
        } else if (slot) {
            delete message.dataset.state;
            message.textContent = Date.now() - started >= 30000
                ? 'Waiting for a demo ad in this format. You can try another size or content type.'
                : 'Loading demo preview…';
        }
    }
    preview.prepend(message);
    frame.addEventListener('load', update);
    timer = setInterval(update, 500);
    previewTimer = timer;
    update();
}

async function embedIntegrity() {
    const response = await fetch(new URL('./embed-integrity.json', import.meta.url), {cache: 'no-cache'});
    if (!response.ok) throw new Error('Embed integrity unavailable');
    const {file, integrity} = await response.json();
    if (!/^embed\.v[1-9][0-9]*\.js$/.test(file) || !/^sha384-[A-Za-z0-9+/]{64}$/.test(integrity)) {
        throw new Error('Invalid embed integrity');
    }
    return {file, integrity};
}

function embedCode({id, scriptUrl, integrity, appKey, size, content, network}) {
    return `<div id="${id}"></div>\n\n` +
        `<script async src="${scriptUrl}"\n` +
        `        integrity="${integrity}"\n` +
        '        crossorigin="anonymous"\n' +
        (network ? `        data-network="${network}"\n` : '') +
        `        data-target="${id}"\n` +
        `        data-app-key="${appKey}"\n` +
        `        data-size="${size}"\n` +
        `        data-content="${content}"></script>`;
}

form.addEventListener('input', reset);
window.addEventListener('pagehide', reset);

form.addEventListener('submit', async event => {
    event.preventDefault();
    reset();
    const appKey = keyInput.value.trim().toLowerCase();
    if (!isAppKey(appKey)) {
        keyInput.setAttribute('aria-invalid', 'true');
        status.dataset.state = 'error';
        status.textContent = 'Enter a valid public npub for your app.';
        keyInput.focus();
        return;
    }
    const size = form.elements.size.value;
    const content = form.elements.content.value;
    if (!adSizes.includes(size) || !Object.hasOwn(adContent, content)) return;
    const attempt = generation;
    let client;
    let timer;
    generateButton.disabled = true;
    generateButton.textContent = 'Checking your profile…';
    status.textContent = 'Looking for your app’s Lightning address…';
    try {
        const [metadata, {file, integrity}] = await Promise.race([
            Promise.all([(async () => {
                const [{default: ads}, options] = await Promise.all([
                    import('./console/nostr-ads.js'), websiteOptions()
                ]);
                if (attempt !== generation) return null;
                client = ads.newAdvertiserClient(options.relays, '', []);
                activeClient = client;
                return client.getNip01Meta(appKey);
            })(), embedIntegrity()]),
            new Promise((_, reject) => {
                timer = setTimeout(() => reject(new Error('Profile lookup timed out')), 25000);
            })
        ]);
        if (attempt !== generation) return;
        const lightningAddress = typeof metadata?.lud16 === 'string' ? metadata.lud16.trim() : '';
        const lnurl = typeof metadata?.lud06 === 'string' ? metadata.lud06.trim() : '';
        if (!lightningAddress && !lnurl) {
            status.dataset.state = 'error';
            status.textContent = 'No Lightning address was found for this app. Add one to its Nostr profile, publish the update, then try again.';
            return;
        }
        if (attempt !== generation) return;
        const scriptUrl = new URL(file, import.meta.url).href;
        const id = 'nostrads-' + Array.from(crypto.getRandomValues(new Uint8Array(8)), byte => byte.toString(16).padStart(2, '0')).join('');
        const settings = {id, scriptUrl, integrity, appKey, size, content};
        code.textContent = embedCode(settings);
        status.dataset.state = 'success';
        status.textContent = lightningAddress
            ? `Lightning address found: ${lightningAddress}. Payments go to this app’s wallet.`
            : 'Lightning payment link found. Payments go to this app’s wallet.';
        result.hidden = false;
        const frame = document.createElement('iframe');
        frame.title = 'Website demo preview';
        frame.height = Number(size.split('x')[1]) + 32;
        frame.addEventListener('load', () => {
            if (attempt !== generation || !frame.isConnected) return;
            const doc = frame.contentDocument;
            const template = doc.createElement('template');
            template.innerHTML = embedCode({...settings, network: 'demo'});
            const original = template.content.querySelector('script');
            const loader = doc.createElement('script');
            for (const {name, value} of original.attributes) loader.setAttribute(name, value);
            original.replaceWith(loader);
            doc.body.replaceChildren(template.content);
        }, {once: true});
        // Firefox can reject same-origin nested frames in srcdoc under CSP 'self'.
        frame.src = new URL('./embed-preview.html', import.meta.url).href;
        preview.replaceChildren(frame);
        watchPreview(frame, attempt);
    } catch (_) {
        if (attempt !== generation) return;
        status.dataset.state = 'error';
        status.textContent = 'Could not generate the website code. Check the npub and try again in a moment.';
    } finally {
        clearTimeout(timer);
        client?.close();
        if (activeClient === client) activeClient = null;
        if (attempt === generation) {
            generateButton.disabled = false;
            generateButton.textContent = 'Generate code for my website';
        }
    }
});

copyButton.addEventListener('click', async () => {
    if (!code.textContent) return;
    try {
        await navigator.clipboard.writeText(code.textContent);
        copyButton.textContent = 'Copied';
    } catch (_) {
        const range = document.createRange();
        range.selectNodeContents(code);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        const copied = document.execCommand('copy');
        copyButton.textContent = copied ? 'Copied' : 'Code selected — copy it';
    }
});
