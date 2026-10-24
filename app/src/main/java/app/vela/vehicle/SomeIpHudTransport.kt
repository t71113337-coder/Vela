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
 * The bind targets the gateway's exported manager class by name (the same component proven senders
 * use); a resolve-only bind can miss that class on some firmwares. A bind that never reaches
 * `onServiceConnected`, or a dead binder after a fire failure, is torn down and retried. A head
 * unit without the gateway is the normal case; [available] is false there and nothing is bound.
 */
class SomeIpHudTransport(private val appContext: Context) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vela-someip-hud").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "vela-someip-keepalive").apply { isDaemon = true } }
    private var keepalive: ScheduledFuture<*>? = null
    @Volatile private var keepaliveMs = KEEPALIVE_MS

    private var bound = false
    private var server: IBinder? = null
    private var started = false
    private var lastFrame: ByteArray? = null
    private var lastMapSpec: SomeIpMapCard.Spec? = null
    private var lastMapFrame: ByteArray? = null
    private var bindingSinceElapsed: Long = 0L

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
        override fun onServiceDisconnected(name: ComponentName?) = worker.execute { server = null; started = false }
        override fun onBindingDied(name: ComponentName?) = worker.execute {
            resetBindingLocked(rebind = true)
            support = VehicleSupport.UNAVAILABLE
        }
        override fun onNullBinding(name: ComponentName?) = worker.execute {
            resetBindingLocked(rebind = false)
            support = VehicleSupport.ACCESS_DENIED
        }
    }

    /** Send a guidance frame and keep it refreshed on the gateway's cadence. [fast] uses the quicker
     *  rich-mode resend; the glass blanks after about 2 s without a frame, and a richer renderer
     *  wants a steadier stream. */
    fun send(frame: ByteArray, mapSpec: SomeIpMapCard.Spec? = null, fast: Boolean = false) {
        val want = if (fast) KEEPALIVE_FAST_MS else KEEPALIVE_MS
        worker.execute {
            lastFrame = frame
            if (mapSpec != lastMapSpec) { // render once per content change; the keepalive resends bytes
                lastMapSpec = mapSpec
                lastMapFrame = mapSpec?.let { runCatching { SomeIpMapCard.frame(it) }.getOrNull() }
            }
            ensureBoundLocked()
            if (server != null) { fireLocked(frame); fireMapLocked() }
        }
        if (want != keepaliveMs) { keepaliveMs = want; stopKeepalive() }
        startKeepalive()
    }

    /** Fire one blank frame and stop refreshing. The binding is kept for the next drive. [rich] fires
     *  the rich clear road and a black map window, to blank a renderer that was reading both. */
    fun clear(rich: Boolean = false) {
        stopKeepalive()
        worker.execute {
            lastFrame = null; lastMapSpec = null; lastMapFrame = null
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

    /** Stop the service and unbind (safe because the bind is typed). */
    fun stop() {
        stopKeepalive()
        worker.execute {
            runCatching { if (server != null && started) exec(TX_STOP, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) } }
            resetBindingLocked(rebind = false)
            support = VehicleSupport.AVAILABLE
        }
    }

    private fun ensureBoundLocked() {
        recoverIfStuckLocked()
        if (bound) return
        if (!available(appContext)) { support = VehicleSupport.NOT_PRESENT; return }
        // Hardcoded manager class: the same ComponentName proven windshield senders bind. A
        // resolve-only Intent can miss that class when the package exports more than one service.
        val intent = Intent(ACTION)
            .setClassName(PKG, CLASS)
            .setType(appContext.packageName) // TYPED bind with our own package: the gateway keys the
            // client on the type and crashes on unbind if it is null, so this is mandatory and must be us
        bound = runCatching { appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        bindingSinceElapsed = if (bound) SystemClock.elapsedRealtime() else 0L
        support = if (bound) VehicleSupport.AVAILABLE else VehicleSupport.ACCESS_DENIED
        if (!bound) Log.w(TAG, "SOME/IP HUD bind refused")
    }

    /** A bind that never connects, or connects but never starts, is torn down after [STUCK_BIND_MS]
     *  so the next send can retry (SPEC 10.9). */
    private fun recoverIfStuckLocked() {
        if (!bound) return
        if (server != null && started) return
        if (bindingSinceElapsed == 0L) return
        val waited = SystemClock.elapsedRealtime() - bindingSinceElapsed
        if (waited < STUCK_BIND_MS) return
        Log.w(TAG, "SOME/IP HUD bind stuck after ${waited}ms; recovering")
        resetBindingLocked(rebind = false)
        support = VehicleSupport.UNAVAILABLE
    }

    private fun resetBindingLocked(rebind: Boolean) {
        if (bound) runCatching { appContext.unbindService(conn) }
        bound = false
        server = null
        started = false
        bindingSinceElapsed = 0L
        if (rebind && lastFrame != null) ensureBoundLocked()
    }

    private fun registerAndStartLocked() {
        if (server == null) return
        runCatching {
            exec(TX_REGISTER, readInt = false) { it.writeStrongBinder(callback) }
            val rc = exec(TX_START, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) }
            lastStartRc = rc
            started = rc == RC_OK || rc == RC_ALREADY_STARTED
            support = if (started) VehicleSupport.ACTIVE else VehicleSupport.FAILED
        }.onFailure { support = VehicleSupport.FAILED; Log.w(TAG, "register/start failed: ${it.javaClass.simpleName}") }
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
                support = VehicleSupport.ACTIVE
            } else {
                support = VehicleSupport.FAILED
                // A dead binder after a failed fire needs a fresh bind; keepalive alone will not.
                if (server?.isBinderAlive != true) resetBindingLocked(rebind = true)
            }
        }.onFailure {
            support = VehicleSupport.FAILED
            Log.w(TAG, "fire failed: ${it.javaClass.simpleName}")
            if (server?.isBinderAlive != true) resetBindingLocked(rebind = true)
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

        /** True when this head unit has the gateway manager. A resolve of the bind Intent, no bind. */
        fun available(context: Context): Boolean =
            runCatching {
                context.packageManager.resolveService(Intent(ACTION).setClassName(PKG, CLASS), 0)
            }.getOrNull() != null
    }
}
