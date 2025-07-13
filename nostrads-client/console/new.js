

import Renderer from "../ad-render.js";
import { newAdvertiserClient } from "../nadv-client.js";
import NostrAds from '../nostr-ads.js';
import showSettingsDialog, { getRelays } from "./components/options.js";
import showLogin from "./components/login.js";
import showError from "./components/error.js";
import { saveAd, deleteAd } from "./components/adstore.js";

function getInputs(view) {
    const adForm = view.querySelector('#adForm');
    if (!adForm) {
        console.error("No ad form found in view");
        return [];
    }
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    return inputEls;
}

function getData(view){
    const inputEls = getInputs(view);
    const data = {};
    for (const inputEl of inputEls) {
        if (inputEl.style.display === 'none') continue;
        if (inputEl.type === 'file' && inputEl.files.length > 0) {
            const file = inputEl.files[0];
            if (file.type.startsWith('image/')) {
                data[inputEl.name] = URL.createObjectURL(file);
            } else {
                console.warn(`File ${file.name} is not an image, skipping.`);
            }
            continue;
        } else if (inputEl.type === 'checkbox') {
            data[inputEl.name] = inputEl.checked;
        } else if (inputEl.type === 'radio') {
            if (inputEl.checked) {
                data[inputEl.name] = inputEl.value;
            }
        } else if (inputEl.tagName.toLowerCase() === 'select' && inputEl.multiple) {
            data[inputEl.name] = Array.from(inputEl.selectedOptions).map(option => option.value);
        } else {
            if (inputEl.type === 'date') {
                const date = new Date(inputEl.value);
                if (!isNaN(date.getTime())) {
                    data[inputEl.name] = date.getTime();
                }
            } else {
                data[inputEl.name] = inputEl.value;
            }
        }

        const cnvTo = inputEl.getAttribute('cnvTo');
        if (cnvTo === 'array') {
            data[inputEl.name] = data[inputEl.name].split('\n').map(s => s.trim()).filter(s => s.length > 0);
        } else if (cnvTo === 'sats') {
            const value = parseFloat(data[inputEl.name])* 1000; // Convert to millisatoshis
            data[inputEl.name] = value;
        }
        
        if (!data[inputEl.name] ||
            (Array.isArray(data[inputEl.name]) && data[inputEl.name].length === 0)
        ) {
            data[inputEl.name] = null;
        }
    }
    if (data.delegate) {
        data.delegatePayload = {
            budget: data.budget,
            nwc: data.nwc
        }
    }
    console.log("Collected data:", data);
    return data;
}


