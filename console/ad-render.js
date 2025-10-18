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

    if (mimeType === "image/jpeg" || mimeType === "image/png" || mimeType === "image/png" || mimeType === "image/gif") {
        el.textContent = '';
        el.style.backgroundImage = `url(${payload})`;
        el.style.backgroundSize = "contain";
        el.style.backgroundRepeat = "no-repeat";
        el.style.backgroundPosition = "center";
        el.style.cursor = "pointer";
        
    } else if (mimeType === "text/plain") {
        el.style.backgroundImage = '';
        el.textContent = payload;
    } else {
        console.error("Unsupported mime type:", mimeType);
        errorCallback("Unsupported mime type: " + mimeType);
        return;
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
        el.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (actionType === "link") {
                successCallback();
            }
            window.open(link, '_blank');

        });
    }

    if (actionType === "attention") {
        // call successCallback only when the add enters the viewport for the first time
        let hasBeenShown = false;
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting && !hasBeenShown) {
                    hasBeenShown = true;
                    successCallback();
                    observer.disconnect(); // stop observing after the first show
                }
            });
        });
        disposer.push(() => {
            observer.disconnect();
        });
        observer.observe(el);
    } else if (actionType === "view") {
        // call successCallback as soon as the ad is loaded
        successCallback();
    }

    return disposer;
}

export default {
    render,
    renderEvent
};