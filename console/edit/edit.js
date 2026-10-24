import Renderer, { safeLinkUrl, safeImageUrl } from '../ad-render.js';
import { getSession, createClient } from '../components/session.js';
import { getConfig, getRelays, getBlossomEndpoints, getImageOrigins } from '../components/options.js';

import showError from '../components/error.js';
import { saveAd, deleteAd } from '../components/adstore.js';
import {setupDelegates} from '../components/delegates.js';
import {setupWalletBudget} from '../components/wallet-budget.js';

function getReplacementContext(urlParams) {
    const payload = urlParams.get('see') || urlParams.get('edit');
    if (!payload) {
        return null;
    }
    return {
        event: JSON.parse(decodeURIComponent(payload)),
        isLive: urlParams.get('isLive') === 'true',
    };
}

function getInputs(view) {
    const adForm = view.querySelector('#adForm');
    if (!adForm) {
        console.error('No ad form found in view');
        return [];
    }
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    return inputEls;
}

async function getData(view, ads) {
    const inputEls = getInputs(view);
    const data = {};
    for (const inputEl of inputEls) {
        if (!inputEl.name || inputEl.disabled || inputEl.style.display === 'none') continue;
        if (inputEl.type === 'file' && inputEl.files.length > 0) {
            const file = inputEl.files[0];
            if (file.type.startsWith('image/')) {
                if (!ads) {
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
                        mimeType: file.type,
                    });

                    data[inputEl.name] = descriptor.url;
                }
            } else {
                console.warn(`File ${file.name} is not an image, skipping.`);
            }
            continue;
        } else if (inputEl.type === 'file') {
            data[inputEl.name] = inputEl.dataset.existingImageUrl || null;
            continue;
        } else if (inputEl.type === 'checkbox') {
            data[inputEl.name] = inputEl.checked;
        } else if (inputEl.type === 'radio') {
            if (inputEl.checked) {
                data[inputEl.name] = inputEl.value;
            }
        } else if (inputEl.tagName.toLowerCase() === 'select' && inputEl.multiple) {
            data[inputEl.name] = Array.from(inputEl.selectedOptions).map((option) => option.value);
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
            data[inputEl.name] = data[inputEl.name]
                .split('\n')
                .map((s) => s.trim())
                .filter((s) => s.length > 0);
        } else if (cnvTo === 'sats') {
            const value = parseFloat(data[inputEl.name]) * 1000; // Convert to millisatoshis
            data[inputEl.name] = value;
        }

        if (!data[inputEl.name] || (Array.isArray(data[inputEl.name]) && data[inputEl.name].length === 0)) {
            data[inputEl.name] = null;
        }
    }

    data.maxPayouts = 3; // per offerer
    data.payoutResetInterval = 3600; // in seconds

    return data;
}

