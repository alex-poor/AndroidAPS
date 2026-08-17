package app.aaps.pump.ypsopump.di

import app.aaps.pump.ypsopump.YpsoPumpFragment
import app.aaps.pump.ypsopump.YpsoPumpPlugin
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.crypto.KeyExchange
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import dagger.Module
import dagger.Provides
import dagger.android.ContributesAndroidInjector
import javax.inject.Singleton

@Module
@Suppress("unused")
abstract class YpsoPumpModule {

    // All components use constructor injection via @Inject @Singleton,
    // so no explicit @Provides or @Binds needed for:
    //   - YpsoPumpPlugin
    //   - YpsoBleManager
    //   - SessionCrypto
    //   - KeyExchange
    //   - YpsoPumpState

    // YpsoPumpPlugin declares .fragmentClass(YpsoPumpFragment), so opening the pump tab attaches a
    // DaggerFragment — without this binding that attach throws
    // "No injector factory bound for YpsoPumpFragment" and takes the whole app down.
    @ContributesAndroidInjector abstract fun contributesYpsoPumpFragment(): YpsoPumpFragment
}
