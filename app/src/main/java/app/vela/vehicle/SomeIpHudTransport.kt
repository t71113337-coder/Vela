package app.vela.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import app.vela.core.vehicle.SomeIpHudFrame
import app.vela.core.vehicle.VehicleSupport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes the windshield-HUD road-info frame to a BYD head unit's glass through the SOME/IP
 * gateway `com.ts.car.someip.service` (SPEC 10.9). No ADB and no permission: an ordinary app can
 * bind the gateway and fire events. The frame bytes come from [SomeIpHudFrame]; this does the
 * bind and the Binder transactions.
 *
 * Every transaction runs on one background thread, because a wedged gateway can block a transact
 * forever and must never reach the main thread. The session is: a TYPED bind (the intent type is
 * our own package, which the gateway requires; a typeless bind NPE-crashes the gateway process and
 * the factory AR-HUD with it on unbind), then register a callback, then start the service before
 * the first fire, then fire the frame and re-fire it on a keepalive so the glass does not blank.
 * The bind prefers the gateway's exported manager class by name (the same component proven senders
 * use) and falls back to resolving the action within the package when that class is absent.
 *
 * Failures are counted. A bind that never connects, a dead binder, a null binding or a refused
 * start each back off a growing delay before the next attempt (so a crash-looping gateway is not
 * hammered), and after a cap the transport gives up for the rest of the drive. After a few failures
 * in rich mode it drops the 0x8003 map window and the fast cadence and keeps only the road frame.
 * A new drive ([clear]) resets the counters. A head unit without the gateway is the normal case;
 * [available] is false there and nothing is bound.
 */
