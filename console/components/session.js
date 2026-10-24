import NostrAds from '../nostr-ads.js';

let current = null;
let pending = null;
export const sessionStorageKey = 'nostrads:account:v1';
const clients = new Set();
export const getSession = () => current;
const changed = () => window.dispatchEvent(new Event('nostrads:session'));

export function cancelLogin() {
    if (!pending) return;
    const attempt = pending;
    pending = null;
    attempt.signer.logout();
    attempt.reject(new DOMException('Login cancelled', 'AbortError'));
}

function savedAccount() {
    try {
        return localStorage.getItem(sessionStorageKey);
    } catch (_) {
        return null;
    }
}

function forgetAccount() {
    try {
        localStorage.removeItem(sessionStorageKey);
    } catch (_) {}
}

function rememberAccount() {
    try {
        localStorage.setItem(
            sessionStorageKey,
            JSON.stringify({ version: 1, pubkey: current.pubkey, method: current.method, state: current.signer.exportState() })
        );
    } catch (_) {
        forgetAccount();
    }
}

function authenticate(method, start, expectedPubkey, expectedSavedAccount) {
    cancelLogin();
    const signer = NostrAds.newSignerSession();
    return new Promise((resolve, reject) => {
        const attempt = { signer, reject };
        pending = attempt;
        try {
            start(signer, (pubkey, error) => {
                if (pending !== attempt) return;
                pending = null;
                if (expectedSavedAccount !== undefined && savedAccount() !== expectedSavedAccount) {
                    signer.logout();
                    reject(new DOMException('Saved account changed', 'AbortError'));
                    return;
                }
                if (error || (expectedPubkey && pubkey !== expectedPubkey)) {
                    signer.logout();
                    reject(new Error(error || 'The account has changed. Log in again.'));
                    return;
                }
                closeSession();
                current = { pubkey, method, auth: signer.getAuth(), signer };
                if (expectedSavedAccount === undefined) rememberAccount();
                changed();
                resolve(current);
            }, attempt);
        } catch (error) {
            if (pending === attempt) pending = null;
            signer.logout();
            reject(error);
        }
    });
}

export function login(method, value, password, relays, notice) {
    return authenticate(method, (signer, callback, attempt) =>
        signer.login(method, value, password, relays, callback, (type, url) => {
            if (pending === attempt) notice?.(type, url);
        })
    );
}

export async function restoreSession(notice = (type, url) => {
    window.dispatchEvent(new CustomEvent('nostrads:signer-notice', { detail: { type, url } }));
}) {
    const saved = savedAccount();
    if (!saved || current || pending) return current;
    try {
        const account = JSON.parse(saved);
        if (
            account.version !== 1 ||
            !/^[0-9a-f]{64}$/.test(account.pubkey) ||
            !['extension', 'nsec', 'ncryptsec', 'bunker', 'connect'].includes(account.method) ||
            typeof account.state !== 'string'
        ) throw new Error('Invalid saved account');
        return await authenticate(account.method, (signer, callback, attempt) =>
            signer.restore(account.state, callback, (type, url) => {
                if (pending === attempt) notice?.(type, url);
            }), account.pubkey, saved
        );
    } catch (error) {
        if (error.name !== 'AbortError' && savedAccount() === saved) forgetAccount();
        if (error.name !== 'AbortError') {
            window.dispatchEvent(new CustomEvent('nostrads:restore-error', { detail: error.message }));
        }
        return null;
    }
}

function closeSession() {
    cancelLogin();
    const previous = current;
    current = null;
    for (const client of clients) client.close();
    clients.clear();
    previous?.signer.logout();
    if (previous) changed();
}

export function logout() {
    forgetAccount();
    closeSession();
}

export function createClient(relays, blossomEndpoints) {
    if (!current) throw new Error('Log in to manage your campaigns.');
    const client = NostrAds.newAdvertiserClient(relays, current.auth, blossomEndpoints);
    const close = client.close;
    client.close = () => {
        clients.delete(client);
        close();
    };
    clients.add(client);
    return client;
}

window.addEventListener('pagehide', closeSession);
window.addEventListener('pageshow', (event) => {
    if (event.persisted) restoreSession();
});
window.addEventListener('storage', (event) => {
    if (event.key !== sessionStorageKey && event.key !== null) return;
    closeSession();
    restoreSession();
});