async function loadProps(view, urlParams, lifecycle) {
    const seeArgs = urlParams.get('see');
    const editArgs = urlParams.get('edit');

    let editable = !seeArgs;

    const event = JSON.parse(decodeURIComponent(seeArgs || editArgs || '{}'));

    const content = JSON.parse(event.content || '{}');
    const tags = event.tags || [];

    const mimeType = tags.find((tag) => tag[0] === 'm')?.[1];

    const inputEls = getInputs(view);
    for (const el of inputEls) {
        const name = el.getAttribute('name');
        const aliases =
            el
                .getAttribute('alias')
                ?.split(',')
                .map((s) => s.trim()) || [];

        const set = async (k, v) => {
            try {
                if (el.type === 'file') {
                    if (!mimeType?.startsWith('image/')) return;
                    const safeUrl = safeImageUrl(v, await getImageOrigins());
                    if (!safeUrl)
                        throw new Error('This image host is not in your selected Blossom servers or configured CDNs.');
                    el.dataset.existingImageUrl = safeUrl;
                    el.required = false;
                } else if (el.type === 'checkbox') {
                    el.checked = v;
                } else if (el.type === 'radio') {
                    if (el.value === v) {
                        el.checked = true;
                    }
                } else if (el.type === 'date') {
                    const date = new Date(v);

                    if (!isNaN(v)) {
                        el.value = new Date(v * 1000).toISOString().split('T')[0];
                    }
                } else if (el.tagName.toLowerCase() === 'select' && el.multiple) {
                    if (el.choices) {
                        // Use Choices.js API to set values

                        const choices = [];
                        if (Array.isArray(v)) {
                            choices.push(...v);
                        } else if (v) {
                            choices.push(v);
                        }
                        el.choices.clearStore();
                        el.choices.setChoiceByValue(choices);
                    } else {
                        // Fallback to native DOM methods

                        const options = Array.from(el.options);
                        options.forEach((option) => {
                            if (Array.isArray(v)) {
                                option.selected = option.selected || v.includes(option.value);
                            } else {
                                option.selected = option.selected || v == option.value;
                            }
                        });
                    }
                } else {
                    const cnvTo = el.getAttribute('cnvTo');
                    if (cnvTo === 'sats') {
                        v = parseFloat(v) / 1000; // Convert from millisatoshis to satoshis
                    }

                    if (cnvTo === 'array') {
                        el.value = [el.value, v].filter(Boolean).join('\n');
                    } else {
                        el.value = v;
                    }
                }
            } catch (e) {
                console.error(`Error setting value for ${name} (${aliases.join(', ')}):`, e);
            }
        };

        for (const [k, v] of Object.entries(content)) {
            if (k === name || aliases.includes(k)) {
                await set(name, v);
                break;
            }
        }

        for (const tag of tags) {
            if (tag[0] === name || aliases.includes(tag[0])) {
                await set(name, tag[1]);
            }
            if (aliases.includes(tag[0] + '.1')) {
                await set(name, tag[1]);
            }
            if (aliases.includes(tag[0] + '.2')) {
                await set(name, tag[2]);
            }
        }

        const config = await getConfig();
        if (!lifecycle.active) return editable;
        const isTestMode = !!config.testmode;

        const delegateInput = view.querySelector('input[name="delegate"]');
        const defaultDelegate = isTestMode ? config.testmode_config?.default_delegate || config.default_delegate : config.default_delegate;
        if (delegateInput.value === '' && defaultDelegate) {
            delegateInput.value = defaultDelegate;
        }

        if (isTestMode) {
            const nwcInput = view.querySelector('input[name="nwc"]');
            const nwcInfo = view.querySelector('#nwcInfo');
            const publicTestWallet = config.testmode_config?.nwc;
            if (!nwcInput.value && publicTestWallet) nwcInput.value = publicTestWallet;
            if (nwcInfo) {
                nwcInfo.textContent = publicTestWallet && nwcInput.value === publicTestWallet
                    ? 'Demo console · A test wallet connection is prefilled.'
                    : 'Demo console · Use a test wallet for this campaign.';
                nwcInfo.hidden = false;
            }
            nwcInput.style.display = 'block';
        } else {
            view.querySelector('#nwcInfo').hidden = true;
            view.querySelector('#nwcInfo').textContent = '';
        }

        const dailyBudgetInput = view.querySelector('input[name="dailyBudget"]');
        if (dailyBudgetInput && !dailyBudgetInput.value) {
            let dailyBudget = 1000;
            if (content.bid / 1000 > dailyBudget) {
                dailyBudget = content.bid / 1000;
            }
            dailyBudgetInput.value = String(dailyBudget);
        }
    }

    const cancelBtn = view.querySelector('#cancel');

    cancelBtn.addEventListener('click', async (e) => {
        try {
            e.preventDefault();
            e.stopPropagation();
            if (!confirm('Are you sure you to stop this ad and cancel the bid? This action cannot be undone.')) {
                return;
            }
            const ads = createClient(await getRelays(), await getBlossomEndpoints());

            lifecycle.cleanup.push(() => ads.close());
            await ads.cancel(event.id);
            if (!lifecycle.active) return;
            await saveAd(event);
            ads.close();
            location.hash = 'campaigns';
        } catch (err) {
            console.error('Error cancelling bid:', err);
            showError(view, err);
            return;
        }
    });

    const deleteBtn = view.querySelector('#delete');
    deleteBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!confirm("Remove this device's saved copy? This does not delete the campaign from relays.")) {
            return;
        }

        await deleteAd(event.id);

        location.hash = 'campaigns';
    });
    return editable;
}

async function replaceAd(ads, originalEvent, replacementEvent, isLive) {
    await saveAd(replacementEvent);
    if (originalEvent?.id) {
        if (isLive) {
            await ads.cancel(originalEvent.id);
        }
        await deleteAd(originalEvent.id);
    }
}

