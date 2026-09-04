package com.lsfg.android.session

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle shared by the two privileged frame sources. ACTIVE is reached only
 * after a real HardwareBuffer has been handed to the native input. */
internal enum class PrivilegedCaptureState {
    IDLE, STARTING, WAITING_PERMISSION, BINDING, CAPTURE_STARTING, ACTIVE, ERROR, STOPPING,
}

/**
 * Calls the hidden [android.window.ScreenCapture] / [android.view.SurfaceControl] API
 * via reflection to capture a single frame from a specific app UID.
 *
 * Requires at minimum shell UID (Shizuku) or root UID to call captureDisplay() with
 * a UID filter — both satisfy this requirement.
 */
internal class PrivilegedScreenCapture(
    width: Int,
    height: Int,
    targetUid: Int,
    private val backendName: String,
) {
    private val captureDisplay: Method
    private val args: Any
    private val getHardwareBuffer: Method
    private val captureCreatedLogged = AtomicBoolean(false)

    init {
        if (backendName == "SHIZUKU") {
            Log.i(
                TAG,
                "SHIZUKU_CAPTURE_PROCESS uid=${android.os.Process.myUid()} " +
                    "pid=${android.os.Process.myPid()} classLoader=${javaClass.classLoader?.javaClass?.name}",
            )
        }
        val classNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ moved the capture argument classes to android.window.
            // SurfaceControl$DisplayCaptureArgs no longer exists on this branch.
            listOf("android.window.ScreenCapture")
        } else {
            // API 30-33 expose this hidden capture contract through SurfaceControl.
            listOf("android.view.SurfaceControl", "android.window.ScreenCapture")
        }
        val failures = mutableListOf<String>()
        val backend = classNames.firstNotNullOfOrNull { className ->
            Log.i(TAG, "$backendName CAPTURE_CANDIDATE api=${Build.VERSION.SDK_INT} class=$className")
            runCatching { buildBackend(className, width, height, targetUid) }
                .onFailure {
                    val reason = "${captureFailureKind(it)}: ${it.message ?: it.javaClass.simpleName}"
                    failures += "$className: $reason"
                    Log.w(TAG, "$backendName CAPTURE_CANDIDATE_REJECTED class=$className reason=$reason", it)
                }
                .getOrNull()
        }
            ?: throw IllegalStateException(
                "No privileged ScreenCapture backend with UID filter is available: " +
                    failures.joinToString("; "),
            )
        captureDisplay = backend.captureDisplay
        args = backend.args
        getHardwareBuffer = backend.getHardwareBuffer
        Log.i(TAG, "$backendName CAPTURE_API_SELECTED api=${Build.VERSION.SDK_INT} captureApi=${backend.apiClass}")
    }

    fun captureHardwareBuffer(): HardwareBuffer {
        val screenshot = try {
            captureDisplay.invoke(null, args)
                ?: throw CaptureResolutionException(
                    "CAPTURE_RETURNED_NULL",
                    "$backendName CAPTURE_RETURNED_NULL: captureDisplay returned null",
                )
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw CaptureResolutionException(
                captureFailureKind(cause),
                "$backendName CAPTURE_FAILED: ${cause.message ?: cause.javaClass.simpleName}",
                cause,
            )
        }
        val buffer = try {
            getHardwareBuffer.invoke(screenshot) as? HardwareBuffer
                ?: throw CaptureResolutionException(
                    "CAPTURE_RETURNED_NULL",
                    "$backendName CAPTURE_RETURNED_NULL: screenshot has no HardwareBuffer",
                )
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw CaptureResolutionException(
                captureFailureKind(cause),
                "$backendName CAPTURE_BUFFER_FAILED: ${cause.message ?: cause.javaClass.simpleName}",
                cause,
            )
        }
        if (captureCreatedLogged.compareAndSet(false, true)) {
            Log.i(TAG, "$backendName CAPTURE_CREATE_OK api=${Build.VERSION.SDK_INT}")
        }
        return buffer
    }

    private fun buildBackend(
        captureClassName: String,
        width: Int,
        height: Int,
        targetUid: Int,
    ): Backend {
        val captureClass = resolveClass("CAPTURE_CLASS", captureClassName)
        val builderClass = resolveClass("DISPLAY_BUILDER", "$captureClassName\$DisplayCaptureArgs\$Builder")
        val argsClass = resolveClass("DISPLAY_ARGS", "$captureClassName\$DisplayCaptureArgs")
        val screenshotClass = resolveClass("SCREENSHOT_BUFFER", "$captureClassName\$ScreenshotHardwareBuffer")
        val builder = createDisplayCaptureArgsBuilder(captureClassName, builderClass)
        invokeOptional(builderClass, builder, "setSize", intArrayOf(width, height))
        invokeOptional(builderClass, builder, "setPixelFormat", intArrayOf(PixelFormat.RGBA_8888))
        if (!invokeSetUid(builderClass, builder, targetUid.toLong())) {
            throw IllegalStateException("UID filter missing in $captureClassName")
        }
        val builtArgs = findMethod(builderClass, "build", emptyArray<Class<*>>()).invoke(builder)
            ?: throw IllegalStateException("$captureClassName args build returned null")
        Log.i(TAG, "$backendName TARGET_FILTER_OK uid=$targetUid")
        return Backend(
            captureDisplay = findSingleArgMethod(captureClass, "captureDisplay", argsClass),
            args = builtArgs,
            getHardwareBuffer = findNoArgMethod(screenshotClass, "getHardwareBuffer"),
            apiClass = captureClassName,
        )
    }

    private fun createDisplayCaptureArgsBuilder(captureClassName: String, builderClass: Class<*>): Any {
        val constructors = builderClass.declaredConstructors
        val binderConstructor = constructors.firstOrNull { ctor ->
            ctor.parameterTypes.size == 1 && IBinder::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }
        if (binderConstructor != null) {
            val displayToken = findDisplayToken()
            return runCatching {
                binderConstructor.isAccessible = true
                Log.i(TAG, "$backendName BUILDER_RESOLVED class=$captureClassName signature=(android.os.IBinder)")
                binderConstructor.newInstance(displayToken)
            }.getOrElse { cause ->
                throw CaptureResolutionException(
                    "CONSTRUCTOR_NOT_FOUND",
                    "$captureClassName DisplayCaptureArgs.Builder(IBinder) invocation failed",
                    cause,
                )
            }
        }

        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder using logical display id ${Display.DEFAULT_DISPLAY}")
                return ctor.newInstance(Display.DEFAULT_DISPLAY)
            }.onFailure { Log.w(TAG, "$captureClassName display-id builder unavailable", it) }
        }

        constructors.filter { ctor ->
            ctor.parameterTypes.isEmpty()
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder using no-arg constructor")
                return ctor.newInstance()
            }.onFailure { Log.w(TAG, "$captureClassName no-arg builder unavailable", it) }
        }

        throw CaptureResolutionException(
            "BUILDER_NOT_FOUND",
            "No usable $captureClassName DisplayCaptureArgs.Builder constructor: " +
                constructors.joinToString { ctor ->
                    ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                }
        )
    }

    private fun findDisplayToken(): IBinder {
        // This is the Android 15 framework path: ScreenCapture's Builder takes
        // the internal physical display token. Resolve it first; the older
        // DisplayManager reflection below remains only as an OEM fallback.
        Log.i(TAG, "$backendName DISPLAY_TOKEN_PATH primary=SurfaceControl.getInternalDisplayToken")
        findDisplayTokenFromSurfaceControl("getInternalDisplayToken")?.let { return it }
        Log.i(TAG, "$backendName DISPLAY_TOKEN_PATH fallback=SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken")
        findDisplayTokenFromSurfaceControl("getPhysicalDisplayToken")?.let { return it }
        findDisplayTokenFromDisplayManagerGlobal()?.let { return it }
        findDisplayTokenFromDisplayService()?.let { return it }

        for (className in listOf("android.view.DisplayControl")) {
            val cls = runCatching { Class.forName(className) }
                .onFailure { Log.w(TAG, "Display token class unavailable: $className", it) }
                .getOrNull() ?: continue

            findDisplayTokenFromDisplayControlClass(className, cls)?.let { return it }
        }
        throw CaptureResolutionException("DISPLAY_TOKEN_NOT_FOUND", "No display token API is available")
    }

    private fun findDisplayTokenFromSurfaceControl(methodName: String): IBinder? {
        val cls = runCatching { Class.forName("android.view.SurfaceControl") }
            .getOrElse { cause ->
                Log.w(TAG, "$backendName CLASS_NOT_FOUND android.view.SurfaceControl", cause)
                return null
            }
        return runCatching {
            if (methodName == "getInternalDisplayToken") {
                val token = findNoArgMethod(cls, methodName).invoke(null) as? IBinder
                    ?: throw IllegalStateException("$methodName returned null")
                Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=SurfaceControl.$methodName")
                token
            } else {
                val ids = findNoArgMethod(cls, "getPhysicalDisplayIds").invoke(null) as? LongArray
                    ?: throw IllegalStateException("getPhysicalDisplayIds returned null")
                val method = findMethod(cls, methodName, arrayOf(Long::class.javaPrimitiveType!!))
                // AOSP's getInternalDisplayToken() itself selects index zero of
                // getPhysicalDisplayIds(); preserve that deterministic primary
                // display selection instead of silently falling through to an
                // arbitrary external display.
                val physicalId = ids.firstOrNull()
                    ?: throw IllegalStateException("getPhysicalDisplayIds returned empty array")
                val token = method.invoke(null, physicalId) as? IBinder
                    ?: throw IllegalStateException("$methodName returned null for primary physical display")
                Log.i(
                    TAG,
                    "$backendName DISPLAY_TOKEN_OK source=SurfaceControl.$methodName physicalId=$physicalId",
                )
                token
            }
        }.onFailure { cause ->
            Log.w(TAG, "$backendName DISPLAY_TOKEN_REJECTED source=SurfaceControl.$methodName kind=${captureFailureKind(cause)} reason=${cause.message ?: cause.javaClass.simpleName}", cause)
        }.getOrNull()
    }

    private fun findDisplayTokenFromDisplayManagerGlobal(): IBinder? {
        return runCatching {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            runCatching {
                globalClass.getMethod("getDisplayToken", Int::class.javaPrimitiveType)
                    .invoke(global, Display.DEFAULT_DISPLAY) as? IBinder
            }.getOrNull()?.let { token ->
                Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=DisplayManagerGlobal.getDisplayToken")
                return@runCatching token
            }
            runCatching {
                val dmField = globalClass.declaredFields.firstOrNull { it.name == "mDm" }
                    ?: return@runCatching null
                dmField.isAccessible = true
                val dm = dmField.get(global) ?: return@runCatching null
                dm.javaClass.methods.firstOrNull { method ->
                    method.name == "getDisplayToken" &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }?.invoke(dm, Display.DEFAULT_DISPLAY) as? IBinder
            }.getOrNull()?.let { token ->
                Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=DisplayManagerGlobal.mDm.getDisplayToken")
                return@runCatching token
            }
            val info = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(global, Display.DEFAULT_DISPLAY)
                ?: return@runCatching null
            val tokenField = info.javaClass.declaredFields.firstOrNull { field ->
                IBinder::class.java.isAssignableFrom(field.type) &&
                    field.name.contains("token", ignoreCase = true)
            } ?: return@runCatching null
            tokenField.isAccessible = true
            (tokenField.get(info) as? IBinder)
                ?.also { Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=DisplayManagerGlobal.${tokenField.name}") }
        }.onFailure { Log.w(TAG, "$backendName DISPLAY_TOKEN_FAIL source=DisplayManagerGlobal", it) }
            .getOrNull()
    }

    private fun findDisplayTokenFromDisplayService(): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val displayBinder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "display") as? IBinder
                ?: return@runCatching null
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val displayManager = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, displayBinder)
                ?: return@runCatching null
            displayManager.javaClass.methods.firstOrNull { method ->
                method.name == "getDisplayToken" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.invoke(displayManager, Display.DEFAULT_DISPLAY) as? IBinder
        }.onSuccess {
            if (it != null) Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=IDisplayManager.getDisplayToken")
        }.onFailure { Log.w(TAG, "$backendName DISPLAY_TOKEN_FAIL source=IDisplayManager", it) }
            .getOrNull()
    }

    private fun findDisplayTokenFromDisplayControlClass(className: String, cls: Class<*>): IBinder? {
        runCatching {
            val ids = cls.methods.firstOrNull { it.name == "getPhysicalDisplayIds" && it.parameterTypes.isEmpty() }
                ?.invoke(null) as? LongArray
                ?: throw NoSuchMethodException("$className.getPhysicalDisplayIds()")
            val tokenMethod = cls.methods.firstOrNull { method ->
                method.name == "getPhysicalDisplayToken" &&
                    method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            } ?: throw NoSuchMethodException("$className.getPhysicalDisplayToken(long)")
            for (id in ids) {
                (tokenMethod.invoke(null, id) as? IBinder)?.let { token ->
                    Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=$className.getPhysicalDisplayToken")
                    return token
                }
            }
        }.onFailure { Log.w(TAG, "$className physical display token unavailable", it) }

        runCatching {
            cls.methods.firstOrNull { it.name == "getInternalDisplayToken" && it.parameterTypes.isEmpty() }
                ?.invoke(null) as? IBinder
                ?: throw NoSuchMethodException("$className.getInternalDisplayToken()")
        }.onSuccess {
            Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=$className.getInternalDisplayToken")
            return it
        }.onFailure { Log.w(TAG, "$className internal display token unavailable", it) }

        runCatching {
            cls.methods.firstOrNull { method ->
                method.name == "getBuiltInDisplay" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.invoke(null, 0) as? IBinder
                ?: throw NoSuchMethodException("$className.getBuiltInDisplay(int)")
        }.onSuccess {
            Log.i(TAG, "$backendName DISPLAY_TOKEN_OK source=$className.getBuiltInDisplay")
            return it
        }.onFailure { Log.w(TAG, "$className built-in display token unavailable", it) }

        return null
    }

    private fun invokeSetUid(builderClass: Class<*>, builder: Any, uid: Long): Boolean {
        val methods = allMethods(builderClass).filter { it.name == "setUid" && it.parameterTypes.size == 1 }
        for (method in methods) {
            runCatching {
                makeAccessible(method)
                when (method.parameterTypes[0]) {
                    Long::class.javaPrimitiveType -> method.invoke(builder, uid)
                    Int::class.javaPrimitiveType -> method.invoke(builder, uid.toInt())
                    else -> return@runCatching
                }
                Log.i(TAG, "$backendName CAPTURE_UID_FILTER method=${method.declaringClass.name}.${method.name}(${method.parameterTypes[0].name}) uid=$uid")
                return true
            }.onFailure {
                Log.w(TAG, "$backendName CAPTURE_UID_FILTER_REJECTED method=${method.declaringClass.name}.${method.name} reason=${it.message ?: it.javaClass.simpleName}", it)
            }
        }
        Log.w(TAG, "$backendName CAPTURE_UID_FILTER_MISSING builder=$builderClass methods=${allMethods(builderClass).filter { it.name == "setUid" }.joinToString { it.toGenericString() }}")
        return false
    }

    private fun invokeOptional(builderClass: Class<*>, builder: Any, name: String, args: IntArray) {
        val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
        runCatching {
            findMethod(builderClass, name, types).invoke(builder, *args.toTypedArray())
        }.onFailure {
            Log.w(TAG, "$backendName CAPTURE_OPTIONAL_REJECTED method=$name reason=${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun findSingleArgMethod(cls: Class<*>, name: String, argClass: Class<*>): Method {
        return allMethods(cls)
            .firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(argClass)
            }
            ?.also(::makeAccessible)
            ?: throw NoSuchMethodException("${cls.name}.$name(${argClass.name})")
    }

    private fun findNoArgMethod(cls: Class<*>, name: String): Method {
        return allMethods(cls)
            .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
            ?.also(::makeAccessible)
            ?: throw NoSuchMethodException("${cls.name}.$name()")
    }

    /**
     * API 35 keeps setUid(long) on the package-private CaptureArgs.Builder.
     * Class.getMethod() finds that inherited public method, but it is not invokable
     * until the member itself is made accessible.  Walk the hierarchy explicitly so
     * OEM class layouts produce a precise candidate error instead of a generic
     * "UID filter missing" result.
     */
    private fun findMethod(cls: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method =
        allMethods(cls).firstOrNull { method ->
            method.name == name && method.parameterTypes.contentEquals(parameterTypes)
        }?.also(::makeAccessible)
            ?: throw NoSuchMethodException("${cls.name}.$name(${parameterTypes.joinToString { it.name }})")

    private fun allMethods(cls: Class<*>): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredMethods.forEach { method ->
                methods.putIfAbsent(method.toGenericString(), method)
            }
            current = current.superclass
        }
        cls.methods.forEach { method -> methods.putIfAbsent(method.toGenericString(), method) }
        return methods.values.toList()
    }

    private fun makeAccessible(method: Method) {
        method.isAccessible = true
    }

    private fun resolveClass(role: String, className: String): Class<*> =
        runCatching { Class.forName(className) }
            .onSuccess { Log.i(TAG, "$backendName CLASS_RESOLVED role=$role class=$className") }
            .getOrElse { cause ->
                throw CaptureResolutionException("CLASS_NOT_FOUND", "$role $className", cause)
            }

    private fun captureFailureKind(error: Throwable): String = when (val cause =
        if (error is InvocationTargetException) error.targetException ?: error else error
    ) {
        is CaptureResolutionException -> cause.kind
        is ClassNotFoundException -> "CLASS_NOT_FOUND"
        is NoSuchMethodException -> "METHOD_NOT_FOUND"
        is IllegalAccessException -> "ILLEGAL_ACCESS"
        is SecurityException -> "SECURITY_EXCEPTION"
        else -> when (cause.javaClass.name) {
            "android.os.DeadObjectException" -> "SERVICE_DIED"
            else -> "HIDDEN_API_BLOCKED"
        }
    }

    private class CaptureResolutionException(
        val kind: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private data class Backend(
        val captureDisplay: Method,
        val args: Any,
        val getHardwareBuffer: Method,
        val apiClass: String,
    )

    companion object {
        private const val TAG = "PrivilegedCapture"
    }
}
