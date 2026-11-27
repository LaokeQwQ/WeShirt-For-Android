package com.laoke.weshirt.platform.update

/** A Service can be destroyed while blocking disk/package work is still unwinding. Neither event
 * alone permits a new transfer to share the target directory or foreground notification ID. */
internal class UpdateDownloadLease {
    private var owner: String? = null
    private var destroyed = false
    private var finished = false

    @Synchronized fun acquire(id: String): Boolean {
        if (owner != null) return false
        owner = id
        destroyed = false
        finished = false
        return true
    }

    @Synchronized fun serviceDestroyed(id: String) {
        if (owner != id) return
        destroyed = true
        releaseWhenStopped()
    }

    @Synchronized fun workerFinished(id: String) {
        if (owner != id) return
        finished = true
        releaseWhenStopped()
    }

    /** Only startForegroundService failure, before any service/worker exists, may bypass the gate. */
    @Synchronized fun startRejected(id: String) { if (owner == id) owner = null }

    private fun releaseWhenStopped() { if (destroyed && finished) owner = null }
}