async function showNewForm(view, urlParams, lifecycle) {
    const adForm = view.querySelector('#adForm');
    adForm.addEventListener('submit', (event) => {
        event.preventDefault();
        view.querySelector('#submit').click();
    });
    const inputEls = adForm.querySelectorAll('input, select, textarea');
    const previewEl = view.querySelector('#preview');

    // Taxonomy identifiers must be preserved when restoring a signed campaign.
    const cat = adForm.querySelector('select[name="category"]');
    await fetch(new URL('../data/categories.json', import.meta.url))
        .then((r) => r.json())
        .then((entries) => {
            for (const { id, label } of entries) cat.add(new Option(label, id));
        });

    // Load languages
    const lang = adForm.querySelector('select[name="languages"]');

    await fetch(new URL('../data/languages.json', import.meta.url))
        .then((r) => r.json())
        .then((langs) => {
            for (const [code, name] of Object.entries(langs)) {
                lang.add(new Option(`${code.toUpperCase()} - ${name}`, code));
            }
        });

    let editable = await loadProps(view, urlParams, lifecycle);
    if (!lifecycle.active) return;
    let isLive = urlParams.get('isLive') === 'true';
    let isEdit = urlParams.get('edit') !== null;
    let isSee = urlParams.get('see') !== null;
    const replacementContext = getReplacementContext(urlParams);
    const isReplacing = editable && replacementContext !== null;

    const cloneBtn = view.querySelector('#clone');
    const saveBtn = view.querySelector('#submit');
    const cancelBtn = view.querySelector('#cancel');
    const deleteBtn = view.querySelector('#delete');

    if (cloneBtn) {
        cloneBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();

            const next = new URLSearchParams(urlParams);
            next.set('edit', next.get('see') || next.get('edit') || '');
            next.delete('see');
            location.hash = `edit?${next}`;
        });
        cloneBtn.style.display = !editable ? 'inline-block' : 'none';
    }

    if (saveBtn && isReplacing) {
        saveBtn.textContent = isLive ? 'Publish replacement' : 'Republish ad';
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

    if (isEdit || !isSee) {
        deleteBtn.style.display = 'none';
        cancelBtn.style.display = 'none';
    } else {
        if (isLive) {
            cancelBtn.style.display = 'inline-block';
            deleteBtn.style.display = 'none';
        } else {
            cancelBtn.style.display = 'none';
            deleteBtn.style.display = 'inline-block';
        }
    }

    let ads;
    const getClient = async () => {
        if (!lifecycle.active) throw new Error('The campaign editor has closed.');
        if (!ads) {
            const relays = await getRelays();
            const endpoints = await getBlossomEndpoints();
            if (!lifecycle.active) throw new Error('The campaign editor has closed.');
            ads = createClient(relays, endpoints);
            lifecycle.cleanup.push(() => ads.close());
        }
        return ads;
    };

    await setupDelegates(view, lifecycle);
    const walletBudget = await setupWalletBudget(view, lifecycle);
    if (!lifecycle.active) return;

    saveBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        try {
            if (!adForm.reportValidity()) return;
            if (!getSession()) {
                window.dispatchEvent(new Event('nostrads:login-request'));
                throw new Error('Log in to sign and publish this campaign.');
            }
            saveBtn.disabled = true;
            await walletBudget.beforePublish();
            if (!lifecycle.active) return;
            const client = await getClient();
            const data = await getData(view, client);
            if (!lifecycle.active) return;
            const event = await client.publish(data);
            if (!lifecycle.active) return;
            if (isReplacing) {
                await replaceAd(client, replacementContext.event, event, replacementContext.isLive);
            } else {
                await saveAd(event);
            }

            client.close();
            location.hash = 'campaigns';
        } catch (err) {
            console.error('Error publishing bid:', err);
            showError(view, err);
            return;
        } finally {
            saveBtn.disabled = false;
        }
    });

    if (!lifecycle.active) return;

    // Initialize Choices after restoring the native selected options.

    const preSelectedCategories = Array.from(cat.selectedOptions).map((option) => option.value);
    const preSelectedLanguages = Array.from(lang.selectedOptions).map((option) => option.value);

    const categorySelect = new Choices(cat, {
        removeItemButton: true,
        searchEnabled: true,
        searchPlaceholderValue: 'Search categories...',
        placeholderValue: 'Select categories',
        classNames: {
            containerOuter: ['choices', 'choices--purple'],
        },
    });

    const languageSelect = new Choices(lang, {
        removeItemButton: true,
        searchEnabled: true,
        searchPlaceholderValue: 'Search languages...',
        placeholderValue: 'Select languages',
        classNames: {
            containerOuter: ['choices', 'choices--purple'],
        },
    });

    if (preSelectedCategories.length > 0) categorySelect.setChoiceByValue(preSelectedCategories);
    if (preSelectedLanguages.length > 0) languageSelect.setChoiceByValue(preSelectedLanguages);

    if (!editable) {
        languageSelect.disable();
        categorySelect.disable();
    }

    lifecycle.cleanup.push(() => {
        categorySelect.destroy();
        languageSelect.destroy();
    });

    // Keep inactive payload fields out of validation and publication.
    const actionTypeEl = adForm.querySelector('select[name="actionType"]');
    const actionDescription = adForm.querySelector('#actionDesc');

    actionTypeEl.addEventListener('change', (e) => {
        const titleAttribute = e.target.selectedOptions[0].getAttribute('title');
        actionDescription.textContent = titleAttribute || 'No description available.';
    });
    actionTypeEl.dispatchEvent(new Event('change'));

    const mimeTypeEl = adForm.querySelector('select[name="mimeType"]');
    const payloadTextEl = adForm.querySelector('#payloadText');
    const payloadImageEl = adForm.querySelector('#payloadImage');
    payloadImageEl.accept = 'image/png,image/jpeg,image/gif';
    payloadImageEl.addEventListener('change', () => {
        const type = payloadImageEl.files[0]?.type;
        const supported = ['image/png', 'image/jpeg', 'image/gif'];
        payloadImageEl.setCustomValidity(type && !supported.includes(type) ? 'Choose a PNG, JPEG or GIF image.' : '');
        if (supported.includes(type)) {
            mimeTypeEl.value = type;
            mimeTypeEl.dispatchEvent(new Event('change'));
        }
    });
    mimeTypeEl.addEventListener('change', (e) => {
        const isText = e.target.value.startsWith('text/');
        payloadTextEl.style.display = isText ? 'block' : 'none';
        payloadTextEl.disabled = !isText;
        payloadImageEl.style.display = isText ? 'none' : 'block';
        payloadImageEl.disabled = isText;
        payloadImageEl.required = !isText && !payloadImageEl.dataset.existingImageUrl;
        view.querySelector('#creative-label').htmlFor = isText ? 'payloadText' : 'payloadImage';
        view.querySelector('#existing-image-note').hidden =
            isText || !payloadImageEl.dataset.existingImageUrl || payloadImageEl.files.length > 0;
    });
    mimeTypeEl.dispatchEvent(new Event('change'));

    // Handle preview rendering

    let previewDisposers = [];
    let previewImageUrl = null;

    let previewVersion = 0;
    const resizeObserver = new ResizeObserver(() => {
        if (!lifecycle.active) return;
        const container = view.querySelector('#previewContainer');
        const width = parseFloat(previewEl.style.width);
        const height = parseFloat(previewEl.style.height);
        if (width > 0) {
            const scale = Math.min(2, container.clientWidth / width, (window.innerHeight - 180) / height);
            previewEl.style.transform = `scale(${scale})`;
            previewEl.style.left = `${Math.max(
                0,
                (container.clientWidth - parseFloat(previewEl.style.width) * scale) / 2
            )}px`;
            container.style.height = `${height * scale}px`;
        }
    });
    resizeObserver.observe(view.querySelector('#previewContainer'));
    lifecycle.cleanup.push(() => resizeObserver.disconnect());
    lifecycle.cleanup.push(() => {
        previewDisposers.forEach((dispose) => dispose());
        if (previewImageUrl) URL.revokeObjectURL(previewImageUrl);
    });
    const update = async () => {
        const version = ++previewVersion;
        const data = await getData(view, null);
        const imageOrigins = await getImageOrigins();
        if (!lifecycle.active || version !== previewVersion) {
            if (data.payload?.startsWith('blob:')) URL.revokeObjectURL(data.payload);
            return;
        }
        previewDisposers.forEach((dispose) => dispose());
        previewDisposers = [];
        if (previewImageUrl) URL.revokeObjectURL(previewImageUrl);
        previewImageUrl = data.payload?.startsWith('blob:') ? data.payload : null;
        const summary = view.querySelector('#publish-summary');
        if (summary)
            summary.textContent = `Bid ${(data.bid || 0) / 1000} sats per ${
                { link: 'click', view: 'view', attention: 'engagement' }[data.actionType] || 'action'
            } · Daily budget ${(data.dailyBudget || 0) / 1000} sats`;
        if (data.payload) {
            previewDisposers = Renderer.render(
                previewEl,
                { ...data, link: safeLinkUrl(data.link) || 'https://example.com' },
                () => {},
                () => {},
                { noLink: true, allowedImageOrigins: imageOrigins }
            );
        } else {
            previewEl.textContent = 'Add an image or write your ad text to see a preview.';
            previewEl.style.backgroundImage = '';
        }
        const size = data.size.split('x').map((s) => parseInt(s, 10));
        previewEl.style.width = `${size[0]}px`;
        previewEl.style.height = `${size[1]}px`;
        const container = view.querySelector('#previewContainer');
        const fit = () => {
            const scale = Math.min(2, container.clientWidth / size[0], (window.innerHeight - 180) / size[1]);
            previewEl.style.transform = `scale(${scale})`;
            previewEl.style.left = `${Math.max(
                0,
                (container.clientWidth - parseFloat(previewEl.style.width) * scale) / 2
            )}px`;
            container.style.height = `${size[1] * scale}px`;
        };
        fit();

    };

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

    update();
}

