import { safeLinkUrl, safeImageUrl } from './ad-render.js';
import showSettingsDialog, {
    getConfig,
    getRelays,
    getBlossomEndpoints,
    getImageOrigins,
} from './components/options.js';
import showLogin from './components/login.js';
import { getAds } from './components/adstore.js';
import connectQr from './components/connect-qr.js';
import {loadDelegateDirectory} from './components/delegates.js';
import { login, logout, cancelLogin, getSession, createClient, restoreSession } from './components/session.js';

const workspace = document.querySelector('#workspace');
const dialog = document.querySelector('#auth-dialog');
const qr = connectQr(document.body);
let method = 'extension';
let authAttempt = 0;
let generation = 0;
let disposeView = () => {};
let restoringApproval = false;

function openLogin() {
    document.querySelector('#auth-title').textContent = getSession() ? 'Switch Nostr account' : 'Log in to NostrAds';
    if (!dialog.open) dialog.showModal();
}
window.addEventListener('nostrads:login-request', openLogin);
document.querySelector('#login').addEventListener('click', () => {
    if (!getSession()) openLogin();
    else {
        document.querySelector('#auth-title').textContent = 'Switch Nostr account';
        openLogin();
    }
});
document.querySelector('#logout').addEventListener('click', logout);
document.querySelector('#auth-close').addEventListener('click', () => {
    clearLogin();
    dialog.close();
});
dialog.addEventListener('close', clearLogin);
dialog.addEventListener('cancel', clearLogin);
function clearLogin() {
    restoringApproval = false;
    authAttempt++;
    cancelLogin();
    qr.clear();
    document.querySelector('#connect-invitation').hidden = true;
    dialog.querySelectorAll('input, textarea').forEach((input) => {
        input.value = '';
    });
    document.querySelector('#auth-notice').hidden = true;
    document.querySelector('#auth-approval').hidden = true;
    document.querySelector('#auth-approval').removeAttribute('href');
    document.querySelector('#auth-status').textContent = '';
    setBusy(false);
}
function setBusy(busy) {
    document.querySelector('#auth-submit').disabled = busy;
    document.querySelector('#generate-connect').disabled = busy;
    dialog.querySelectorAll('[data-method]').forEach((button) => {
        button.disabled = busy;
    });
}
function signerNotice(type, url) {
    const status = document.querySelector('#auth-status');
    document.querySelector('#auth-notice').hidden = false;
    if (type === 'nostrconnect') {
        document.querySelector('#connect-invitation').hidden = false;
        document.querySelector('#connect-link').value = url;
        qr.show(url);
    }
    if (type === 'auth_url') {
        const safe = safeLinkUrl(url);
        if (!safe) {
            status.textContent = 'The signer returned an unsupported approval link.';
            return;
        }
        const link = document.querySelector('#auth-approval');
        link.href = safe;
        link.hidden = false;
        status.textContent = 'Open the approval link to continue in your signer.';
    }
}
window.addEventListener('nostrads:signer-notice', ({ detail }) => {
    restoringApproval = true;
    openLogin();
    setBusy(true);
    signerNotice(detail.type, detail.url);
});
window.addEventListener('nostrads:restore-error', () => {
    if (!restoringApproval) return;
    restoringApproval = false;
    setBusy(false);
    document.querySelector('#auth-status').textContent = 'Could not reconnect to your signer. Log in again.';
});
for (const button of dialog.querySelectorAll('[data-method]')) {
    button.addEventListener('click', () => {
        method = button.dataset.method;
        qr.clear();
        document.querySelector('#connect-invitation').hidden = true;
        dialog
            .querySelectorAll('[data-method]')
            .forEach((item) => item.setAttribute('aria-pressed', String(item === button)));
        dialog.querySelectorAll('[data-auth]').forEach((item) => {
            item.hidden = item.dataset.auth !== method;
        });
        dialog.querySelectorAll('input, textarea').forEach((input) => {
            input.value = '';
        });
        document.querySelector('#auth-notice').hidden = true;
        document.querySelector('#auth-status').textContent = '';
        document.querySelector('#auth-submit').textContent = {
            extension: 'Log in with extension',
            bunker: 'Connect remote signer',
            nsec: 'Log in with private key',
            ncryptsec: 'Unlock and log in',
        }[method];
    });
}
async function authenticate(selectedMethod) {
    const attempt = ++authAttempt;
    const status = document.querySelector('#auth-status');
    const value = {
        extension: '',
        bunker: document.querySelector('#bunker-url').value.trim(),
        nsec: document.querySelector('#nsec-key').value.trim(),
        ncryptsec: document.querySelector('#encrypted-key').value.trim(),
        connect: '',
    }[selectedMethod];
    const password = document.querySelector('#key-password').value;
    if (selectedMethod === 'extension' && !window.nostr) {
        status.textContent = 'No NIP-07 extension was found. Enable your extension or choose another login method.';
        return;
    }
    const isHexKey = selectedMethod === 'nsec' && /^[0-9a-f]{64}$/i.test(value);
    if (['nsec', 'ncryptsec'].includes(selectedMethod) && !isHexKey && !value.startsWith(selectedMethod + '1')) {
        status.textContent = `Enter a valid ${selectedMethod} key.`;
        return;
    }
    if (selectedMethod === 'bunker' && !value.startsWith('bunker://')) {
        status.textContent = 'Paste a bunker:// link, or generate a nostrconnect link.';
        return;
    }
    qr.clear();
    document.querySelector('#connect-invitation').hidden = true;
    setBusy(true);
    status.textContent = selectedMethod === 'ncryptsec' ? 'Unlocking your key…' : 'Connecting…';
    try {
        // Let the pending state paint before key derivation begins.
        await new Promise((resolve) => setTimeout(resolve, 30));
        if (!dialog.open || attempt !== authAttempt) return;
        await login(selectedMethod, value, password, await getRelays(), signerNotice);
        dialog.close();
    } catch (error) {
        if (attempt === authAttempt && error.name !== 'AbortError') {
            qr.clear();
            document.querySelector('#connect-invitation').hidden = true;
            document.querySelector('#connect-link').value = '';
            status.textContent = error.message;
        }
    } finally {
        if (attempt !== authAttempt) return;
        dialog.querySelectorAll('input').forEach((input) => {
            input.value = '';
        });
        document.querySelector('#encrypted-key').value = '';
        setBusy(false);
    }
}
document.querySelector('#auth-form').addEventListener('submit', (event) => {
    event.preventDefault();
    authenticate(method);
});
document.querySelector('#generate-connect').addEventListener('click', () => authenticate('connect'));
document.querySelector('#copy-connect').addEventListener('click', async () => {
    const input = document.querySelector('#connect-link');
    try {
        await navigator.clipboard.writeText(input.value);
    } catch (_) {
        input.focus();
        input.select();
        document.execCommand('copy');
    }
});

