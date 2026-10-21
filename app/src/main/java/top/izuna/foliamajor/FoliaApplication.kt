package top.izuna.foliamajor

import android.app.Application

/**
 * Application entry point.
 *
 * Currently empty: the playback service and the API client are both created where they are used
 * rather than held here, so there is no global state to set up yet. It exists as a named class
 * because the manifest points at it and because the DI wiring for the local library will land here.
 */
class FoliaApplication : Application()
