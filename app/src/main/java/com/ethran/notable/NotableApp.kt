package com.ethran.notable

import android.app.Application
import com.ethran.notable.io.excalidraw.ExcalidrawUnifiedTemplate
import com.onyx.android.sdk.rx.RxManager
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class NotableApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RxManager.Builder.initAppContext(this)
        ExcalidrawUnifiedTemplate.initFromAsset { name ->
            assets.open(name).bufferedReader()
        }
        checkHiddenApiBypass()
    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

}