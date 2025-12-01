package it.zeroTituli

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HattrickPlugin : Plugin() {

    override fun load(context: Context) {
        // Register the Hattrick provider
        registerMainAPI(Hattrick())
    }
}
