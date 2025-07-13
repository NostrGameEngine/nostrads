class SharedExecutorClient {
    constructor(workerUrl, options) {
        this.callbacks = {};
        if (typeof SharedWorker !== 'undefined' && !options?.forceCompat) {
            console.log("Using native SharedWorker");
            const worker = new SharedWorker(workerUrl, options);
            worker.port.start();
            worker.port.addEventListener('message', this.handleMessage.bind(this));
            this.worker = worker.port;
            this.sharedWorker = worker;
            this.compatMode = false;
        }else{
            console.log("Using SharedWorker compatibility mode");
            const worker = new Worker(workerUrl,  options);
            worker.addEventListener('message', this.handleMessage.bind(this));
            this.worker = worker;
            this.compatMode = true;
        }

    }

    isNativeSupported() {
        return typeof SharedWorker !== 'undefined';
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
        return new Promise((resolve, reject) => {
            const invkId = `${this.worker.id}-${Math.random().toString(36).substring(2, 15)}-${Date.now()}`;
            this.worker.postMessage(JSON.stringify({
                type: 'invoke',
                method: methodName,
                args: args,
                invkId: invkId  
            }));
            this.worker.addEventListener('message', (event) => {
                const data = JSON.parse(event.data);
                if (data.type === 'result' && data.invkId === invkId) {
                    if (data.error) {
                        reject(new Error(data.error));
                    } else {
                        resolve(data.result);
                    }
                }
            },{ once: true });
        });
    }

    triggerCallback(callbackName, ...args) {
        this.worker.postMessage(JSON.stringify({
            type: 'callback',
            name: callbackName,
            args: args
        }));
    }   

    close() {
        if (this.worker instanceof MessagePort) {
            this.worker.close();
        } else {
            this.worker.terminate();
        }
    }   
    
    handleMessage(event) {
        // console.log('Message received from worker:', event.data);
        if(typeof event.data === 'string') {
            const data = JSON.parse(event.data);
            if (data.type === 'callback') {
                this.callbacks[data.name]?.(...data.args);
            }
        }
    }
    
    
}

export default SharedExecutorClient;