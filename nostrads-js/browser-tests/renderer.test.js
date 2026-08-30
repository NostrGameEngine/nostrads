import Renderer, {safeImageUrl, safeLinkUrl} from '../src/console/ad-render.js';

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

try {
    const element = document.querySelector('#ad');
    let successes = 0;
    let errors = 0;
    const payload = '<img src=x onerror="window.__nostradsXss=true">';
    const disposers = Renderer.render(
        element,
        {
            mimeType: 'text/plain',
            payload,
            link: 'https://example.com/offer',
            description: '<script>window.__nostradsXss=true</script>',
            actionType: 'view',
            callToAction: 'Open'
        },
        () => successes++,
        () => errors++
    );

    assert(successes === 1, 'text ad was not confirmed exactly once');
    assert(errors === 0, 'safe ad produced a rendering error');
    assert(element.textContent.includes(payload), 'payload was not rendered as text');
    assert(!element.querySelector('img') && !element.querySelector('script'), 'untrusted text became executable DOM');
    assert(window.__nostradsXss !== true, 'untrusted ad content executed');
    assert(Array.isArray(disposers), 'renderer did not return disposers');

    assert(safeLinkUrl('javascript:alert(1)') === null, 'javascript link was accepted');
    assert(safeLinkUrl('https://user:password@example.com/') === null, 'credentialed link was accepted');
    assert(safeLinkUrl('https://example.com/path')?.startsWith('https://'), 'safe HTTPS link was rejected');
    assert(safeImageUrl('data:image/svg+xml,<svg/>') === null, 'data image was accepted');
    assert(safeImageUrl('//evil.example/image.png') === null, 'protocol-relative cross-origin image was accepted');
    assert(safeImageUrl('https://evil.example/image.png') === null, 'unapproved cross-origin image was accepted');
    assert(
        safeImageUrl('https://cdn.example/image.png', ['https://cdn.example'])?.startsWith('https://cdn.example/'),
        'explicitly approved image origin was rejected'
    );

    let unsafeErrors = 0;
    Renderer.render(
        document.createElement('div'),
        {
            mimeType: 'text/plain',
            payload: 'unsafe',
            link: 'javascript:alert(1)',
            description: '',
            actionType: 'view',
            callToAction: ''
        },
        () => { throw new Error('unsafe ad was confirmed'); },
        () => unsafeErrors++
    );
    assert(unsafeErrors === 1, 'unsafe link was not rejected');

    const imageElement = document.createElement('div');
    document.body.appendChild(imageElement);
    let imageSuccesses = 0;
    let imageErrors = 0;
    let resolveImage;
    const imageLoaded = new Promise(resolve => { resolveImage = resolve; });
    Renderer.render(
        imageElement,
        {
            mimeType: 'image/png',
            payload: '/browser-tests/test-image.svg',
            link: 'https://example.com/image-offer',
            description: 'Loaded image',
            actionType: 'view',
            callToAction: 'Open image'
        },
        () => {
            imageSuccesses++;
            resolveImage();
        },
        () => imageErrors++
    );
    assert(imageSuccesses === 0, 'image impression was confirmed before loading');
    await Promise.race([
        imageLoaded,
        new Promise((_, reject) => setTimeout(() => reject(new Error('image render timed out')), 3000))
    ]);
    assert(imageSuccesses === 1, 'loaded image was not confirmed exactly once');
    assert(imageErrors === 0, 'safe image produced a rendering error');
    assert(imageElement.style.backgroundImage.includes('test-image.svg'), 'loaded image was not rendered');

    const OriginalIntersectionObserver = window.IntersectionObserver;
    window.IntersectionObserver = class {
        constructor(callback) { this.callback = callback; }
        observe(target) { this.callback([{target, isIntersecting: true, intersectionRatio: 1}]); }
        disconnect() {}
    };
    const originalHasFocus = document.hasFocus;
    document.hasFocus = () => true;
    const attentionElement = document.createElement('div');
    document.body.appendChild(attentionElement);
    let attentionSuccesses = 0;
    Renderer.render(
        attentionElement,
        {
            mimeType: 'image/png',
            payload: '/browser-tests/test-image.svg',
            link: 'https://example.com/attention-offer',
            description: 'Attention image',
            actionType: 'attention',
            callToAction: 'Open image'
        },
        () => attentionSuccesses++,
        error => { throw new Error(`attention image failed: ${error}`); }
    );
    await new Promise(resolve => setTimeout(resolve, 1200));
    assert(attentionSuccesses === 1, 'attention image did not confirm after rendering while already intersecting');
    window.IntersectionObserver = OriginalIntersectionObserver;
    document.hasFocus = originalHasFocus;
    document.body.dataset.testStatus = 'passed';
} catch (error) {
    document.body.dataset.testStatus = 'failed';
    document.body.dataset.testError = String(error?.stack || error);
}
