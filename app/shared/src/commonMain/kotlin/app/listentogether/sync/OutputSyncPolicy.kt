package app.listentogether.sync

/** Remote renderers report coarse positions and must never inherit the phone's speed corrections. */
fun outputSyncMode(requested: SyncMode, remote: Boolean): SyncMode = if (remote) SyncMode.RELAXED else requested
