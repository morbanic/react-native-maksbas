package com.maksbas

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Registers the native module with the host app.
 *
 * [BaseReactPackage] rather than a plain `ReactPackage`: `createNativeModules`
 * is deprecated under the New Architecture, and the module info below is what
 * lets React Native construct the module lazily, on first use from JS, instead
 * of eagerly at startup.
 */
class MaksbasPackage : BaseReactPackage() {

    override fun getModule(
        name: String,
        reactContext: ReactApplicationContext,
    ): NativeModule? = if (name == MaksbasModule.NAME) MaksbasModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            MaksbasModule.NAME to ReactModuleInfo(
                name = MaksbasModule.NAME,
                className = MaksbasModule.NAME,
                canOverrideExistingModule = false,
                needsEagerInit = false,
                isCxxModule = false,
                // Not a codegen'd TurboModule — it reaches JS through the
                // interop layer, which the New Architecture keeps for exactly
                // this kind of hand-written bridge module.
                isTurboModule = false,
            ),
        )
    }
}
