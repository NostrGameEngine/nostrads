import { checkPostMessageOrigin } from './strict-origin.js';
class SharedExecutorClient {
    constructor(workerUrl, options) {
        this.callbacks = Object.create(null);
        const scopedWorkerUrl = new URL(workerUrl, window.location.href);
        const workerOptions = {...options};
        delete workerOptions.forceCompat;
        delete workerOptions.sessionKey;
        const worker = new Worker(scopedWorkerUrl, workerOptions);
        worker.addEventListener('message', this.handleMessage.bind(this));
        this.worker = worker;
        this.compatMode = true;
        this.instanceId = crypto.randomUUID?.() ?? Math.random().toString(36).slice(2);
    }

    isNativeSupported() {
        return false;
    }

    isNative() {
        return !this.compatMode;
    }

    registerCallback(callbackName, callbackFunction) {
        this.callbacks[callbackName] = callbackFunction;
        this.worker.postMessage(JSON.stringify({
            type: 'registerCallback',
            name: callbackName
        }));
    }

    unregisterCallback(callbackName) {
        this.worker.postMessage(JSON.stringify({
            type: 'unregisterCallback',
            name: callbackName
        }));
    }

    invoke(methodName, ...args) {
        if (!/^[A-Za-z][A-Za-z0-9]{0,63}$/.test(methodName)) {
            return Promise.reject(new Error('Invalid worker method name'));
        }
        return new Promise((resolve, reject) => {
            const invkId = `${this.instanceId.replace(/[^A-Za-z0-9_-]/g, '')}-${Math.random().toString(36).substring(2, 15)}-${Date.now()}`;
            
            const l = (event) => {
                let data;
                try {
                    data = JSON.parse(event.data);
                } catch (_) {
                    return;
                }
                if (data.type === 'result' && data.invkId === invkId) {
                    if (data.error) {
                        reject(new Error(data.error));
                    } else {
                        resolve(data.result);
                    }
                    try {
                        this.worker.removeEventListener('message', l);
                    } catch (e) {
                        console.warn('Could not remove event listener:', e);
                    }
                }
            };
            this.worker.addEventListener('message', l);
            const message = JSON.stringify({
                type: 'invoke',
                method: methodName,
                args: args,
                invkId: invkId
            });
            if (message.length > 1024 * 1024) {
                reject(new Error('Worker request exceeds maximum size'));
                return;
            }
            this.worker.postMessage(message);
        });
    }

    close() {
        this.worker.terminate();
    }   
    
    handleMessage(event) {
        checkPostMessageOrigin(event);
        // console.log('Message received from worker:', event.data);
        if(typeof event.data === 'string') {
            let data;
            try {
                data = JSON.parse(event.data);
            } catch (_) {
                return;
            }
            if (data.type === 'callback') {
                this.callbacks[data.name]?.(...data.args);
            }
        }
    }
    
    
}

export default SharedExecutorClient;
