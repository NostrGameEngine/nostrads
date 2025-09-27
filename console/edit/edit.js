

import Renderer from "../ad-render.js";
import NostrAds from '../nostr-ads.js';
import showSettingsDialog, { getConfig, getRelays, getBlossomEndpoints } from "../components/options.js";
import showLogin from "../components/login.js";
import showError from "../components/error.js";
import { saveAd, deleteAd } from "../components/adstore.js";
import loadHeader from "../components/header.js";

function getInputs(view) {
    const adForm = view.querySelector('#adForm');
    if (!adForm) {
        console.error("No ad form found in view");
        return [];
    }
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    return inputEls;
}



async function getData(view, ads){
    const inputEls = getInputs(view);
    const data = {};
    for (const inputEl of inputEls) {
        if (inputEl.style.display === 'none') continue;
        if (inputEl.type === 'file' && inputEl.files.length > 0) {
            const file = inputEl.files[0];
            if (file.type.startsWith('image/')) {
                if(!ads){
                    data[inputEl.name] = URL.createObjectURL(file);
                } else {
                    const buffer = await new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = (e) => resolve(e.target.result);
                        reader.onerror = reject;
                        reader.readAsArrayBuffer(file);
                    });
                    const imgData = new Uint8Array(buffer);
                    const descriptor = await ads.uploadImage({
                        imageData: imgData,
                        mimeType: file.type
                    });
                    console.log("Image uploaded:", descriptor);
                    data[inputEl.name] = descriptor.url;
                }
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
    
    data.maxPayouts = 3; // per offerer
    data.payoutResetInterval = 3600; // in seconds
    console.log("Collected data:", data);
    return data;
}


async function loadProps(view, urlParams) {
     const seeArgs = urlParams.get('see');
    const editArgs = urlParams.get('edit');
   
   
    // if (!seeArgs && !editArgs)  return true;
    
    let editable = !seeArgs;
    console.log("Editable:", editable);

    const event = JSON.parse(decodeURIComponent(seeArgs || editArgs || "{}"));
    console.log("Loading ad data:", event);
    const content = JSON.parse(event.content || '{}');
    const tags = event.tags || [];

    const mimeType = tags.find(tag => tag[0] === 'm')?.[1] ;

    const inputEls = getInputs(view);
    for (const el of inputEls){
        const name = el.getAttribute('name');
        const aliases = el.getAttribute('alias')?.split(',').map(s => s.trim()) || [];

        const set = async (k, v) => {
            console.log("Set",k,v)
            try {
                if(el.type==="file"){           
                    await new Promise(async (res,rej) =>  {
                        try {
                            const response = await fetch(v);
                            const blob = await response.blob();
                            const mimeType = blob.type || mimeType|| 'image/png';
                            const file = new File([blob], `ad-${event.id}`, { type: mimeType });
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            el.files = dataTransfer.files;
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            res();
                        } catch (e) {
                            console.error(`Error setting file for ${name} from URL:`, e);
                            rej(e);
                        }
                    });
                
                }else if (el.type === 'checkbox') {
                    el.checked = v;
                } else if (el.type === 'radio') {
                    if (el.value === v) {
                        el.checked = true;
                    }
                } else if (el.type === 'date') {
                    const date = new Date(v);
                    console.log("Date", date);
                    
                    if(!isNaN(v)){
                        el.value = new Date(v*1000).toISOString().split('T')[0];
                    }
                } else if (el.tagName.toLowerCase() === 'select' && el.multiple) {
                    if (el.choices) {
                        // Use Choices.js API to set values
                        console.log(`Setting multiple select ${name} to ${v} using choices.js`);

                        const choices = [];
                        choices.push(...el.choices.store.getAll().map(c => c.value));
                        if (Array.isArray(v)) {
                            choices.push(...v);
                        } else if (v) {
                            choices.push(v);
                        }
                        el.choices.clearStore();                     
                        el.choices.setChoiceByValue(choices);

                    } else {
                        // Fallback to native DOM methods
                        console.log(`Setting multiple select ${name} to ${v}`);
                        const options = Array.from(el.options);
                        options.forEach(option => {
                            if (Array.isArray(v)) {
                                option.selected = option.selected ||v.includes(option.value);
                            } else {
                                option.selected = option.selected || v == option.value;
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
                await set(name, v);
                break;
            }
        }

        for (const tag of tags) {
            if (tag[0] === name || aliases.includes(tag[0])) {
                console.log(`Setting ${name} to ${tag[1]} from tags`);
                await set(name, tag[1]);
            }
            if (aliases.includes(tag[0] + ".1")) {
                console.log(`Setting ${name} to ${tag[1]} from tag[1]`);
                await set(name, tag[1]);
            }
            if (aliases.includes(tag[0] + ".2")) {
                console.log(`Setting ${name} to ${tag[1]} from tag[2]`);
                await set(name, tag[2]);
            }
        }


        const config = await getConfig();
        const isTestMode = !!config.testmode;

        const delegateInput = view.querySelector('input[name="delegate"]');
        if(delegateInput.value === "" && config.default_delegate){
            delegateInput.value = config.default_delegate;
        }
         
        console.log("Is test mode:", isTestMode);
        if(isTestMode){
            const nwcInput = view.querySelector('input[name="nwc"]');
            if(!nwcInput.value){
                nwcInput.value = config.testmode_config.nwc || "";
            }
            const nwcInfo = view.querySelector('#nwcInfo');
            if(nwcInfo){
                nwcInfo.innerHTML = `This field is in test mode and pre-filled with a working NWC URL thay you can use to test`;
            }
            nwcInput.style.display = 'block';
        }
        
        const dailyBudgetInput = view.querySelector('input[name="dailyBudget"]');
        if( dailyBudgetInput && !dailyBudgetInput.value) {
            let dailyBudget = 1000;
            if (content.bid/1000 > dailyBudget) {
                dailyBudget = content.bid/1000;
            }
            dailyBudgetInput.value = String(dailyBudget);
        }

    }

    const cancelBtn = view.querySelector('#cancel');

    cancelBtn.addEventListener('click', async (e) => {
        try{
            e.preventDefault();
            e.stopPropagation();
            if (!confirm("Are you sure you to stop this ad and cancel the bid? This action cannot be undone.")) {
                return;
            }
            const ads = NostrAds.newAdvertiserClient(await getRelays(), "nip07", await getBlossomEndpoints());
         
         
            await ads.cancel(event.id);
            ads.close();
            window.location.href = "/console/";
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
        if (!confirm("Are you sure you want to delete this ad? This action cannot be undone.")) {
            return;
        }

        if (mimeType && mimeType.startsWith('image/')) {
            const ads = NostrAds.newAdvertiserClient(await getRelays(), "nip07", await getBlossomEndpoints());

            const url = content.payload;
            const hash = url.split('/').pop().split('.')[0];
            // TODO: delete image only if not referenced by other ads 
            //  might be complex, since different ads in different consoles might
            //  be using an image with the same hash.
            
            // try {
            //     await ads.deleteImage(hash).catch(err => {
            //         console.error("Error deleting image:", err);
            //     });
            // } catch (e) {
            //     console.error("Error deleting image:", e);

            // }
            ads.close();

        }
        await deleteAd(event.id);

      
        window.location.href = "/console/";

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
    let isEdit = urlParams.get('edit') !== null;
    let isSee = urlParams.get('see') !== null;

    const cloneBtn = view.querySelector('#clone');
    const saveBtn = view.querySelector('#submit');
    const cancelBtn = view.querySelector('#cancel');
    const deleteBtn = view.querySelector('#delete');

    if (cloneBtn) {
        cloneBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            
            const url = new URL(window.location.href);
            const urlParams = new URLSearchParams(window.location.search);
            url.searchParams.set('edit', urlParams.get('see') || urlParams.get('edit') || '');
            url.searchParams.delete('see');
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

    if (isEdit||!isSee){
        // backBtn.style.display = 'inline-block';
        deleteBtn.style.display = 'none';
        cancelBtn.style.display = 'none';

    } else{
        // backBtn.style.display = 'none';

        if (isLive) {
            cancelBtn.style.display = 'inline-block';
            deleteBtn.style.display = 'none';
        } else {
            cancelBtn.style.display = 'none';
            deleteBtn.style.display = 'inline-block';
        }
    }

    const ads = NostrAds.newAdvertiserClient(await getRelays(), "nip07", await getBlossomEndpoints());


    saveBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const data = await getData(view, ads);
        console.log("Submitting data:", data);
        try {
            const event = await ads.publish(data);
            await saveAd(event);
            console.log("Ad published:", event);

            await ads.close();
            window.location.href = "/console/";

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

    const delegateMetadataEl = view.querySelector('#delegateMetadata');
    const delegateInputEL = adForm.querySelector('input[name="delegate"]');




    let delegatePubkey = null;

    const update = async () => {
        const data = await getData(view, null);
        Renderer.render(
            previewEl,
            data,
            () => { },
            () => { }
        );
        const size = data.size.split('x').map(s => parseInt(s, 10));
        previewEl.style.width = `${size[0]}px`;
        previewEl.style.height = `${size[1]}px`;

        if (delegateInputEL.value!== delegatePubkey) {
            delegateMetadataEl.innerHTML = '';
            try{
                if (delegateInputEL.value){
                    delegatePubkey = delegateInputEL.value;
                    const meta = await ads.getNip01Meta(delegateInputEL.value);
                    if(!meta) throw new Error("No metadata found for delegate pubkey: " + delegateInputEL.value);
                    const delegateInfoEl = document.createElement('div');
                    delegateInfoEl.className = 'delegate-info';
                    const nameEl = document.createElement('h3');
                    nameEl.textContent = meta.name || 'Delegate';
                    delegateInfoEl.appendChild(nameEl);
                    const descEl = document.createElement('p');
                    descEl.textContent = meta.about || 'No description available.';
                    delegateInfoEl.appendChild(descEl);

                    const websiteEl = document.createElement('a');
                    websiteEl.href = meta.website || '#';
                    websiteEl.textContent = meta.website || 'No website available';
                    websiteEl.target = '_blank';
                    delegateInfoEl.appendChild(websiteEl);

                    delegateMetadataEl.appendChild(delegateInfoEl);

                    console.log(meta);
                    const feeStr = meta["nostrads:fees"];
                    if(feeStr){
                        console.log("Delegate fees:", feeStr);
                        const [min,pc, max] = meta["nostrads:fees"]?.split(':').map(s => parseFloat(s)) || [0, 0, 0];
                        const feeEl = document.createElement('div');
                        feeEl.className = 'fee-container';
                        const pcFeeEl = document.createElement('div');
                        pcFeeEl.className = 'fee';
                        pcFeeEl.textContent = `Fee: ${pc}%`;
                        feeEl.appendChild(pcFeeEl);

                        const feeDetails = document.createElement('span');
                        feeDetails.className = 'feedetails';
                        feeEl.appendChild(feeDetails);
                        feeDetails.textContent = `Min ${Math.ceil(min/1000)} sats / Max ${Math.ceil(max/1000)} sats`;
                      

                        delegateInfoEl.appendChild(feeEl);
                        

                    }
                   
                }
            } catch (e) {
                console.error("Error fetching delegate metadata:", e);
                delegateMetadataEl.innerHTML = `<span class="error">Error fetching metadata: ${e.message}</span>`;
            }
        }
    }

    for (const inputEl of inputEls) {
        inputEl.addEventListener('input', update);
        inputEl.addEventListener('change', update);
        inputEl.addEventListener('keyup', update);
        if (!editable) {
            inputEl.setAttribute('readonly', '1');
            inputEl.classList.add('disabled');

        } else {
            inputEl.removeAttribute('readonly');
            inputEl.classList.remove('disabled');

        }
    }


    const nwcInputEl = adForm.querySelector('input[name="nwc"]');
    if (nwcInputEl) {
        // on lost focus open a prompt that asks the user to confirm the nwc is  budgetted
        nwcInputEl.addEventListener('blur', async (e) => {
            if (prompt( `Please ensure your NWC URL is budgeted to match the intended total campaign budget,  including a buffer for fees. Otherwise, you may incur unexpected costs.
              
Type "yes" to confirm that your NWC URL is properly budgeted.`
              ) !== 'yes') {
                nwcInputEl.value = '';
            }
        });
    }

 
    update();
}


async function main() {
    try {      
        loadHeader(document.body);
        showLogin(document.body, {
            relays: await getRelays(),
            blossomEndpoints: await getBlossomEndpoints()
        });
        await showNewForm(document.body)
        const backBtns = document.querySelectorAll('#back');
        for( const backBtn of backBtns) {
            backBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const url = new URL("..", window.location.href);
                url.searchParams.delete('see');
                url.searchParams.delete('edit');
                window.location.href = url.toString();
            });
        }
        // if (backBtn) {
        //     backBtn.addEventListener('click', (e) => {
        //         e.preventDefault();
        //         e.stopPropagation();
        //         const url = new URL("..", window.location.href);
        //         url.searchParams.delete('see');
        //         url.searchParams.delete('edit');
        //         window.location.href = url.toString();
        //     });
        // }
    } catch (e) {
        console.error("Error initializing NostrAds:", e);
        document.body.classList.add('locked');
        showError(document.body, e);
    }

}

window.addEventListener('load', () => {
    main();
});