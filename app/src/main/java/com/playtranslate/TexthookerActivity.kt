package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri


class TexthookerActivity : AppCompatActivity() {
    private var captureService: CaptureService? = null
    private var wasLiveBeforeStart = false

    /** Flips true on the first onPause after launch (when CustomTabs takes
     *  over). On the next onResume — i.e. the user dismissing the browser —
     *  we finish() ourselves rather than show an empty trampoline screen. */
    private var hasBeenBackgrounded = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CaptureService.LocalBinder).getService()
            captureService = service
            wasLiveBeforeStart = service.isLive
            WebsocketManager.instance.connect()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            captureService = null
        }
    }

    /** Set once onCreate runs the normal launch path. onDestroy uses this
     *  to skip teardown when the activity is being thrown away as a cold-
     *  restart trampoline (no servers started, no service bound). */
    private var didLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Process was killed while this activity was on top. We have no UI
        // of our own and the system never ran MainActivity's bootstrap on
        // this fresh process, so re-binding CaptureService and re-launching
        // CustomTabs cold is fragile (and has crashed). Bounce to
        // MainActivity instead — it owns the canonical app entry path.
        if (savedInstanceState != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        didLaunch = true
        // Session-scoped: stays true while CustomTabs is on top (where this
        // activity is technically stopped). Cleared in onDestroy. Read by
        // CaptureService.updateForegroundState and isUserReachable to keep
        // live mode and hotkeys alive during the texthooker session.
        isInForeground = true

        TexthookerHttpServer.get(this).startIfNeeded()
        bindService(Intent(this, CaptureService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        WebsocketManager.instance.connect()

        val url = "http://127.0.0.1:${TexthookerHttpServer.PORT}/"
        val intent: CustomTabsIntent = CustomTabsIntent.Builder()
            .setUrlBarHidingEnabled(true)
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, url.toUri())
    }

    override fun onResume() {
        super.onResume()
        PlayTranslateApplication.markResumed(this)
        if (hasBeenBackgrounded) {
            // CustomTabs was dismissed — drop the empty trampoline so back
            // navigates straight to MainActivity instead of a black screen.
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        hasBeenBackgrounded = true
    }

    override fun onDestroy() {
        if (!didLaunch) {
            super.onDestroy()
            return
        }
        isInForeground = false
        val service = captureService
        if (service != null) {
            // If we started live mode just for the hooker, stop it now
            if (!wasLiveBeforeStart) {
                service.stopLive()
            }
        }
        unbindService(serviceConnection)
        TexthookerHttpServer.get(this).stopIfRunning()
        super.onDestroy()
    }

    companion object {
        /** Session-scoped: true from the end of [onCreate] (after the cold-
         *  restart bounce) through [onDestroy]. Stays true while CustomTabs is
         *  on top of this activity — the entire useful lifetime of the
         *  texthooker session. Read by [CaptureService.updateForegroundState]
         *  to keep live polling alive while the user reads OCR output in the
         *  browser, and by [PlayTranslateAccessibilityService.isUserReachable]
         *  to keep hotkeys live during the session. */
        @Volatile
        var isInForeground = false
            set(value) {
                if (field == value) return
                field = value
                android.util.Log.d("CaptureService", "TexthookerActivity.isInForeground = $value")
                CaptureService.instance?.updateForegroundState()
                CaptureService.instance?.reconcileLiveModes("isInForeground=$value")
            }
    }
}
