package john.fucklocation.xposed.hooks.blindhook

import android.location.Location
import de.robv.android.xposed.XposedBridge
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

object BlindHookLocation: BaseLocationHook() {
    operator fun invoke(clazz: Class<*>, classLoader: ClassLoader): Int {
        return BlindHook(clazz, classLoader) { method, location: Location? ->
            if (location == null || !FakeLoc.enable) return@BlindHook location

            val newLoc = injectLocation(location)

            if (FakeLoc.enableDebugLog) {
                Logger.debug("${method.name} injected: $newLoc")
            }

            newLoc
        }
    }
}