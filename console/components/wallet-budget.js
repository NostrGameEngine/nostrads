import NostrAds from '../nostr-ads.js';
import {getConfig} from './options.js';

const day = 86400000;
const sats = msats => new Intl.NumberFormat('en-US', {maximumFractionDigits: 3}).format(msats / 1000);

export function campaignBudget(dailySats, endDate, now = Date.now()) {
    const dailyMsats = Math.round(Number(dailySats) * 1000);
    const end = Date.parse(endDate);
    if (!Number.isSafeInteger(dailyMsats) || dailyMsats <= 0 || !Number.isFinite(end) || end <= now) return null;
    // Date inputs use UTC midnight, matching the campaign expiration sent by the editor.
    const days = Math.ceil((end - now) / day);
    const totalMsats = dailyMsats * days;
    return Number.isSafeInteger(totalMsats) ? {dailyMsats, days, totalMsats, end} : null;
}

export async function setupWalletBudget(view, lifecycle) {
    const config = await getConfig();
    if (!lifecycle.active) return {beforePublish() { throw new Error('The campaign editor has closed.'); }};
    const input = view.querySelector('[name="nwc"]');
    const daily = view.querySelector('[name="dailyBudget"]');
    const endDate = view.querySelector('[name="expire_at"]');
    const status = view.querySelector('#wallet-budget-status');
    const warning = view.querySelector('#wallet-limit-warning');
    const detail = view.querySelector('#wallet-budget-warning-detail');
    const confirm = view.querySelector('#confirm-wallet-limit');
    const isDemo = config.testmode === true;
    let version = 0;
    let network;
    let timer;
    let budget;
    let checkedConnection;
    let state = 'empty';
    let confirmationPolicy;

    function expected() { return campaignBudget(daily.value, endDate.value); }
    function render() {
        warning.hidden = true;
        status.textContent = '';
        delete status.dataset.state;
        if (isDemo || !input.value.trim()) { state = 'empty'; return; }
        const target = expected();
        const policy = JSON.stringify([input.value, daily.value, endDate.value, target?.days]);
        if (policy !== confirmationPolicy) { confirm.checked = false; confirmationPolicy = policy; }
        if (!budget) { state = 'checking'; status.textContent = 'Checking the wallet’s spending limit…'; return; }
        const summary = target
            ? `Campaign budget: ${sats(target.dailyMsats)} sats × ${target.days} day${target.days === 1 ? '' : 's'} = ${sats(target.totalMsats)} sats.`
            : 'Choose a daily budget and a future end date to compare the wallet limit.';
        if (budget.status !== 'limited') {
            state = 'unverified';
            warning.hidden = false;
            status.textContent = target ? summary : '';
            detail.textContent = budget.status === 'unlimited'
                ? 'The wallet reports that this connection has no spending limit.'
                : budget.status === 'invalid'
                    ? 'Enter a valid Nostr Wallet Connect URL to check its spending limit.'
                    : 'This wallet’s spending limit could not be verified. Its get_budget method may be unavailable or not permitted for this connection.';
            if (budget.status === 'invalid') state = 'invalid';
            return;
        }
        if (!target) { state = 'missing-dates'; status.textContent = summary; return; }
        const remaining = Math.max(0, budget.totalMsats - budget.usedMsats);
        if (budget.period !== 'never' && budget.renewsAt * 1000 < target.end) {
            state = 'unverified';
            status.textContent = summary;
            warning.hidden = false;
            detail.textContent = `The wallet’s ${budget.period} limit renews before the campaign ends. Check that its limit covers the full campaign; it cannot be compared with a single total allowance.`;
            return;
        }
        if (remaining !== target.totalMsats) {
            state = 'mismatch';
            status.dataset.state = 'error';
            status.textContent = `${summary} The wallet has ${sats(remaining)} sats of spending allowance left (${sats(budget.totalMsats)} sats limit, ${sats(budget.usedMsats)} sats used). Adjust the wallet limit to match the campaign.`;
            return;
        }
        state = 'matched';
        status.dataset.state = 'success';
        status.textContent = `${summary} Wallet spending allowance matches.`;
    }
    function validConnection(connection) {
        try {
            const uri = new URL(connection);
            const relays = uri.searchParams.getAll('relay');
            return uri.protocol === 'nostr+walletconnect:' && /^[0-9a-f]{64}$/i.test(uri.hostname)
                && /^[0-9a-f]{64}$/i.test(uri.searchParams.get('secret') ?? '') && relays.length > 0 && relays.length <= 5;
        } catch (_) { return false; }
    }
    async function check() {
        clearTimeout(timer);
        const attempt = ++version;
        network?.close();
        network = null;
        checkedConnection = input.value.trim();
        if (isDemo || !input.value.trim()) { budget = null; render(); return; }
        if (!validConnection(input.value.trim())) { budget = {status: 'invalid'}; render(); return; }
        budget = null;
        render();
        const probe = NostrAds.newConsoleNetwork();
        network = probe;
        try {
            const result = await probe.checkWalletBudget(input.value.trim());
            if (!lifecycle.active || attempt !== version) return;
            budget = result;
            render();
        } catch (_) {
            if (!lifecycle.active || attempt !== version) return;
            budget = {status: 'unknown'};
            render();
        } finally {
            probe.close();
            if (network === probe) network = null;
        }
    }
    function connectionChanged() {
        version++;
        clearTimeout(timer);
        network?.close();
        network = null;
        budget = null;
        checkedConnection = undefined;
        confirmationPolicy = undefined;
        confirm.checked = false;
        render();
        if (!isDemo && input.value.trim()) timer = setTimeout(check, 500);
    }
    input.addEventListener('input', connectionChanged);
    input.addEventListener('change', () => {
        if (checkedConnection !== input.value.trim()) check();
    });
    for (const field of [daily, endDate]) field.addEventListener('input', render);
    lifecycle.cleanup.push(() => { version++; clearTimeout(timer); network?.close(); });
    if (input.value.trim() && !isDemo) check();
    else render();
    return {
        async beforePublish() {
            if (isDemo) return;
            await check();
            if (state === 'invalid') throw new Error('Enter a valid Nostr Wallet Connect URL.');
            if (!expected()) throw new Error('Choose a daily budget and a future end date.');
            if (state === 'mismatch') throw new Error('The wallet spending allowance must match the campaign budget.');
            if (state !== 'matched' && !confirm.checked) {
                throw new Error('Confirm that this NWC connection has a spending limit in your wallet.');
            }
        }
    };
}
