package app.aaps.plugins.source.di

import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.plugins.source.BGSourceFragment
import app.aaps.plugins.source.NSClientSourcePlugin
import app.aaps.plugins.source.XdripSourcePlugin
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module(
    includes = [
        SourceModule.Bindings::class
    ]
)

@Suppress("unused")
abstract class SourceModule {

    @ContributesAndroidInjector abstract fun contributesBGSourceFragment(): BGSourceFragment

    @ContributesAndroidInjector abstract fun contributesXdripWorker(): XdripSourcePlugin.XdripSourceWorker

    @Module
    interface Bindings {

        // NSClientSourcePlugin is no longer offered as a selectable BG source (see PluginsListModule),
        // but NSClientV3 still injects NSClientSource to decide whether to re-upload BG to Nightscout.
        @Binds fun bindNSClientSource(nsClientSourcePlugin: NSClientSourcePlugin): NSClientSource
        @Binds fun bindXDrip(xdripSourcePlugin: XdripSourcePlugin): XDripSource
    }
}
