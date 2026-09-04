package com.lsfg.android.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicBoolean

class RootCaptureEngine(private val ctx: Context) {

    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile private var service: IShizukuCaptureService? = null
    @Volatile private var pendingStart: StartArgs? = null
    @Volatile private var errorListener: ErrorListener? = null
    @Volatile private var activeListener: (() -> Unit)? = null
    @Volatile private var metricsOnly: Boolean = false
    @Volatile private var fpsListener: CaptureEngine.FpsListener? = null
    @Volatile private var graphListener: CaptureEngine.FrameGraphListener? = null
    private var metricsThread: HandlerThread? = null
    private var metricsHandler: Handler? = null
    private var fpsPoller: Runnable? = null
    private var graphPoller: Runnable? = null
    @Volatile private var fpsFrameCount: Long = 0
    @Volatile private var graphFrameCount: Long = 0
    private var fpsWindowStartMs: Long = 0L
    private var graphWindowStartMs: Long = 0L
    private var graphLastGeneratedCount: Long = 0L
    private var lastUniqueCaptureCount: Long = 0L
    private var graphLastUniqueCaptureCount: Long = 0L
    private var lastPostedCount: Long = 0L
    private var graphRealEma: Float = 0f
    private var graphGenEma: Float = 0f
    @Volatile private var everConnected: Boolean = false
    @Volatile internal var state: PrivilegedCaptureState = PrivilegedCaptureState.IDLE
        private set
    private val binding = AtomicBoolean(false)
    private val rootProbeInFlight = AtomicBoolean(false)
    private val rootVerified = AtomicBoolean(false)
    private val firstFrameToNative = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            binding.set(false)
            everConnected = true
            service = binder?.takeIf { it.pingBinder() }
                ?.let { IShizukuCaptureService.Stub.asInterface(it) }
            val svc = service
            if (svc == null) {
                fail("ROOT_ERROR bind returned no live service")
                return
            }
            val description = runCatching { svc.describeBackend() }
                .getOrElse { "describeBackend failed: ${it.message ?: it.javaClass.simpleName}" }
            LsfgLog.i(TAG, "ROOT_BIND_OK $description")
            if (!description.contains("uid=0")) {
                fail("ROOT_ERROR service is not uid=0: $description")
                return
            }
            pendingStart?.let { start ->
                startCaptureInternal(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val wasConnected = everConnected
            service = null
            if (state != PrivilegedCaptureState.STOPPING && state != PrivilegedCaptureState.IDLE) {
                fail(if (wasConnected) "ROOT_ERROR service disconnected" else "ROOT_PERMISSION_FAIL access denied or su unavailable")
            }
        }
    }

    fun setErrorListener(listener: ErrorListener?) { errorListener = listener }
    fun setActiveListener(listener: (() -> Unit)?) { activeListener = listener }
    fun setFpsListener(listener: CaptureEngine.FpsListener?) { fpsListener = listener }
    fun setFrameGraphListener(listener: CaptureEngine.FrameGraphListener?) { graphListener = listener }

