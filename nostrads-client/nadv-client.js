
import SharedExecutorClient from './sharedexecutor/SharedExecutorClient.js';

export async function newAdvertiserClient(options){
    if (!options) options = {};
    const executor = new SharedExecutorClient(options.worker ?? '../nadv-worker.js', {
        type: 'module',
        forceCompat: options.forceCompatModeForWorker  // uncomment this if you want to force the compat mode even if the SharedWorker API is available (mostly for debug)
    });
    await executor.invoke("initAdvertiser", options.relays);
    return executor;
}


