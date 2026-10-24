export const adSizes = ['256x256', '250x250', '512x256', '256x512', '512x128', '480x60', '720x90', '128x512'];
export const adContent = {
    images: ['image/png', 'image/jpeg', 'image/gif'],
    text: ['text/plain'],
    mixed: ['image/png', 'image/jpeg', 'image/gif', 'text/plain']
};

export function isAppKey(value) {
    return /^(npub1[023456789acdefghjklmnpqrstuvwxyz]{58}|[0-9a-f]{64})$/.test(value);
}

export async function websiteOptions(network = 'production') {
    if (!['production', 'demo'].includes(network)) throw new Error('Invalid ad network');
    const response = await fetch(new URL('./console/config.json', import.meta.url));
    if (!response.ok) throw new Error('Website configuration unavailable');
    const config = await response.json();
    const origins = new Set(config.default_blossom_relays.map(url => new URL(url).origin));
    for (const origin of [...origins]) {
        for (const cdn of config.blossom_image_origins?.[origin] ?? []) origins.add(new URL(cdn).origin);
    }
    // Public image hosts used by the network demo are also available to embeds.
    const demoResponse = await fetch(new URL('./demo/network.json', import.meta.url));
    if (demoResponse.ok) {
        const demo = await demoResponse.json();
        if (network === 'demo') {
            if (!isAppKey(demo.appKey) || !/^[0-9a-f]{64}$/.test(demo.advertiser)
                || !Array.isArray(demo.relays) || !demo.relays.length || !Array.isArray(demo.imageOrigins)
                || !demo.relays.every(value => {
                    const relay = new URL(value);
                    return relay.protocol === 'wss:' && relay.hostname && !relay.username && !relay.password;
                })) throw new Error('Invalid demo configuration');
            return {
                appKey: demo.appKey,
                relays: demo.relays,
                advertisersWhitelist: [demo.advertiser],
                allowedImageOrigins: demo.imageOrigins.map(value => new URL(value).origin)
            };
        }
        for (const origin of demo.imageOrigins) origins.add(new URL(origin).origin);
    }
    if (network === 'demo') throw new Error('Demo configuration unavailable');
    return {relays: config.default_relays, allowedImageOrigins: [...origins]};
}
