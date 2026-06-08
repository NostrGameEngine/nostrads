function isFromSharedWorker(event) {
    const target = event?.target;
    return typeof MessagePort !== 'undefined' && target instanceof MessagePort;
}

function isFromDedicatedWorker(event) {
    const target = event?.target;

    if (typeof Worker !== 'undefined' && target instanceof Worker) return true;

    const inWorker = typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope;
    if (inWorker && (target === self || target?.constructor?.name === 'DedicatedWorkerGlobalScope')) {
        return true;
    }

    return false;
}

function isFromBroadcastChannel(event) {
    const target = event?.currentTarget ?? event?.target;
    return typeof BroadcastChannel !== 'undefined' && target instanceof BroadcastChannel;
}

export function checkPostMessageOrigin(event) {
    const allowedOrigins = [
        self.location.origin ?? window.location.origin
    ];

    if (isFromSharedWorker(event) || isFromDedicatedWorker(event) || isFromBroadcastChannel(event)) {
        allowedOrigins.push('');
    }


    if (!allowedOrigins.includes(event.origin ?? '')) {
        throw new Error(`Untrusted origin: ${event.origin} not in ${allowedOrigins.join(', ')}`);
    }
}


