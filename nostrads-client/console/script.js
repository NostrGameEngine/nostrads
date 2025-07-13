

import Renderer from "../ad-render.js";
import { newAdvertiserClient } from "../nadv-client.js";
import NostrAds from '../nostr-ads.js';


async function loadProps(adForm) {

    // if has see query parameter, load the ad data
    const urlParams = new URLSearchParams(window.location.search);
    const seeArgs = urlParams.get('see');
    const editArgs = urlParams.get('edit');
    if (!seeArgs && !editArgs) {
        return true;
    }
    let editable = !seeArgs;
    
    const args = JSON.parse(decodeURIComponent(seeArgs || editArgs));
    console.log("Loading ad data:", args);
    const content = JSON.parse(args.content || '{}');
    const tags = args.tags || [];
    console.log("Context:", content);

    adForm.querySelectorAll('input, select, textarea').forEach(el => {
        const name = el.getAttribute('name');
        const aliases = el.getAttribute('alias')?.split(',').map(s => s.trim()) || [];
        const set = (k,v)=>{
            try{
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
                    el.value = v;
                }
            }catch (e) {
                console.error(`Error setting value for ${name} (${aliases.join(', ')}):`, e);
            }
        }
        for(const [k,v] of Object.entries(content)){
            if (k === name || aliases.includes(k)) {
                console.log(`Setting ${name} to ${v} from context`);
                set(name, v);
                break;
            }
        }
        for(const tag of tags) {
            if (tag[0] === name || aliases.includes(tag[0])) {
                console.log(`Setting ${name} to ${tag[1]} from tags`);
                set(name, tag[1]);
                break;
            }
            if (  aliases.includes(tag[0]+".1")) {
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
    });

    console.log("Tags:", tags);
    const cloneBtn = document.querySelector('#clone');
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
    
    const saveBtn = document.querySelector('#submit');
    const cancelBtn = document.querySelector('#cancel');
    if (saveBtn) {
        if(!editable){
            saveBtn.style.display = 'none';
            cancelBtn.style.display = 'inline-block';
        }else{
            saveBtn.style.display = 'inline-block';
            cancelBtn.style.display = 'none';
        }
    }
    
    return editable;

}

async function loadNewForm(client){

    const adForm = document.getElementById('adForm');
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    const previewEl = adForm.querySelector('#preview');

    // Load categories
    const cat = adForm.querySelector('select[name="category"]');
    await  fetch('https://ngengine.org/docs/nip-drafts/nostr-content-taxonomy.csv').then(r => r.text()).then(t => {
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




    let editable = await loadProps(adForm);;

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

    // Set the pre-selected values
    if (preSelectedCategories.length > 0) {
        categorySelect.setChoiceByValue(preSelectedCategories);
    }
    if (preSelectedLanguages.length > 0) {
        languageSelect.setChoiceByValue(preSelectedLanguages);
    }

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




    const getData = () => {
        const data = {};
        for (const inputEl of inputEls) {
        
            if (inputEl.style.display === 'none') continue;

            // if input is an image input, load the image into a blob url
            if (inputEl.type === 'file' && inputEl.files.length > 0) {
                const file = inputEl.files[0];
                if (file.type.startsWith('image/')) {
                    data[inputEl.name] = URL.createObjectURL(file);
                } else {
                    console.warn(`File ${file.name} is not an image, skipping.`);
                }
                continue;
            }else  if (inputEl.type === 'checkbox') {
                data[inputEl.name] = inputEl.checked;
            } else if (inputEl.type === 'radio') {
                if (inputEl.checked) {
                    data[inputEl.name] = inputEl.value;
                }
            } else if (inputEl.tagName.toLowerCase() === 'select' && inputEl.multiple) {
                data[inputEl.name] = Array.from(inputEl.selectedOptions).map(option => option.value);
            }  else {
                if (inputEl.type === 'date') {
                    const date = new Date(inputEl.value);
                    if(!isNaN(date.getTime())){
                        data[inputEl.name] = date.getTime();
                    }
                }else{
                    data[inputEl.name] = inputEl.value;
                }
            }  

            if (inputEl.getAttribute('cnvTo')==='array'){
                data[inputEl.name] = data[inputEl.name].split('\n').map(s => s.trim()).filter(s => s.length > 0);
            }

            if (!data[inputEl.name]||
                
                (Array.isArray(data[inputEl.name]) && data[inputEl.name].length === 0) 

            ) {
                data[inputEl.name] = null;
            }
        }
        if (data.delegate){
            data.delegatePayload = {
                budget: data.budget,
                nwc: data.nwc
            }
        }
        

        console.log("Collected data:", data);
        return data;
    };

    const reloadPreview = ()=>{
       const data = getData();
       Renderer.render(
            previewEl,
            data,
            ()=>{},
            ()=>{}
       );
        const size = data.size.split('x').map(s => parseInt(s, 10));
        previewEl.style.width = `${size[0]}px`;
        previewEl.style.height = `${size[1]}px`;
    }
    
    for(const inputEl of inputEls){
        inputEl.addEventListener('input', reloadPreview);
        inputEl.addEventListener('change', reloadPreview);
        inputEl.addEventListener('keyup', reloadPreview);
        if (!editable){
            inputEl.setAttribute('readonly', '1');
            inputEl.classList.add('disabled');
            
        } else{ 
            inputEl .removeAttribute('readonly');
            inputEl.classList.remove('disabled');
            
        }
    }

    adForm.querySelector('#submit').addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const data = getData();
        console.log("Submitting data:", data);
        try {
            await client.publish(data);
        }catch (err) {
            console.error("Error publishing bid:", err);
            alert("Error publishing bid: " + err.message);
            return;
        }
    });

    reloadPreview();
}

async function common(ads){
    const nip01 = await ads.getNip01Meta(await ads.getPublicKey());
    console.log("NIP-01 metadata:", nip01);
    const userEl = document.querySelector('#login');
    if(userEl!=null){
        userEl.innerHTML = `
            <img src="${nip01.picture}" />
            <span>${nip01.name || nip01.display_name || nip01.nip05 || ads.getPublicKey()}</span>
        `;
    }

 
        
}


async function loadAdList(client) {
    const adListEl = document.querySelector('#adlist');
    if (!adListEl) {
        console.error("No ad list element found");
        return;
    }

    const ads = await client.list();
    console.log("Loading ad list:", ads);
    //                         <div id="preview" class="nostr-adspace"></div>

    for(const ad of ads) {
        const tags = ad.tags || [];
        const size = tags.find(tag => tag[0] === 's')[1].split("x").map(s => parseInt(s, 10));
        const ratio = tags.find(tag => tag[0] === 'S')[1].split(":").map(s => parseFloat(s)). reduce((a, b) => a / b);

        const maxSize = window.innerWidth * 0.8;
        const width = Math.min(size[0], maxSize);
        const height = Math.min(size[1], maxSize * ratio);

        const adEl = document.createElement('div');
        adEl.className = 'nostr-adspace';
        adEl.style.width = `${width}px`;
        adEl.style.height = `${height}px`;
        console.log("Creating ad element with size:", width, height, "for ad:", ad);
        adListEl.appendChild(adEl);
        adEl.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            console.log("Ad clicked:", ad);
            const uriComponent = encodeURIComponent(JSON.stringify(ad));
            const url = new URL("new.html", window.location.href);
            url.searchParams.set('see', uriComponent);
            window.location.href = url.toString();
        });
        Renderer.renderEvent(
            adEl,
            ad,
            () => { },
            () => { },
            {
                noLink: true
            }
        );
    }

}

async function main() {
    const errorEl = document.querySelector('#error');

    try{
        let devMode = true;
        if (errorEl){
            errorEl.style.display = 'none';
        }
        
             const ads = NostrAds.newAdvertiserClient([
                "wss://nostr.rblb.it"
            ], "nip07");

            await common(ads);
            document.body.classList.remove('locked');
      
        // console.log();

        
        // console.log("Current URL path:", urlPath);
        const urlPath = window.location.pathname;
        if (urlPath.includes('/new')) {
            await loadNewForm(ads)
        } else {
            await loadAdList(ads);

        }
    }catch (e) {
        console.error("Error initializing NostrAds:", e);
        document.body.classList.add('locked');


        if(errorEl){
            errorEl.textContent = "Error initializing NostrAds: " + e.message;
            errorEl.style.display = 'block';
        }

        return;
    }

}

window.addEventListener('load', () => {
    main();
});