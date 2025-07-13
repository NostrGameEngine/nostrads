import SharedWorkerBackend from './sharedworker-backend.js';
import SharedWorkerCompatBackend from './sharedworker-compat-backend.js';
class SharedExecutor {
    constructor(callback) {
        if (typeof onconnect !== 'undefined') {
            this.backend = new SharedWorkerBackend(callback);
        }else {
            this.backend = new SharedWorkerCompatBackend(callback);
        }

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
        this.backend.addMainThreadMessageListener( (event)  =>{
            // console.log('Message received from main thread:', event.data);
            const data = JSON.parse(event.data);
            if (data.type === 'invoke') {
                const { method, args, invkId } = data;
                this.invoke(method, args)
                    .then(result => {
                        this.backend.postMessageToMainThread(JSON.stringify({
                            type: 'result',
                            method,
                            result,
                            invkId
                        }));
                    })
                    .catch(error => {
                        this.backend.postMessageToMainThread(JSON.stringify({
                            type: 'result',
                            method,
                            error: error.message,
                            invkId
                        }));
                    });
            } else if(data.type === 'registerCallback') {
                this.registerCallback(data.name, (...args) => {
                    // console.log(`Callback ${data.name} triggered with args`, args);
                    this.backend.postMessageToMainThread(JSON.stringify({
                        type: 'callback',
                        name: data.name,
                        args
                    }));
                });
            } else if(data.type === 'unregisterCallback') {
                this.unregisterCallback(data.name);
            } else if(data.type === 'triggerCallback') {
                this.triggerCallback(data.name, ...data.args);
            } else if(data.type === 'close') {
                this.close();
            }
        });
        
    }
}

export default SharedExecutor;