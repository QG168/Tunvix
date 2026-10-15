package com.tunvix.app.services
import com.tunvix.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppServices(
    val catalog: CatalogRepository,
    val importer: SourceImporter,
    val playback: PlaybackController,
    val demo: Boolean,
    val backendReady: Boolean = false,
    val catalogReady: StateFlow<Boolean> = MutableStateFlow(true),
    val catalogError: StateFlow<String?> = MutableStateFlow(null),
    val closeCatalog: () -> Unit = {},
    val epg: EpgRepository? = null,
)
