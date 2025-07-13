

export function getRelays(){
    try{
        const v= JSON.parse(localStorage.getItem('nostrads-relays'));
        if(!v||!Array.isArray(v) || v.length === 0){
            throw new Error("No relays found in localStorage");
        }
        return v;
    } catch(e){
        console.error("Error parsing relays from localStorage:", e);
        return ["wss://nostr.rblb.it"];
    }
}



export default function showSettingsDialog(view, options){
    let dialog = view.querySelector('#settings-dialog');
    if (!dialog) {
        dialog = document.createElement('dialog');
        dialog.id = 'settings-dialog';
        dialog.innerHTML = `
            <form method="dialog">
                <h2>Relay Set</h2>
                <p>Enter a list of relays, one per line:</p>
                <textarea id="relay-set-textarea" rows="10" cols="50"></textarea>
                <div class="dialog-buttons">
                    <button type="submit">Save</button>
                    <button type="reset">Cancel</button>
                </div>
            </form>
        `;
        const submitButton = dialog.querySelector('button[type="submit"]');
        submitButton.addEventListener('click', (e) => {
            e.preventDefault();
            const textarea = dialog.querySelector('#relay-set-textarea');
            const relays = textarea.value.split('\n').map(line => line.trim()).filter(line => line);
            console.log("Saving relays:", relays);
            localStorage.setItem('nostrads-relays', JSON.stringify(relays));
            dialog.close();
            window.location.reload();
        });
        const resetButton = dialog.querySelector('button[type="reset"]');
        resetButton.addEventListener('click', (e) => {
            e.preventDefault();
            dialog.close();
            
        });
        dialog.addEventListener('close', () => {
           
        });
        view.appendChild(dialog);
        
    }
    dialog.querySelector('#relay-set-textarea').value = getRelays().join('\n');
    dialog.showModal();    
}

