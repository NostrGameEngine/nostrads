const LINK_PROTOCOLS = new Set(["https:"]);
const IMAGE_PROTOCOLS = new Set(["https:"]);

function parseAbsoluteUrl(value) {
    if (typeof value !== "string" || value.length > 2048) return null;
    try {
        return new URL(value);
    } catch (_) {
        return null;
    }
}

export function safeLinkUrl(value) {
    const url = parseAbsoluteUrl(value);
    return url && LINK_PROTOCOLS.has(url.protocol) && !url.username && !url.password ? url.href : null;
}

export function safeImageUrl(value, allowedOrigins = []) {
    if (typeof value === "string" && value.startsWith("/") && typeof window !== "undefined") {
        try {
            const sameOriginUrl = new URL(value, window.location.origin);
            return sameOriginUrl.origin === window.location.origin ? sameOriginUrl.href : null;
        } catch (_) {
            return null;
        }
    }
    const url = parseAbsoluteUrl(value);
    if (!url) return null;
    if (
        IMAGE_PROTOCOLS.has(url.protocol) &&
        typeof window !== "undefined" &&
        (url.origin === window.location.origin || allowedOrigins.includes(url.origin))
    ) return url.href;
    if (url.protocol === "blob:" && typeof window !== "undefined" && url.origin === window.location.origin) {
        return url.href;
    }
    return null;
}

function renderEvent(
    el,
    bid,
    successCallback,
    errorCallback,
    options = {}
) {
    const content = JSON.parse(bid.content);
    const link = content.link;
    const tags = bid.tags || [];
    const mimeType = tags.find(tag => tag[0] === 'm')?.[1];
    const payload = content.payload;
    const description = content.description || "";
    const actionType = tags.find(tag => tag[0] === 'k')?.[1];
    const callToAction = content.call_to_action

    return render(
        el,
        {
            mimeType,
            payload,
            link,
            description,
            actionType,
            callToAction
        },
        successCallback,
        errorCallback,
        options
    );

}
function render(
    el, 
    {
        mimeType,
        payload,
        link,
        description,
        actionType,
        callToAction

    },
    successCallback,
    errorCallback,
    options = {}
){
    const disposer = [];
    const safeLink = safeLinkUrl(link);
    let rendered = false;
    let successSent = false;
    let viewabilityTimer = null;
    let updateViewability = () => {};

    const reportSuccess = () => {
        if (!successSent) {
            successSent = true;
            try {
                Promise.resolve(successCallback()).catch(error => errorCallback(`Ad confirmation failed: ${error}`));
            } catch (error) {
                errorCallback(`Ad confirmation failed: ${error}`);
            }
        }
    };
    const markRendered = () => {
        rendered = true;
        if (actionType === "view") reportSuccess();
        updateViewability();
    };

    if (!safeLink) {
        console.error("Unsafe ad link rejected");
        errorCallback("Unsafe ad link");
        return disposer;
    }

    if (mimeType === "image/jpeg" || mimeType === "image/png" || mimeType === "image/gif") {
        const safePayload = safeImageUrl(payload, options.allowedImageOrigins || []);
        if (!safePayload) {
            console.error("Unsafe image URL rejected");
            errorCallback("Unsafe image URL");
            return disposer;
        }
        el.textContent = '';
        el.style.backgroundImage = '';
        el.style.backgroundSize = "contain";
        el.style.backgroundRepeat = "no-repeat";
        el.style.backgroundPosition = "center";
        el.style.cursor = "pointer";

        const image = new Image();
        image.referrerPolicy = "no-referrer";
        image.onload = () => {
            el.style.backgroundImage = `url("${safePayload}")`;
            markRendered();
        };
        image.onerror = () => errorCallback("Ad image failed to load");
        image.src = safePayload;
        disposer.push(() => {
            image.onload = null;
            image.onerror = null;
            image.src = "";
        });
    } else if (mimeType === "text/plain") {
        el.style.backgroundImage = '';
        el.textContent = payload;
    } else {
        console.error("Unsupported mime type:", mimeType);
        errorCallback("Unsupported mime type: " + mimeType);
        return disposer;
    }



    let descriptionEl = el.querySelector('.nostr-ddspace-description');
    if (!descriptionEl) {
        descriptionEl = document.createElement('div');
        descriptionEl.className = 'nostr-ddspace-description';
        el.appendChild(descriptionEl);
    }

    descriptionEl.textContent = description || "";
    if (!description) {
        descriptionEl.style.visibility = 'hidden';
    } else {
        descriptionEl.style.visibility = 'visible';
    }

    let callToActionEl = el.querySelector('.nostr-ddspace-call-to-action');
    if (!callToActionEl) {
        callToActionEl = document.createElement('div');
        callToActionEl.className = 'nostr-ddspace-call-to-action';
        el.appendChild(callToActionEl);
    }

    if (callToAction) {
        callToActionEl.textContent = callToAction;
        callToActionEl.style.visibility = 'visible';
    } else {
        callToActionEl.textContent = "Learn More";
        callToActionEl.style.visibility = 'hidden';
    }

    let nostrIconEl = el.querySelector('.nostr-ddspace-icon');
    if (!nostrIconEl) {
        nostrIconEl = document.createElement('div');
        nostrIconEl.className = 'nostr-ddspace-icon';
        el.appendChild(nostrIconEl);
    }
 
    if (!options.noLink){
        const clickHandler = (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (actionType === "link") {
                reportSuccess();
            }
            window.open(safeLink, '_blank', 'noopener,noreferrer');

        };
        el.addEventListener('click', clickHandler);
        disposer.push(() => el.removeEventListener('click', clickHandler));
    }

    if (actionType === "attention") {
        let currentEntry = null;
        const isViewable = () =>
            rendered &&
            currentEntry?.isIntersecting &&
            currentEntry.intersectionRatio >= 0.5 &&
            document.visibilityState === "visible" &&
            document.hasFocus();
        updateViewability = () => {
            if (isViewable() && viewabilityTimer === null) {
                viewabilityTimer = setTimeout(() => {
                    viewabilityTimer = null;
                    if (isViewable()) {
                        reportSuccess();
                        observer.disconnect();
                    }
                }, 1000);
            } else if (!isViewable() && viewabilityTimer !== null) {
                clearTimeout(viewabilityTimer);
                viewabilityTimer = null;
            }
        };
        const observer = new IntersectionObserver((entries) => {
            currentEntry = entries[entries.length - 1] || null;
            updateViewability();
        }, {threshold: [0.5]});
        document.addEventListener("visibilitychange", updateViewability);
        window.addEventListener("blur", updateViewability);
        window.addEventListener("focus", updateViewability);
        disposer.push(() => {
            if (viewabilityTimer !== null) clearTimeout(viewabilityTimer);
            observer.disconnect();
            document.removeEventListener("visibilitychange", updateViewability);
            window.removeEventListener("blur", updateViewability);
            window.removeEventListener("focus", updateViewability);
        });
        observer.observe(el);
    }

    if (mimeType === "text/plain") markRendered();

    return disposer;
}

export default {
    render,
    renderEvent
};