async function loadProps(view, urlParams) {
    
    const seeArgs = urlParams.get('see');
    const editArgs = urlParams.get('edit');
    if (!seeArgs && !editArgs)  return true;
    
    let editable = !seeArgs;

    const event = JSON.parse(decodeURIComponent(seeArgs || editArgs));
    console.log("Loading ad data:", event);
    const content = JSON.parse(event.content || '{}');
    const tags = event.tags || [];
    console.log("Context:", content);

    const inputEls = getInputs(view);
    for (const el of inputEls){
        const name = el.getAttribute('name');
        const aliases = el.getAttribute('alias')?.split(',').map(s => s.trim()) || [];

        const set = (k, v) => {
            try {
                if (el.type === 'checkbox') {
                    el.checked = v;
                } else if (el.type === 'radio') {
                    if (el.value === v) {
                        el.checked = true;
                    }
                } else if (el.tagName.toLowerCase() === 'select' && el.multiple) {
                    if (el.choices) {
                        // Use Choices.js API to set values
                        console.log(`Setting multiple select ${name} to ${v}`);
                        el.choices.clearStore();
                        console.log(el.choices.store);
                        if (Array.isArray(v)) {
                            el.choices.setChoiceByValue(v);
                        } else if (v) {
                            el.choices.setChoiceByValue([v]);
                        }
                    } else {
                        // Fallback to native DOM methods
                        const options = Array.from(el.options);
                        options.forEach(option => {
                            if (Array.isArray(v)) {
                                option.selected = v.includes(option.value);
                            } else {
                                option.selected = v == option.value;
                            }
                        });
                    }
                } else {
                    const cnvTo = el.getAttribute('cnvTo');
                    if(cnvTo === 'sats') {
                        v = parseFloat(v) / 1000; // Convert from millisatoshis to satoshis
                    }
                    
                    if (cnvTo === 'array') {
                        el.value += v;
                    } 
                    el.value = v;
                }
            } catch (e) {
                console.error(`Error setting value for ${name} (${aliases.join(', ')}):`, e);
            }
        }

        for (const [k, v] of Object.entries(content)) {
            if (k === name || aliases.includes(k)) {
                console.log(`Setting ${name} to ${v} from context`);
                set(name, v);
                break;
            }
        }

        for (const tag of tags) {
            if (tag[0] === name || aliases.includes(tag[0])) {
                console.log(`Setting ${name} to ${tag[1]} from tags`);
                set(name, tag[1]);
                break;
            }
            if (aliases.includes(tag[0] + ".1")) {
                console.log(`Setting ${name} to ${tag[1]} from tag[1]`);
                set(name, tag[1]);
                break;
            }
            if (aliases.includes(tag[0] + ".2")) {
                console.log(`Setting ${name} to ${tag[1]} from tag[2]`);
                set(name, tag[2]);
                break;
            }
        }

    }

    const cancelBtn = view.querySelector('#cancel');

    cancelBtn.addEventListener('click', async (e) => {
        try{
            e.preventDefault();
            e.stopPropagation();
            const ads = NostrAds.newAdvertiserClient(await getRelays(), "nip07");
            await ads.cancel(event.id);
            ads.close();
        } catch (err) {
            console.error("Error cancelling bid:", err);
            showError(view, err);
            return;
        }


    });

    const deleteBtn = view.querySelector('#delete');
    deleteBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        deleteAd(event.id);
    });
    return editable;
}