document.querySelector('#settings').addEventListener('click', () => showSettingsDialog(document.body));
async function updateAccount() {
    showLogin(document.body);
    document.querySelector('#logout').hidden = !getSession();
    const relays = await getRelays();
    document.querySelector('#network-summary').textContent = `${relays.length} configured relays`;
    document.querySelector('#demo-badge').hidden = !(await getConfig()).testmode;
}
window.addEventListener('nostrads:session', () => {
    updateAccount();
    if (restoringApproval && getSession()) dialog.close();
    if (!getSession()) {
        location.hash = 'campaigns';
        renderRoute();
    } else if (!workspace.querySelector('#adForm')) renderRoute();
});
window.addEventListener('nostrads:network', () => {
    updateAccount();
    renderRoute();
});
window.addEventListener('hashchange', renderRoute);

const tag = (event, key) => event.tags?.find((item) => item[0] === key)?.[1];
function campaign(event, live) {
    const content = JSON.parse(event.content || '{}');
    const expiry = Number(tag(event, 'expiration'));
    return {
        event,
        content,
        live,
        status: expiry && expiry * 1000 <= Date.now() ? 'expired' : live ? 'published' : 'local',
        expiry,
        format: tag(event, 'm')?.startsWith('image/') ? 'image' : 'text',
        size: tag(event, 's') || '—',
        action: tag(event, 'k'),
        title: content.description || 'Untitled campaign',
    };
}
function openCampaign(item) {
    const params = new URLSearchParams({
        see: encodeURIComponent(JSON.stringify(item.event)),
        isLive: String(item.live),
    });
    location.hash = `edit?${params}`;
}
function textCell(value, className) {
    const element = document.createElement('span');
    element.textContent = value;
    if (className) element.className = className;
    return element;
}
async function dashboard(epoch) {
    document.title = 'Campaigns · NostrAds';
    workspace.innerHTML = `
        <div class="page-heading"><div><p class="eyebrow">Campaign manager</p><h1>Campaigns</h1><p class="muted">Manage your ads, placements and budgets.</p></div><a class="button" id="createNew" href="#new">＋ New campaign</a></div>
        <div class="metrics"><div><span>Active</span><strong id="published-count">—</strong><small>Running campaigns</small></div><div><span>Expired</span><strong id="expired-count">—</strong><small>Past their end date</small></div><div><span>Saved on this device</span><strong id="local-count">—</strong><small>Saved copies</small></div></div>
        <div class="campaign-panel"><div class="panel-toolbar"><h2>All campaigns <span class="count" id="campaign-count">0</span></h2><button type="button" class="secondary" id="refresh">↻ Refresh</button></div>
        <div class="filters"><label class="search-label"><span class="sr-only">Search campaigns</span><input id="campaign-search" type="search" placeholder="Search campaigns…"></label><select id="status-filter" aria-label="Campaign status"><option value="all">All statuses</option><option value="published">Published</option><option value="expired">Expired</option><option value="local">Saved locally</option></select><select id="format-filter" aria-label="Creative format"><option value="all">All formats</option><option value="image">Image</option><option value="text">Text</option></select></div>
        <p id="console-status" role="status"></p><div id="campaign-list"></div></div>
        `;
    document.querySelector('#refresh').addEventListener('click', renderRoute);
    const list = document.querySelector('#campaign-list');
    const status = document.querySelector('#console-status');
    const refresh = document.querySelector('#refresh');
    let items = [];
    let client;
    disposeView = () => {
        client?.close();
    };
    function empty(title, description, loginAction = false) {
        list.replaceChildren();
        const box = document.createElement('div');
        box.className = 'empty-state';
        box.append(textCell('▦', 'empty-icon'), textCell(title, 'empty-title'), textCell(description, 'muted'));
        if (loginAction) {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = 'Log in';
            button.addEventListener('click', openLogin);
            box.append(button);
        }
        list.append(box);
    }
    if (!getSession()) {
        status.textContent = 'Log in to see your campaigns.';
        refresh.disabled = true;
        empty('Manage your campaigns', 'Log in to manage your ads, or create a new campaign to get started.', true);
        return;
    }
    status.textContent = 'Loading your campaigns…';
    refresh.disabled = true;
    try {
        const saved = await getAds();
        if (epoch !== generation) return;
        client = createClient(await getRelays(), await getBlossomEndpoints());
        let live = [];
        let failed = false;
        try {
            live = await client.list();
        } catch (_) {
            failed = true;
        }
        if (epoch !== generation) return;
        const merged = new Map();
        for (const event of saved) {
            try {
                if (event.pubkey === getSession()?.pubkey) merged.set(event.id, campaign(event, false));
            } catch (_) {}
        }
        for (const event of live) {
            try {
                merged.set(event.id, campaign(event, true));
            } catch (_) {}
        }
        items = [...merged.values()].sort((a, b) => b.event.created_at - a.event.created_at);
        document.querySelector('#published-count').textContent = items.filter(
            (item) => item.status === 'published'
        ).length;
        document.querySelector('#expired-count').textContent = items.filter((item) => item.status === 'expired').length;
        document.querySelector('#local-count').textContent = items.filter((item) => !item.live).length;
        document.querySelector('#campaign-count').textContent = items.length;
        status.textContent = failed
            ? 'Could not refresh your campaigns. Showing saved copies. Check your connection and try again.'
            : `Last updated at ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
        const origins = await getImageOrigins();
        if (epoch !== generation) return;
        function draw() {
            const query = document.querySelector('#campaign-search').value.toLowerCase().trim();
            const selectedStatus = document.querySelector('#status-filter').value;
            const selectedFormat = document.querySelector('#format-filter').value;
            const visible = items.filter(
                (item) =>
                    (selectedStatus === 'all' || item.status === selectedStatus) &&
                    (selectedFormat === 'all' || item.format === selectedFormat) &&
                    `${item.title} ${item.event.id}`.toLowerCase().includes(query)
            );
            list.replaceChildren();
            if (!visible.length) {
                empty(
                    items.length ? 'No matching campaigns' : 'No campaigns yet',
                    items.length ? 'Try another search or filter.' : 'Create a campaign to publish your first ad.'
                );
                return;
            }
            const heading = document.createElement('div');
            heading.className = 'campaign-columns';
            for (const title of ['Campaign', 'Status', 'Bid / action', 'End date', '']) heading.append(textCell(title));
            list.append(heading);
            for (const item of visible) {
                const row = document.createElement('button');
                row.type = 'button';
                row.className = 'campaign-row';
                row.setAttribute('aria-label', `Open ${item.title}`);
                const creative = document.createElement('div');
                creative.className = 'campaign-creative';
                const preview = document.createElement('div');
                preview.className = 'campaign-thumb';
                const imageUrl = item.format === 'image' ? safeImageUrl(item.content.payload, origins) : null;
                if (imageUrl) {
                    const image = document.createElement('img');
                    image.src = imageUrl;
                    image.alt = '';
                    image.referrerPolicy = 'no-referrer';
                    image.addEventListener('error', () => {
                        preview.textContent = 'Image unavailable';
                    });
                    preview.append(image);
                } else {
                    preview.textContent = item.format === 'text' ? 'Aa' : 'Image unavailable';
                }
                const description = document.createElement('div');
                description.className = 'campaign-description';
                description.append(
                    textCell(item.title, 'campaign-name'),
                    textCell(`${item.format === 'image' ? 'Image' : 'Text'} · ${item.size}`, 'muted')
                );
                creative.append(preview, description);
                const state = textCell(
                    { published: 'Active', expired: 'Ended', local: 'Saved' }[item.status],
                    `status-pill ${item.status}`
                );
                const bid = textCell(
                    `${Number.isFinite(Number(item.content.bid)) ? Number(item.content.bid) / 1000 : '—'} sats`,
                    'bid-cell'
                );
                bid.append(
                    textCell(
                        { link: 'per click', view: 'per view', attention: 'per attention' }[item.action] ||
                            'per action',
                        'muted'
                    )
                );
                const end = textCell(
                    item.expiry
                        ? new Date(item.expiry * 1000).toLocaleDateString(undefined, {
                              day: 'numeric',
                              month: 'short',
                              year: 'numeric',
                          })
                        : 'No end date',
                    'expiry-cell'
                );
                row.append(creative, state, bid, end, textCell('↗', 'row-arrow'));
                row.addEventListener('click', () => openCampaign(item));
                list.append(row);
            }
        }
        for (const id of ['campaign-search', 'status-filter', 'format-filter'])
            document.querySelector(`#${id}`).addEventListener('input', draw);
        draw();
    } catch (_) {
        if (epoch === generation)
            status.textContent = 'Could not load campaigns. Check your signer and network settings.';
    } finally {
        client?.close();
        client = null;
        if (epoch === generation) refresh.disabled = false;
    }
}

