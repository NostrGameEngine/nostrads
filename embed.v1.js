(() => {
    const script = document.currentScript;
    if (!script) return;
    const {appKey = '', size = '256x256', content = 'images', network = 'production', target} = script.dataset;
    if (!['production', 'demo'].includes(network)) return;
    const placement = target ? document.getElementById(target) : null;
    if (target && !placement) return;
    const source = new URL('./embed.html', script.src);
    source.search = new URLSearchParams({appKey, size, content, network});
    const frame = document.createElement('iframe');
    frame.title = 'Advertisement';
    frame.loading = 'lazy';
    frame.referrerPolicy = 'no-referrer';
    const [width, height] = /^(256x256|250x250|512x256|256x512|512x128|480x60|720x90|128x512)$/.test(size)
        ? size.split('x').map(Number) : [256, 256];
    frame.width = width;
    frame.height = height;
    frame.style.cssText = `display:block;border:0;width:${width}px;max-width:100%;height:auto;aspect-ratio:${width}/${height};`;
    frame.src = source.href;
    if (placement) placement.replaceChildren(frame);
    else script.before(frame);
})();
