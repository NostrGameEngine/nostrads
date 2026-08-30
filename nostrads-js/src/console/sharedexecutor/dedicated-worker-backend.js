import { checkPostMessageOrigin } from './strict-origin.js';

const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9]{0,63}$/;

/**
 * A deliberately non-shared backend for wallet-bearing operations. Keeping one
 * worker per page prevents another same-origin tab from observing or invoking
 * the worker protocol through SharedWorker ports or BroadcastChannel.
 */
class DedicatedWorkerBackend {
    constructor(callback) {
        this.registeredMethods = Object.create(null);
        this.callbacks = Object.create(null);
        this.listeners = [];
        this.closed = false;
        this.readyPromise = Promise.resolve().then(() => callback(true));
        self.addEventListener('message', (event) => {
            checkPostMessageOrigin(event);
            for (const listener of this.listeners) listener(event);
        });
    }

    ready() {
        return this.readyPromise;
    }

    postMessageToMainThread(message) {
        if (!this.closed) self.postMessage(message);
    }

    addMainThreadMessageListener(callback) {
        this.listeners.push(callback);
    }

    close() {
        this.closed = true;
        self.close();
    }

    async invoke(method, args) {
        if (!NAME_PATTERN.test(method) || !Array.isArray(args)) throw new Error('Invalid worker invocation');
        const fn = this.registeredMethods[method];
        if (!fn) throw new Error(`Method ${method} is not registered.`);
        return fn(...args);
    }

    triggerCallback(callbackName, ...args) {
        const callback = this.callbacks[callbackName];
        if (callback) callback(...args);
    }

    registerCallback(callbackName, callbackFunction) {
        if (!NAME_PATTERN.test(callbackName)) throw new Error('Invalid callback name');
        this.callbacks[callbackName] = callbackFunction;
    }

    unregisterCallback(callbackName) {
        delete this.callbacks[callbackName];
    }

    registerMethod(methodName, methodFunction) {
        if (!NAME_PATTERN.test(methodName)) throw new Error('Invalid method name');
        this.registeredMethods[methodName] = methodFunction;
    }
}

export default DedicatedWorkerBackend;
