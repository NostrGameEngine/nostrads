class SharedWorkerBackend {
    constructor(callback) {
       
        // Counter for generating IDs
        this.counter = 0;

        // Initialize collections
        this.registeredMethods = {};
        this.invokations = {};

        this.callbacks = {};


        this.ports=[];
        this.listeners=[];
        // console.log("Use native shared workers");
        this.readyPromise = new Promise((resolve) => {
            onconnect = (e) => {
                const port = e.ports[0];
                // console.log("New port connected!:", port);
                    port.addEventListener('message', (args)=>{
                        // console.log("Message received on port:", args);
                        for(const listener of this.listeners) {
                            // console.log("Invoking listener with args:", args);
                            listener( args);
                        }
                    });
                    port.start();
                    this.ports.push(port);
                    port.addEventListener('close', () => {
                        // console.log("Port closed, removing from ports list");
                        this.ports = this.ports.filter(p => p !== port);
                    });
                                              
            };
            resolve();

            callback(true);
        });
    }

    postMessageToMainThread(message) {
        for(const port of this.ports) {
            port.postMessage(message);
        }
    };

    addMainThreadMessageListener( callback) {
        this.listeners.push(callback);
    };

    close(){
        for(const port of this.ports) {
            port.close();
        }
    }
    ready() {
        return this.readyPromise;
    }


    randomId() {
        return (this.counter++) + "-" + Math.random() + "-" + Date.now();
    }

    async invoke(method, args) {
        // console.log(`Invoking method: ${method} with args:`, args);
        return this.registeredMethods[method](...args);
    }

    triggerCallback(callbackName, ...args) {
        // console.log(`Triggering callback: ${callbackName} with args:`, args);
        this.callbacks[callbackName](...args);        
    }

    registerCallback(callbackName, callbackFunction) {
        this.callbacks[callbackName] = callbackFunction;
    }

    unregisterCallback(callbackName) {
        delete this.callbacks[callbackName];
    }

    registerMethod(methodName, methodFunction) {
        this.registeredMethods[methodName] = methodFunction;
    }

}

export default SharedWorkerBackend;