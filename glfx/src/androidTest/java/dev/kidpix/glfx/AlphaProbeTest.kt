package dev.kidpix.glfx

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Logs how this device's WebView handles alpha around glfx.js (documentation aid, no assertions). */
@RunWith(AndroidJUnit4::class)
class AlphaProbeTest {
    @Test
    fun probeWebViewAlpha() {
        GlfxJsReference("testpic.png").use { Log.i("GlfxProbe", it.renderer + " :: " + it.probe()) }
    }
}
