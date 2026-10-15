package com.tunvix.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Process-level bounded LRU. Row disposal cancels only its wait, never another row's decode. */
internal class SharedImageCache<T>(
    private val capacityBytes: Int,
    private val scope: CoroutineScope,
    private val sizeOf: (T) -> Int,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String,T>(16,0.75f,true)
    private val pending = mutableMapOf<String,Deferred<T?>>()
    private var bytes = 0L

    fun peek(uri: String): T? = synchronized(lock) { entries[uri] }

    suspend fun load(uri: String, loader: suspend () -> T?): T? {
        val task = synchronized(lock) {
            entries[uri]?.let { return it }
            pending[uri] ?: scope.async(start=CoroutineStart.LAZY) {
                loader()?.also { value ->
                    synchronized(lock) {
                        entries.remove(uri)?.let { bytes-=sizeOf(it) }
                        val size=sizeOf(value)
                        if(size<=capacityBytes) {
                            entries[uri]=value; bytes+=size
                            while(bytes>capacityBytes) {
                                val oldest=entries.entries.iterator()
                                val entry=oldest.next(); bytes-=sizeOf(entry.value); oldest.remove()
                            }
                        }
                    }
                }
            }.also { created ->
                pending[uri]=created
                created.invokeOnCompletion { synchronized(lock) {
                    if(pending[uri]===created)pending.remove(uri)
                } }
            }
        }
        task.start()
        return task.await()
    }
}