class SomeIpHudTransport(private val appContext: Context) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vela-someip-hud").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "vela-someip-keepalive").apply { isDaemon = true } }
    // keepalive and keepaliveMs are touched only from the caller thread (the main thread) in
    // send/clear/stop; keepaliveMs is volatile so the worker's keepalive task reads a stable value.
    private var keepalive: ScheduledFuture<*>? = null
    @Volatile private var keepaliveMs = KEEPALIVE_MS

    // Everything below is confined to the single worker thread (and the callbacks, which post to it).
    private var bound = false
    private var server: IBinder? = null
    private var started = false
    private var lastFrame: ByteArray? = null
    private var lastMapSpec: SomeIpMapCard.Spec? = null
    private var lastMapFrame: ByteArray? = null
    private var bindingSinceElapsed: Long = 0L
    private var deaths = 0
    private var rebindNotBeforeElapsed = 0L
    private var gaveUp = false
    @Volatile private var richFallback = false

    // Answers the gateway's callbacks like the AIDL stub would, so registration does not fault.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> { reply?.writeString(CALLBACK_TOKEN); return true }
                1 -> { reply?.writeNoException(); reply?.writeInt(0); return true }                 // onSomeIpEvent
                2 -> { runCatching { data.readInt() }; reply?.writeNoException(); return true }      // onHalServiceStatus(bool)
                3 -> { reply?.writeNoException(); reply?.writeInt(0); reply?.writeInt(0); return true } // onRequest
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = worker.execute {
            server = binder
            started = false
            registerAndStartLocked()
            lastFrame?.let { fireLocked(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) = worker.execute {
            server = null; started = false
            bindingSinceElapsed = SystemClock.elapsedRealtime() // the stuck-recovery timer runs from the drop
        }
        override fun onBindingDied(name: ComponentName?) = worker.execute {
            noteFailureLocked()
            resetBindingLocked()
            if (!gaveUp) support = VehicleSupport.UNAVAILABLE
        }
        override fun onNullBinding(name: ComponentName?) = worker.execute {
            noteFailureLocked()
            resetBindingLocked()
            if (!gaveUp) support = VehicleSupport.ACCESS_DENIED
        }
    }

    /** Send a guidance frame and keep it refreshed on the gateway's cadence. [fast] uses the quicker
     *  rich-mode resend; the glass blanks after about 2 s without a frame, and a richer renderer
     *  wants a steadier stream. In rich fallback the map window and the fast cadence are dropped. */
    fun send(frame: ByteArray, mapSpec: SomeIpMapCard.Spec? = null, fast: Boolean = false) {
        val want = if (fast && !richFallback) KEEPALIVE_FAST_MS else KEEPALIVE_MS
        worker.execute {
            lastFrame = frame
            val mapWasOn = lastMapFrame != null
            if (!richFallback && mapSpec != lastMapSpec) { // render once per content change; keepalive resends bytes
                lastMapSpec = mapSpec
                lastMapFrame = mapSpec?.let {
                    runCatching { SomeIpMapCard.frame(it) }.getOrNull()?.also { f -> Log.d(TAG, "0x8003 map window ${f.size} bytes") }
                }
            }
            ensureBoundLocked()
            if (server != null) {
                fireLocked(frame)
                if (lastMapFrame != null) fireMapLocked()
                else if (mapWasOn && started) runCatching { fireOnceLocked(SomeIpMapCard.frame(null), SomeIpHudFrame.TOPIC_MAP) } // blank once on rich->minimal
            }
        }
        if (want != keepaliveMs) { keepaliveMs = want; stopKeepalive() }
        startKeepalive()
    }

    /** Fire one blank frame and stop refreshing, and reset the failure counters for the next drive.
     *  The binding is kept, so if it dies while cleared nothing rebinds until the next [send]; that is
     *  fine because a cleared HUD has nothing to show. [rich] also fires a black 0x8003 map window, to
     *  blank a renderer that was reading both. */
    fun clear(rich: Boolean = false) {
        stopKeepalive()
        worker.execute {
            lastFrame = null; lastMapSpec = null; lastMapFrame = null
            deaths = 0; gaveUp = false; richFallback = false; rebindNotBeforeElapsed = 0L
            if (server != null && started) {
                fireLocked(if (rich) SomeIpHudFrame.clearRich() else SomeIpHudFrame.clear())
                if (rich) runCatching { fireOnceLocked(SomeIpMapCard.frame(null), SomeIpHudFrame.TOPIC_MAP) }
            }
        }
    }

    // Fire the cached map window on its own topic, after the road frame has started the service. A
    // map fire does not drive recovery; the road fire governs that.
    private fun fireMapLocked() {
        val m = lastMapFrame ?: return
        if (server == null || !started) return
        runCatching { lastMapFireRc = fireOnceLocked(m, SomeIpHudFrame.TOPIC_MAP) }
    }

    /** Stop the service, unbind (safe because the bind is typed) and shut the worker and ticker down.
     *  The instance is single-use after this; the controller drops its reference. */
    fun stop() {
        stopKeepalive()
        worker.execute {
            runCatching { if (server != null && started) exec(TX_STOP, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) } }
            resetBindingLocked()
            support = VehicleSupport.AVAILABLE
        }
        worker.shutdown()   // let the queued teardown run, then stop accepting work
        ticker.shutdownNow()
    }

    private fun ensureBoundLocked() {
        recoverIfStuckLocked()
        if (bound || gaveUp) return
        if (SystemClock.elapsedRealtime() < rebindNotBeforeElapsed) return // inside the backoff window
        if (!available(appContext)) { support = VehicleSupport.NOT_PRESENT; return }
        val pm = appContext.packageManager
        // Prefer the known manager ComponentName; fall back to resolving the action in the package
        // when a firmware exports the manager under a different class.
        val named = Intent(ACTION).setClassName(PKG, CLASS)
        val useNamed = runCatching { pm.resolveService(named, 0) }.getOrNull() != null
        val intent = (if (useNamed) named else Intent(ACTION).setPackage(PKG))
            .setType(appContext.packageName) // TYPED bind with our own package: the gateway keys the
            // client on the type and crashes on unbind if it is null, so this is mandatory and must be us
        bound = runCatching { appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        bindingSinceElapsed = if (bound) SystemClock.elapsedRealtime() else 0L
        if (bound) {
            support = VehicleSupport.AVAILABLE
        } else {
            noteFailureLocked()
            if (!gaveUp) support = VehicleSupport.ACCESS_DENIED
            Log.w(TAG, "SOME/IP HUD bind refused")
        }
    }

    /** A bind that connects but never starts, or a connection that dropped and did not come back, is
     *  torn down after [STUCK_BIND_MS] so the next attempt can retry (SPEC 10.9). */
    private fun recoverIfStuckLocked() {
        if (!bound) return
        if (server != null && started) return
        if (bindingSinceElapsed == 0L) return
        val waited = SystemClock.elapsedRealtime() - bindingSinceElapsed
        if (waited < STUCK_BIND_MS) return
        Log.w(TAG, "SOME/IP HUD bind stuck after ${waited}ms; recovering")
        noteFailureLocked()
        resetBindingLocked()
        if (!gaveUp) support = VehicleSupport.UNAVAILABLE
    }

    // Count one failure, drop to minimal after a few, and give up after the cap; between failures,
    // hold off the next bind by a delay that grows with the count.
    private fun noteFailureLocked() {
        deaths++
        if (!richFallback && deaths >= RICH_FALLBACK_DEATHS) enterRichFallbackLocked()
        if (deaths >= DEATH_CAP) {
            gaveUp = true
            support = VehicleSupport.UNAVAILABLE
            Log.w(TAG, "SOME/IP HUD gave up after $deaths failures; retries on the next drive")
        } else {
            val shift = (deaths - 1).coerceIn(0, 5)
            rebindNotBeforeElapsed = SystemClock.elapsedRealtime() + minOf(BACKOFF_BASE_MS shl shift, BACKOFF_MAX_MS)
        }
    }

    private fun noteSuccessLocked() {
        deaths = 0; gaveUp = false; rebindNotBeforeElapsed = 0L
    }

    // Too many failures to keep paying the rich cost: drop the map window and the fast cadence, keep
    // the road frame. The server is usually gone at this point, so the blank is left to the next send.
    private fun enterRichFallbackLocked() {
        richFallback = true
        lastMapSpec = null; lastMapFrame = null
        keepaliveMs = KEEPALIVE_MS
        Log.w(TAG, "SOME/IP HUD falling back to minimal after $deaths failures")
    }

    private fun resetBindingLocked() {
        if (bound) runCatching { appContext.unbindService(conn) }
        bound = false
        server = null
        started = false
        bindingSinceElapsed = 0L
    }

    private fun registerAndStartLocked() {
        if (server == null) return
        runCatching {
            exec(TX_REGISTER, readInt = false) { it.writeStrongBinder(callback) }
            val rc = exec(TX_START, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) }
            lastStartRc = rc
            started = rc == RC_OK || rc == RC_ALREADY_STARTED
            if (started) {
                bindingSinceElapsed = 0L // running: the stuck-recovery timer is off
                support = VehicleSupport.ACTIVE
            } else {
                // Refused start: back off and tear down rather than hammering start on every keepalive.
                support = VehicleSupport.FAILED
                noteFailureLocked()
                resetBindingLocked()
            }
        }.onFailure {
            support = VehicleSupport.FAILED
            Log.w(TAG, "register/start failed: ${it.javaClass.simpleName}")
            noteFailureLocked()
            resetBindingLocked()
        }
    }

    private fun fireLocked(frame: ByteArray) {
        if (server == null) return
        if (!started) registerAndStartLocked()
        if (!started) return
        runCatching {
            var rc = fireOnceLocked(frame, SomeIpHudFrame.TOPIC_ROAD_INFO)
            if (rc == RC_NOT_STARTED) { // the service was lost: re-offer and try once more
                started = false
                registerAndStartLocked()
                if (started) rc = fireOnceLocked(frame, SomeIpHudFrame.TOPIC_ROAD_INFO)
            }
            lastFireRc = rc
            if (rc == RC_OK) {
                noteSuccessLocked()
                support = VehicleSupport.ACTIVE
            } else {
                support = VehicleSupport.FAILED
                // A dead binder after a failed fire needs a fresh bind; keepalive alone will not.
                if (server?.isBinderAlive != true) { noteFailureLocked(); resetBindingLocked() }
            }
        }.onFailure {
            support = VehicleSupport.FAILED
            Log.w(TAG, "fire failed: ${it.javaClass.simpleName}")
            if (server?.isBinderAlive != true) { noteFailureLocked(); resetBindingLocked() }
        }
    }

    private fun fireOnceLocked(frame: ByteArray, topic: Long): Int = exec(TX_FIRE, readInt = true) { d ->
        d.writeInt(1)                              // typed-object non-null marker
        d.writeLong(topic)
        d.writeLong(System.currentTimeMillis())
        d.writeInt(frame.size)                     // SomeIpData reads an explicit size, then the array (len twice by design)
        d.writeByteArray(frame)
    }

    // One transaction with the interface token written first; reads the int result when the call
    // returns one, and always recycles both parcels. -1 when there is no server.
    private fun exec(code: Int, readInt: Boolean, write: (Parcel) -> Unit): Int {
        val s = server ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVER_TOKEN)
            write(data)
            s.transact(code, data, reply, 0)
            reply.readException()
            return if (readInt) reply.readInt() else 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun startKeepalive() {
        if (keepalive != null) return
        val ms = keepaliveMs
        keepalive = ticker.scheduleWithFixedDelay({
            worker.execute {
                recoverIfStuckLocked()
                lastFrame?.let { if (server != null) { fireLocked(it); fireMapLocked() } else ensureBoundLocked() }
            }
        }, ms, ms, TimeUnit.MILLISECONDS)
    }

    private fun stopKeepalive() {
        keepalive?.cancel(false)
        keepalive = null
    }

    @Volatile var lastStartRc: Int = -1; private set
    @Volatile var lastFireRc: Int = -1; private set
    @Volatile var lastMapFireRc: Int = -1; private set

    companion object {
        private const val TAG = "VelaSomeIp"
        private const val PKG = "com.ts.car.someip.service"
        /** Exported manager class the proven windshield senders bind by name (SPEC 10.9). */
        private const val CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
        private const val ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val SERVER_TOKEN = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"
        private const val TX_REGISTER = 1
        private const val TX_START = 4
        private const val TX_STOP = 5
        private const val TX_FIRE = 6
        private const val RC_OK = 0
        private const val RC_NOT_STARTED = 11
        private const val RC_ALREADY_STARTED = 13
        private const val KEEPALIVE_MS = 1000L
        /** Quicker rich-mode resend (SPEC 10.9): a richer renderer wants a steadier frame stream. */
        private const val KEEPALIVE_FAST_MS = 500L
        /** Tear down a bind that never starts; matches the recovery window used by proven senders. */
        private const val STUCK_BIND_MS = 3000L
        /** Backoff before the next bind attempt, doubling per consecutive failure up to the cap. */
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_MAX_MS = 30_000L
        /** Give up for the rest of the drive after this many consecutive failures. */
        private const val DEATH_CAP = 6
        /** Drop the rich map window and fast cadence after this many, keeping the road frame. */
        private const val RICH_FALLBACK_DEATHS = 3

        /** True when this head unit has the gateway, by the manager class or the action in the package.
         *  A resolve only, no bind. */
        fun available(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            pm.resolveService(Intent(ACTION).setClassName(PKG, CLASS), 0) != null ||
                pm.resolveService(Intent(ACTION).setPackage(PKG), 0) != null
        }.getOrDefault(false)
    }
}
