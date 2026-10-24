import { getSession } from './session.js';

export const methodNames = {
    extension: 'Browser extension',
    bunker: 'Remote signer',
    connect: 'Remote signer',
    nsec: 'Private key',
    ncryptsec: 'Encrypted key',
};

export default function showLogin(view) {
    const user = view.querySelector('#login');
    if (!user) return;
    const session = getSession();
    user.textContent = session ? `${session.pubkey.slice(0, 8)}…${session.pubkey.slice(-6)}` : 'Log in';
    user.title = session ? `${methodNames[session.method]} · ${session.pubkey}` : 'Choose a login method';
}
