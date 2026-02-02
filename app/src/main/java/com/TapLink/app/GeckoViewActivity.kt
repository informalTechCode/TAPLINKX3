package com.TapLinkX3.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class GeckoViewActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var geckoSession: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val geckoView = GeckoView(this)
        setContentView(geckoView)

        val runtime = GeckoRuntime.create(this)
        geckoSession = GeckoSession().apply {
            open(runtime)
        }
        geckoView.setSession(geckoSession)

        val url = intent.getStringExtra(EXTRA_URL) ?: Constants.DEFAULT_URL
        geckoSession.loadUri(url)
    }

    override fun onDestroy() {
        geckoSession.close()
        super.onDestroy()
    }
}
