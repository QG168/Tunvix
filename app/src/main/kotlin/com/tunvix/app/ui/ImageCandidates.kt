package com.tunvix.app.ui
import kotlinx.coroutines.CancellationException
object ImageCandidates {
    suspend fun <T> firstAvailable(primary: String?, fallback: String?, load: suspend (String) -> T?): T? {
        for (uri in listOfNotNull(primary,fallback).distinct()) {
            try { load(uri)?.let { return it } } catch(cancel:CancellationException) { throw cancel } catch(_:Exception) { }
        }
        return null
    }
}
