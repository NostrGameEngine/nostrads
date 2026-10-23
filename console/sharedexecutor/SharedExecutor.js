import DedicatedWorkerBackend from './dedicated-worker-backend.js';

const MAX_MESSAGE_SIZE = 1024 * 1024;
const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9]{0,63}$/;
const INVOCATION_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

class SharedExecutor {
    constructor(callback) {
        this.backend = new DedicatedWorkerBackend(callback);
    }

    async close() {
        await this.backend.ready();
        this.backend.close();
    }

    async invoke(method, args) {
        // console.log(`Invoking method: ${method} with args:`, args);
        await this.backend.ready();
        // console.log(`Invoking method: ${method} with args:`, args);
        return this.backend.invoke(method, args);
    }

    async triggerCallback(callbackName, ...args) {
        await this.backend.ready();
        this.backend.triggerCallback(callbackName, ...args);
    }

    async registerCallback(callbackName, callbackFunction) {
        this.backend.registerCallback(callbackName, callbackFunction);
    }

    async unregisterCallback(callbackName) {
        this.backend.unregisterCallback(callbackName);
    }

    async registerMethod(methodName, methodFunction) {
        this.backend.registerMethod(methodName, methodFunction);
    } 

    async bindToClient(){
        // console.log("Binding to client...");
        this.backend.addMainThreadMessageListener((event, replyPort) => {
            // console.log('Message received from main thread:', event.data);
            if (typeof event.data !== 'string' || event.data.length > MAX_MESSAGE_SIZE) return;
            let data;
            try {
                data = JSON.parse(event.data);
            } catch (_) {
                return;
            }
            if (!data || Object.getPrototypeOf(data) !== Object.prototype) return;
            if (data.type === 'invoke') {
                const { method, args, invkId } = data;
                if (!NAME_PATTERN.test(method) || !Array.isArray(args) || !INVOCATION_ID_PATTERN.test(invkId)) return;
                this.invoke(method, args)
                    .then(result => {
                        this.backend.postMessageToMainThread(JSON.stringify({
                            type: 'result',
                            method,
                            result,
                            invkId
                        }), replyPort);
                    })
                    .catch(error => {
                        const rs = JSON.stringify({
                            type: 'result',
                            method,
                            error: String(error),
                            invkId
                        });
                        console.error(`Send rejection response ${rs}`);
                        this.backend.postMessageToMainThread(rs, replyPort);
                    });
            } else if(data.type === 'registerCallback' && NAME_PATTERN.test(data.name)) {
                this.registerCallback(data.name, (...args) => {
                    // console.log(`Callback ${data.name} triggered with args`, args);
                    this.backend.postMessageToMainThread(JSON.stringify({
                        type: 'callback',
                        name: data.name,
                        args
                    }), replyPort);
                });
            } else if(data.type === 'unregisterCallback' && NAME_PATTERN.test(data.name)) {
                this.unregisterCallback(data.name);
            }
        });
        
    }
}

export default SharedExecutor;