export function mountEditor(view, params = new URLSearchParams()) {
    const lifecycle = { active: true, cleanup: [] };
    lifecycle.cleanup.push(arrangeEditor(view));
    showNewForm(view, params, lifecycle)
        .then(() => {
            if (!lifecycle.active) {
                lifecycle.cleanup.splice(0).forEach((dispose) => dispose());
                return;
            }
            for (const button of view.querySelectorAll('[id="back"]')) {
                button.type = 'button';
                button.addEventListener('click', () => {
                    location.hash = 'campaigns';
                });
            }
        })
        .catch(() => {
            if (lifecycle.active)
                showError(view, new Error('Could not open this campaign. Refresh the page and try again.'));
        });
    return () => {
        lifecycle.active = false;
        lifecycle.cleanup.splice(0).forEach((dispose) => dispose());
        view.querySelectorAll('input[type="password"]').forEach((input) => {
            input.value = '';
        });
    };
}

function arrangeEditor(view) {
    const form = view.querySelector('#adForm');
    const grid = document.createElement('div');
    grid.className = 'editor-grid';
    const fields = document.createElement('div');
    fields.className = 'editor-fields';
    const navigation = document.createElement('div');
    navigation.className = 'editor-navigation';
    navigation.setAttribute('aria-label', 'Campaign sections');
    const previewDialog = document.createElement('dialog');
    previewDialog.className = 'creative-preview-dialog';
    previewDialog.setAttribute('aria-labelledby', 'creative-preview-title');
    previewDialog.innerHTML =
        '<div class="dialog-heading"><h2 id="creative-preview-title">Ad preview</h2><button type="button" class="text-button" aria-label="Close ad preview">✕</button></div>';
    previewDialog.append(view.querySelector('#previewContainer'));
    previewDialog.querySelector('button').addEventListener('click', () => previewDialog.close());
    view.append(previewDialog);
    for (const [index, name] of ['Ad details', 'Ad creative', 'Audience & placements', 'Budget & payments'].entries()) {
        const heading = [...form.querySelectorAll('h2')].find((item) => item.textContent === name);
        const panel = document.createElement('section');
        panel.className = 'editor-panel';
        panel.id = `editor-section-${index}`;
        const body = heading.nextElementSibling;
        const previewLabel = [...body.querySelectorAll('label')].find((item) => item.htmlFor === 'preview');
        previewLabel?.remove();
        panel.append(heading, body);
        fields.append(panel);
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'secondary';
        button.textContent = name;
        button.addEventListener('click', () => panel.scrollIntoView({ behavior: 'smooth', block: 'start' }));
        navigation.append(button);
    }
    for (const input of fields.querySelectorAll('input,textarea,select')) {
        if (!input.id && input.name) input.id = `field-${input.name}`;
        const body = input.parentElement;
        const label = [...body.querySelectorAll(':scope > label')].find(
            (item) => item.htmlFor === input.name || (input.name === 'expire_at' && item.htmlFor === 'expiration')
        );
        if (label) label.htmlFor = input.id;
    }
    const actions = document.createElement('div');
    actions.className = 'editor-actions';
    const summary = view.querySelector('#publish-summary');
    const buttons = document.createElement('div');
    buttons.className = 'editor-action-buttons';
    const previewButton = document.createElement('button');
    previewButton.type = 'button';
    previewButton.className = 'secondary';
    previewButton.id = 'open-preview';
    previewButton.textContent = 'Preview ad';
    previewButton.addEventListener('click', () => previewDialog.showModal());
    buttons.append(previewButton);
    for (const id of ['submit', 'clone', 'cancel', 'delete']) buttons.append(view.querySelector(`#${id}`));
    actions.append(summary, buttons);
    const error = view.querySelector('#error');
    grid.append(fields);
    form.replaceChildren(navigation, grid, error, actions);
    return () => {
        if (previewDialog.open) previewDialog.close();
        previewDialog.remove();
    };
}
