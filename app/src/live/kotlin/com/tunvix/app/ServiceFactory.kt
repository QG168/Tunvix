package com.tunvix.app

import android.content.Context
import com.tunvix.app.services.AppServices
import com.tunvix.backend.catalog.LocalCatalogRepository
import com.tunvix.backend.importer.AndroidSourceImporter
import com.tunvix.backend.playback.PlaybackRuntime

/** Production wiring; fresh installs contain only the empty custom source. */
object ServiceFactory {
    fun create(context: Context): AppServices {
        val app = context.applicationContext
        val importer = AndroidSourceImporter(app)
        val catalog = LocalCatalogRepository(app, importer)
        return AppServices(catalog, importer, PlaybackRuntime.controller(app),
            demo = false, backendReady = true,
            catalogReady = catalog.ready, catalogError = catalog.initializationError,
            closeCatalog = catalog::close,
            epg = com.tunvix.app.services.EpgRepository(app),
        )
    }
}