async function renderRoute() {
    const epoch = ++generation;
    disposeView();
    disposeView = () => {};
    const route = location.hash.slice(1);
    const editing = route === 'new' || route.startsWith('edit?');
    document.querySelector('#campaigns-nav').classList.toggle('active', !editing);
    workspace.classList.toggle('editor-workspace', editing);
    if (editing) {
        workspace.innerHTML = '<p role="status">Loading campaign editor…</p>';
        try {
            const [{ mountEditor }, response] = await Promise.all([
                import('./edit/edit.js'),
                fetch(new URL('./edit/form.html', import.meta.url)),
            ]);
            const html = new DOMParser().parseFromString(await response.text(), 'text/html');
            if (epoch !== generation) return;
            workspace.innerHTML = `<div class="page-heading"><div><p class="eyebrow">Campaign manager</p><h1>${
                route === 'new' ? 'New campaign' : 'Campaign details'
            }</h1><p class="muted">Set up your ad, choose where it runs and set a budget.</p></div><a class="secondary button" href="#campaigns">← All campaigns</a></div>`;
            const editor = document.createElement('div');
            editor.className = 'editor-view';
            editor.append(html.querySelector('#adForm'));
            workspace.append(editor);
            const params = new URLSearchParams(route.includes('?') ? route.slice(route.indexOf('?') + 1) : '');
            const dispose = mountEditor(editor, params);
            if (epoch !== generation) dispose();
            else disposeView = dispose;
        } catch (_) {
            if (epoch === generation) workspace.textContent = 'Could not load the editor. Refresh to try again.';
        }
    } else await dashboard(epoch);
}
document.querySelector('#login').disabled = false;
document.querySelector('#settings').disabled = false;
loadDelegateDirectory().catch(() => {});
updateAccount();
renderRoute();
restoreSession();
