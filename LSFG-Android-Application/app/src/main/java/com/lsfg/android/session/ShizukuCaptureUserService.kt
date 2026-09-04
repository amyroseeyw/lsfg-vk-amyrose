package com.lsfg.android.session

import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.Log
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuCaptureUserService : IShizukuCaptureService.Stub() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun startCapture(
        targetUid: Int,
        width: Int,
        height: Int,
        maxFps: Int,
        callback: IShizukuFrameCallback,
    ) {
        stopCapture()
        val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
        running.set(true)
        worker = Thread({
            runCaptureLoop(targetUid, width, height, periodMs, callback)
        }, "lsfg-shizuku-capture").also { it.start() }
    }

    override fun stopCapture() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    override fun describeBackend(): String {
        return "uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"
    }

    override fun destroy() {
        stopCapture()
        System.exit(0)
    }

    private fun runCaptureLoop(
        targetUid: Int,
        width: Int,
        height: Int,
        periodMs: Long,
        callback: IShizukuFrameCallback,
    ) {
        Log.i(
            TAG,
            "SHIZUKU_START uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} " +
                "targetUid=$targetUid sdk=${android.os.Build.VERSION.SDK_INT} serviceVersion=$USER_SERVICE_VERSION",
        )
        val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid, "SHIZUKU") }
            .getOrElse { e ->
                Log.w(TAG, "Unable to initialize privileged capture", e)
                callback.onError("SHIZUKU_ERROR capture init: ${e.message ?: e.javaClass.simpleName}")
                running.set(false)
                return
            }

        var lastFrameNs = 0L
        val targetPeriodNs = periodMs * 1_000_000L
        var firstFrame = true
        while (running.get()) {
            val started = SystemClock.uptimeMillis()
            val hb = runCatching { capture.captureHardwareBuffer() }
                .onFailure {
                    Log.w(TAG, "captureHardwareBuffer failed", it)
                    callback.onError("SHIZUKU_ERROR capture: ${it.message ?: it.javaClass.simpleName}")
                    running.set(false)
                }
                .getOrNull()

            if (hb != null) {
                if (firstFrame) {
                    firstFrame = false
                    Log.i(TAG, "SHIZUKU_FIRST_HARDWAREBUFFER ${hb.width}x${hb.height} fmt=${hb.format}")
                }
                val timestampNs = System.nanoTime()
                val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                val pacingJitterNs = if (frameTimeNs > 0L) kotlin.math.abs(frameTimeNs - targetPeriodNs) else 0L
                lastFrameNs = timestampNs
                try {
                    callback.onFrameMetrics(timestampNs, frameTimeNs, pacingJitterNs)
                    callback.onFrame(hb, timestampNs)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame callback failed", t)
                    callback.onError("SHIZUKU_ERROR callback: ${t.message ?: t.javaClass.simpleName}")
                    running.set(false)
                } finally {
                    runCatching { hb.close() }
                }
            }

            val elapsed = SystemClock.uptimeMillis() - started
            val sleepMs = periodMs - elapsed
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.i(TAG, "SHIZUKU_STOP")
    }


    companion object {
        private const val TAG = "ShizukuUserCapture"

        // Independent from versionCode: Shizuku uses this value to replace a
        // cached UserService after its capture implementation changes.
        const val USER_SERVICE_VERSION = 3
    }
}
