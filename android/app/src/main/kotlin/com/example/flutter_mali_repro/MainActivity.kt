package com.example.flutter_mali_repro

import android.content.Intent
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FlutterActivity() {
  private val channelName = "mali_repro/control"
  private lateinit var channel: MethodChannel
  private var dartReady = false
  private var pendingCommand: Intent? = null
  private var cachedDriverInfo: JSONObject? = null

  override fun configureFlutterEngine(engine: FlutterEngine) {
    super.configureFlutterEngine(engine)
    channel = MethodChannel(engine.dartExecutor.binaryMessenger, channelName)
    channel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
      when (call.method) {
        "ready" -> {
          dartReady = true
          writeReady(call.arguments as? Map<*, *>)
          result.success(true)
          val pending = pendingCommand
          pendingCommand = null
          if (pending != null) dispatchCommand(pending)
        }
        "writeResult" -> {
          writeResult(call.arguments as Map<*, *>)
          result.success(true)
        }
        else -> result.notImplemented()
      }
    }
  }

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    dispatchCommand(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    dispatchCommand(intent)
  }

  private fun dispatchCommand(intent: Intent?) {
    val command = intent?.getStringExtra("mali_command") ?: return
    if (!::channel.isInitialized || !dartReady) {
      pendingCommand = intent
      return
    }
    val label = intent.getStringExtra("mali_label") ?: "unknown"
    val args = mapOf<String, Any?>(
      "label" to label,
      "reuseCardImages" to intent.getBooleanExtra("reuse_card_images", false),
      "tab" to (intent.getStringExtra("mali_tab") ?: if (label.contains("Text")) "Text" else "Cards"),
      "backend" to (intent.getStringExtra("mali_backend") ?: if (intent.getBooleanExtra("enable-impeller", false)) "impeller_gles" else "skia"),
      "pass" to intent.getIntExtra("mali_pass", 1),
      "token" to (intent.getStringExtra("mali_token") ?: ""),
    )
    channel.invokeMethod(command, args, object : MethodChannel.Result {
      override fun success(response: Any?) {
        writeAck(intent, command, response, null)
      }

      override fun error(code: String, message: String?, details: Any?) {
        writeAck(intent, command, null, "$code:${message ?: ""}")
      }

      override fun notImplemented() {
        writeAck(intent, command, null, "not_implemented")
      }
    })
  }

  private fun writeReady(arguments: Map<*, *>?) {
    val ready = JSONObject()
    ready.put("protocol", arguments?.get("protocol") ?: 1)
    ready.put("package", packageName)
    ready.put("pid", android.os.Process.myPid())
    ready.put("ready_us", System.nanoTime() / 1000L)
    writeJsonFile("ready.json", ready)
    android.util.Log.i("MaliReproApp", "READY protocol=1 pid=${android.os.Process.myPid()}")
  }

  private fun writeAck(intent: Intent, command: String, response: Any?, error: String?) {
    val ack = JSONObject()
    ack.put("token", intent.getStringExtra("mali_token") ?: "")
    ack.put("command", command)
    ack.put("ack_us", System.nanoTime() / 1000L)
    if (error == null) ack.put("response", response?.toString() ?: "null") else ack.put("error", error)
    writeJsonFile("command-ack.json", ack)
  }

  private fun writeJsonFile(name: String, value: JSONObject) {
    val dir = filesDir
    File(dir, name).writeText(value.toString())
  }

  private fun writeResult(arguments: Map<*, *>) {
    val frames = JSONArray()
    val frameList = arguments["frames"] as? List<*> ?: emptyList<Any>()
    for (frame in frameList) {
      val map = frame as? Map<*, *> ?: continue
      val obj = JSONObject()
      obj.put("build_us", (map["build_us"] as? Number)?.toLong() ?: 0L)
      obj.put("raster_us", (map["raster_us"] as? Number)?.toLong() ?: 0L)
      obj.put("vsync_us", (map["vsync_us"] as? Number)?.toLong() ?: 0L)
      obj.put("timestamp_us", (map["timestamp_us"] as? Number)?.toLong() ?: 0L)
      frames.put(obj)
    }
    val run = JSONObject()
    run.put("label", arguments["label"] ?: "unknown")
    run.put("token", arguments["token"] ?: "")
    run.put("backend", arguments["backend"] ?: "skia")
    run.put("tab", arguments["tab"] ?: "Cards")
    run.put("pass", (arguments["pass"] as? Number)?.toInt() ?: 1)
    run.put("begin_us", (arguments["begin_us"] as? Number)?.toLong() ?: 0L)
    run.put("end_us", (arguments["end_us"] as? Number)?.toLong() ?: 0L)
    run.put("wall_us", System.nanoTime() / 1000L)
    (arguments["card_images"] as? Map<*, *>)?.let { run.put("card_images", JSONObject(it)) }
    run.put("frames", frames)
    run.put("auxiliary_egl_gles", cachedDriverInfo ?: auxiliaryDriverInfo().also { cachedDriverInfo = it })
    val file = File(filesDir, "results.json")
    val root = if (file.exists()) {
      try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
    } else JSONObject()
    val runs = root.optJSONArray("runs") ?: JSONArray().also { root.put("runs", it) }
    runs.put(run)
    file.writeText(root.toString())
  }

  // This is an auxiliary EGL context. It identifies the device driver, not the
  // exact Flutter engine context, and is labeled as such in the output.
  private fun auxiliaryDriverInfo(): JSONObject {
    val info = JSONObject()
    info.put("context", "auxiliary_native_egl")
    var display: EGLDisplay? = null
    var surface: EGLSurface? = null
    var context: EGLContext? = null
    try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      val versions = IntArray(2)
      if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return info
      val configs = arrayOfNulls<EGLConfig>(1)
      val count = IntArray(1)
      val attrs = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_NONE,
      )
      if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)) return info
      context = EGL14.eglCreateContext(
        display, configs[0], EGL14.EGL_NO_CONTEXT,
        intArrayOf(0x3098, 2, EGL14.EGL_NONE), 0,
      )
      surface = EGL14.eglCreatePbufferSurface(
        display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
      )
      if (context == null || surface == null || !EGL14.eglMakeCurrent(display, surface, surface, context)) return info
      info.put("gl_vendor", GLES20.glGetString(GLES20.GL_VENDOR) ?: "")
      info.put("gl_renderer", GLES20.glGetString(GLES20.GL_RENDERER) ?: "")
      info.put("gl_version", GLES20.glGetString(GLES20.GL_VERSION) ?: "")
      info.put("glsl_version", GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "")
      info.put("egl_version", EGL14.eglQueryString(display, EGL14.EGL_VERSION) ?: "")
      info.put("egl_vendor", EGL14.eglQueryString(display, EGL14.EGL_VENDOR) ?: "")
      info.put("egl_extensions", EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: "")
    } catch (e: Exception) {
      info.put("error", e.toString())
    } finally {
      if (display != null) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != null) EGL14.eglDestroySurface(display, surface)
        if (context != null) EGL14.eglDestroyContext(display, context)
        // EGL_DEFAULT_DISPLAY is shared with Flutter in this process.
        // Keep it initialized until process teardown so engine contexts on
        // other threads remain valid.
      }
    }
    return info
  }
}