async function showNewForm(view) {

    const adForm = view.querySelector('#adForm');
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    const previewEl = adForm.querySelector('#preview');

    // Load categories
    const cat = adForm.querySelector('select[name="category"]');
    await fetch('https://ngengine.org/docs/nip-drafts/nostr-content-taxonomy.csv').then(r => r.text()).then(t => {
        const entries = t.split('\n');
        for (let i = 1; i < entries.length; i++) {
            const l = entries[i].trim();
            const [id, parent, label, cat0, cat1, cat2, cat3] = l.split(',');
            cat.add(new Option([cat0, cat1, cat2, cat3].filter(r => r).join("/"), id));
        }

    });

    // Load languages
    const lang = adForm.querySelector('select[name="languages"]');

    await fetch('https://raw.githubusercontent.com/umpirsky/language-list/master/data/en/language.json').then(r => r.json()).then(langs => {
        for (const [code, name] of Object.entries(langs)) {
            lang.add(new Option(`${code.toUpperCase()} - ${name}`, code));
        }

    });


    const urlParams = new URLSearchParams(window.location.search);

    let editable = await loadProps(view, urlParams);
    let isLive = urlParams.get('isLive') === 'true';

    const cloneBtn = view.querySelector('#clone');
    const saveBtn = view.querySelector('#submit');
    const cancelBtn = view.querySelector('#cancel');
    const deleteBtn = view.querySelector('#delete');

    if (cloneBtn) {
        cloneBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            const url = new URL("new.html", window.location.href);
            const urlParams = new URLSearchParams(window.location.search);
            url.searchParams.set('edit', urlParams.get('see') || urlParams.get('edit') || '');
            window.location.href = url.toString();
        });
        cloneBtn.style.display = !editable ? 'inline-block' : 'none';
    }


    if (saveBtn) {
        if (!editable) {
            saveBtn.style.display = 'none';
            cancelBtn.style.display = 'inline-block';
        } else {
            saveBtn.style.display = 'inline-block';
            cancelBtn.style.display = 'none';
        }
    }

    if (isLive) {
        cancelBtn.style.display = 'inline-block';
        deleteBtn.style.display = 'none';
    } else {
        cancelBtn.style.display = 'none';
        deleteBtn.style.display = 'inline-block';
    }

    saveBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const data = getData(view);
        console.log("Submitting data:", data);
        try {
            const ads = NostrAds.newAdvertiserClient(await getRelays(), "nip07");
            const event = await ads.publish(data);
            saveAd(event);
            console.log("Ad published:", event);

            ads.close();
        } catch (err) {
            console.error("Error publishing bid:", err);
            showError(view, err);
            return;
        }
    });

    
 
    // load choices.js

    const preSelectedCategories = Array.from(cat.selectedOptions).map(option => option.value);
    const preSelectedLanguages = Array.from(lang.selectedOptions).map(option => option.value);


    const categorySelect = new Choices('#adForm select[name="category"]', {
        removeItemButton: true,
        searchEnabled: true,
        searchPlaceholderValue: "Search categories...",
        placeholderValue: "Select categories",
        classNames: {
            containerOuter: 'choices choices--purple'
        }
    });

    const languageSelect = new Choices('#adForm select[name="languages"]', {
        removeItemButton: true,
        searchEnabled: true,
        searchPlaceholderValue: "Search languages...",
        placeholderValue: "Select languages",
        classNames: {
            containerOuter: 'choices choices--purple'
        }
    });
    console.log("Setting pre-selected categories:", preSelectedCategories);

    if (preSelectedCategories.length > 0) categorySelect.setChoiceByValue(preSelectedCategories);
    if (preSelectedLanguages.length > 0) languageSelect.setChoiceByValue(preSelectedLanguages);
    

    if(!editable){
        languageSelect.disable();
        categorySelect.disable();
    }


    // load effects
    const actionTypeEl = adForm.querySelector('select[name="actionType"]');
    const actionDescription = adForm.querySelector('#actionDesc');

    actionTypeEl.addEventListener('change', e => {
        const titleAttribute = e.target.selectedOptions[0].getAttribute('title');
        actionDescription.innerHTML = titleAttribute || 'No description available.';
    });
    actionTypeEl.dispatchEvent(new Event('change'));

    const mimeTypeEl = adForm.querySelector('select[name="mimeType"]');
    const payloadTextEl = adForm.querySelector('#payloadText');
    const payloadImageEl = adForm.querySelector('#payloadImage');
    mimeTypeEl.addEventListener('change', e => {
        if (e.target.value.startsWith('text/')) {
            payloadTextEl.style.display = 'block';
            payloadImageEl.style.display = 'none';
        } else {
            payloadTextEl.style.display = 'none';
            payloadImageEl.style.display = 'block';
        }
    });
    mimeTypeEl.dispatchEvent(new Event('change'));



    // Handle preview rendering 

    const reloadPreview = () => {
        const data = getData(view);
        Renderer.render(
            previewEl,
            data,
            () => { },
            () => { }
        );
        const size = data.size.split('x').map(s => parseInt(s, 10));
        previewEl.style.width = `${size[0]}px`;
        previewEl.style.height = `${size[1]}px`;
    }

    for (const inputEl of inputEls) {
        inputEl.addEventListener('input', reloadPreview);
        inputEl.addEventListener('change', reloadPreview);
        inputEl.addEventListener('keyup', reloadPreview);
        if (!editable) {
            inputEl.setAttribute('readonly', '1');
            inputEl.classList.add('disabled');

        } else {
            inputEl.removeAttribute('readonly');
            inputEl.classList.remove('disabled');

        }
    }


    reloadPreview();
}


async function main() {
    try {      
        showLogin(document.body, {
            relays: getRelays()
        });
        await showNewForm(document.body)
    } catch (e) {
        console.error("Error initializing NostrAds:", e);
        document.body.classList.add('locked');
        showError(document.body, e);
    }

}

window.addEventListener('load', () => {
    main();
});