    /** A ready root backend has a shell that actually reported uid 0. */
    fun isReady(): Boolean = rootVerified.get() || (Shell.getCachedShell()?.isRoot == true)

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        metricsOnly = false
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    fun startMetricsOnly(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        metricsOnly = true
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    private fun startCaptureInternal(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        if (state == PrivilegedCaptureState.ERROR || state == PrivilegedCaptureState.STOPPING) return
        LsfgLog.i(TAG, "ROOT_START permission=${Shell.isAppGrantedRoot()} sdk=${android.os.Build.VERSION.SDK_INT}")
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            fail("ROOT_ERROR target package not found: $targetPackage")
            return
        }

        pendingStart = StartArgs(targetPackage, width, height, maxFps)
        if (!rootVerified.get()) {
            requestRootAndBind()
            return
        }
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        state = PrivilegedCaptureState.CAPTURE_STARTING
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            pendingStart = null
            LsfgLog.i(TAG, "Root ${if (metricsOnly) "metrics" else "capture"} started pkg=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Root startCapture failed", it)
            fail("ROOT_ERROR startCapture: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        state = PrivilegedCaptureState.STOPPING
        pendingStart = null
        stopFpsCounter()
        stopFrameGraph()
        runCatching { service?.stopCapture() }
        runCatching { RootService.unbind(connection) }
        service = null
        everConnected = false
        binding.set(false)
        rootProbeInFlight.set(false)
        rootVerified.set(false)
        state = PrivilegedCaptureState.IDLE
        LsfgLog.i(TAG, "ROOT_STOP")
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }

    @Synchronized
    fun startFpsCounter() {
        if (fpsPoller != null) return
        val h = ensureMetricsThread()
        fpsWindowStartMs = SystemClock.elapsedRealtime()
        lastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        lastPostedCount = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - fpsWindowStartMs).coerceAtLeast(1L)
                fpsWindowStartMs = now
                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - lastUniqueCaptureCount).coerceAtLeast(0L)
                lastUniqueCaptureCount = uniqNow
                val realFps = uniqDelta * 1000f / elapsed
                val postedNow = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
                val postedDelta = (postedNow - lastPostedCount).coerceAtLeast(0L)
                lastPostedCount = postedNow
                val totalFps = postedDelta * 1000f / elapsed
                fpsListener?.onFpsUpdate(realFps, totalFps)
                metricsHandler?.postDelayed(this, 1000L)
            }
        }
        fpsPoller = poll
        h.postDelayed(poll, 1000L)
        LsfgLog.i(TAG, "Root FPS counter started")
    }

    @Synchronized
    fun stopFpsCounter() {
        fpsPoller?.let { metricsHandler?.removeCallbacks(it) }
        fpsPoller = null
        fpsFrameCount = 0
        maybeQuitMetricsThread()
    }

    @Synchronized
    fun startFrameGraph() {
        if (graphPoller != null) return
        val h = ensureMetricsThread()
        graphWindowStartMs = SystemClock.elapsedRealtime()
        graphLastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        graphLastGeneratedCount = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
        graphRealEma = 0f
        graphGenEma = 0f
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - graphWindowStartMs).coerceAtLeast(1L)
                graphWindowStartMs = now
                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - graphLastUniqueCaptureCount).coerceAtLeast(0L)
                graphLastUniqueCaptureCount = uniqNow
                val realFpsRaw = uniqDelta * 1000f / elapsed
                val generated = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
                val genDelta = (generated - graphLastGeneratedCount).coerceAtLeast(0L)
                graphLastGeneratedCount = generated
                val genFpsRaw = genDelta * 1000f / elapsed
                val alpha = 0.35f
                graphRealEma = if (graphRealEma <= 0.01f) realFpsRaw
                               else alpha * realFpsRaw + (1f - alpha) * graphRealEma
                graphGenEma = if (graphGenEma <= 0.01f) genFpsRaw
                              else alpha * genFpsRaw + (1f - alpha) * graphGenEma
                graphListener?.onFrameGraphSample(graphRealEma, graphGenEma)
                metricsHandler?.postDelayed(this, 200L)
            }
        }
        graphPoller = poll
        h.postDelayed(poll, 200L)
        LsfgLog.i(TAG, "Root frame graph started")
    }

    @Synchronized
    fun stopFrameGraph() {
        graphPoller?.let { metricsHandler?.removeCallbacks(it) }
        graphPoller = null
        graphFrameCount = 0
        maybeQuitMetricsThread()
    }

    private fun ensureMetricsThread(): Handler {
        metricsHandler?.let { return it }
        val t = HandlerThread("lsfg-root-metrics").also { it.start() }
        val h = Handler(t.looper)
        metricsThread = t
        metricsHandler = h
        return h
    }

    private fun maybeQuitMetricsThread() {
        if (fpsPoller == null && graphPoller == null) {
            metricsThread?.quitSafely()
            metricsThread = null
            metricsHandler = null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * isAppGrantedRoot() is intentionally non-blocking and may be false before
     * libsu has attempted su.  Do not use it as a denial: construct the real
     * shell, which gives Magisk/APatch the opportunity to show its grant prompt,
     * then verify the remote identity with a short `id -u` command.
     */
    private fun requestRootAndBind() {
        if (!rootProbeInFlight.compareAndSet(false, true)) return
        state = PrivilegedCaptureState.WAITING_PERMISSION
        val cached = Shell.getCachedShell()
        LsfgLog.i(TAG, "ROOT_SU_AVAILABLE cached=${cached != null} grantState=${Shell.isAppGrantedRoot()}")
        LsfgLog.i(TAG, "ROOT_SU_REQUEST")
        Shell.getShell(Shell.EXECUTOR) { shell ->
            val result = runCatching { shell.newJob().add("id -u").exec() }
            val uid = result.getOrNull()?.takeIf { it.isSuccess }?.out?.lastOrNull()?.trim()
            rootProbeInFlight.set(false)
            if (!shell.isRoot || uid != "0") {
                val detail = result.exceptionOrNull()?.message
                    ?: result.getOrNull()?.err?.joinToString(" ")
                    ?: "shellStatus=${shell.status} uid=${uid ?: "unavailable"}"
                fail("ROOT_PERMISSION_FAIL $detail")
                return@getShell
            }
            rootVerified.set(true)
            LsfgLog.i(TAG, "ROOT_UID uid=0")
            LsfgLog.i(TAG, "ROOT_PERMISSION_OK")
            mainHandler.post {
                if (state != PrivilegedCaptureState.ERROR && state != PrivilegedCaptureState.STOPPING) bind()
            }
        }
    }

    private fun bind() {
        // libsu's RootService.bind enforces main-thread invocation. start() may
        // be called from the foreground service's worker thread (e.g. on a
        // surface-geometry change), so hop to the main looper if we're not
        // already there. Without this the bind throws IllegalStateException and
        // the root capture path silently never arms.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bind() }
            return
        }
        if (!binding.compareAndSet(false, true)) return
        state = PrivilegedCaptureState.BINDING
        everConnected = false
        runCatching {
            val intent = Intent(ctx, RootCaptureService::class.java)
            RootService.bind(intent, connection)
            LsfgLog.i(TAG, "ROOT_BINDING")
        }.onFailure {
            binding.set(false)
            LsfgLog.w(TAG, "RootService.bind failed", it)
            fail("ROOT_BIND_FAIL: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long) {
            fpsFrameCount++
            graphFrameCount++
            try {
                if (!metricsOnly) {
                    NativeBridge.pushFrame(buffer, timestampNs)
                    if (firstFrameToNative.compareAndSet(false, true)) {
                        state = PrivilegedCaptureState.ACTIVE
                        LsfgLog.i(TAG, "ROOT_FIRST_FRAME_TO_NATIVE")
                        activeListener?.invoke()
                    }
                }
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from root failed", t)
                fail("ROOT_ERROR pushFrame: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            fail(message ?: "ROOT_ERROR unknown capture error")
        }

        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            NativeBridge.reportShizukuTiming(timestampNs, frameTimeNs, pacingJitterNs)
            if (frameTimeNs > 0L && pacingJitterNs > frameTimeNs) {
                LsfgLog.w(
                    TAG,
                    "Root pacing spike frame=${frameTimeNs / 1_000_000.0}ms jitter=${pacingJitterNs / 1_000_000.0}ms",
                )
            }
        }
    }

    private fun fail(message: String) {
        if (!errorReported.compareAndSet(false, true)) return
        state = PrivilegedCaptureState.ERROR
        pendingStart = null
        LsfgLog.e(TAG, message)
        errorListener?.onError(message)
    }

    private data class StartArgs(
        val targetPackage: String,
        val width: Int,
        val height: Int,
        val maxFps: Int,
    )

    companion object {
        private const val TAG = "RootCaptureEngine"
    }
}
