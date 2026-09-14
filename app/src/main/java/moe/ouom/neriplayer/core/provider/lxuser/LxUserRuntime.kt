package moe.ouom.neriplayer.core.provider.lxuser

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.Closeable
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val HTTP_TIMEOUT_MS = 13_000L
/**
 * Default ceiling for one action when the caller does not set one. Sources fire a
 * telemetry request before the real one, so this has to cover two round trips.
 */
private const val ACTION_DRAIN_TIMEOUT_MS = 12_000L
private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
private const val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8"

/**
 * Client for the script-facing `lx.request` bridge. Per-request call timeouts are
 * applied by [LxUserRuntime] through `newBuilder()`, so only the socket-level
 * defaults live here.
 */
private val lxHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

data class LxUserScript(
    val source: String,
    val metadata: LxUserScriptMetadata = LxUserScriptMetadata.parse(source),
)

/** What a script declares through `lx.send(lx.EVENT_NAMES.inited, info)`. */
data class LxUserSourceCapability(
    val actions: List<String> = emptyList(),
    val qualitys: List<String> = emptyList(),
)

/**
 * Self-contained LX Music user API runtime.
 *
 * Exposes the standard JavaScript surface that real LX user sources depend on:
 * - globalThis.lx.request(url, options, callback)
 * - globalThis.lx.on(globalThis.lx.EVENT_NAMES.request, async handler)
 * - globalThis.lx.send(globalThis.lx.EVENT_NAMES.inited, info)
 * - globalThis.lx.utils.crypto / buffer
 * - globalThis.lx.currentScriptInfo, version, env
 * - console.log / warn / error
 * - setTimeout
 * - Promise draining so async handlers resolve before the caller continues.
 */
class LxUserRuntime(
    private val httpClient: OkHttpClient = lxHttpClient,
) : Closeable {
    private val context = createContext()
    private val requestHandlers = mutableListOf<JSFunction>()
    private val sourceQualities = mutableMapOf<String, List<String>>()
    private val sourceCapabilities = mutableMapOf<String, LxUserSourceCapability>()
    private val timers = ConcurrentHashMap<Int, Pair<Long, JSCallFunction>>()
    private var nextTimerId = 1
    private val closed = AtomicBoolean(false)

    private data class HttpResponse(
        val callback: JSFunction,
        val error: String?,
        val result: Map<String, Any?>?,
        val body: Any?,
    )

    private val pendingHttpResponses = ConcurrentLinkedQueue<HttpResponse>()
    private val drainSignal = Object()

    init {
        installGlobals()
    }

    fun load(script: LxUserScript): LxUserScriptMetadata {
        val info = script.metadata
        NPLogger.d(TAG, "load start name=${info.name.orEmpty()} bytes=${script.source.toByteArray().size}")
        context.evaluate("var module = { exports: {} }; var exports = module.exports;", "lx-module.js")
        context.globalObject.getJSObjectProperty("lx")?.let { lx ->
            lx.getJSObjectProperty("currentScriptInfo")?.apply {
                setProperty("name", info.name.orEmpty())
                setProperty("version", info.version.orEmpty())
                setProperty("author", info.author.orEmpty())
                setProperty("description", info.description.orEmpty())
                setProperty("homepage", info.homepage.orEmpty())
                setProperty("rawScript", script.source)
            }
        }
        context.evaluate(script.source, "lx-user.js")
        // v5 sources commonly fetch remote configuration before registering the
        // request listener. Drain that initialization before the first action.
        drainScriptInitialization()
        NPLogger.d(TAG, "load done name=${info.name.orEmpty()} handlers=${requestHandlers.size} sources=${sourceCapabilities}")
        return info
    }

    /** Invokes a V5 action (musicUrl, lyric, or pic) and returns its raw result. */
    fun callAction(
        action: String,
        args: Map<String, Any?>,
        timeoutMs: Long = ACTION_DRAIN_TIMEOUT_MS,
    ): Any? {
        require(action == "musicUrl" || action == "lyric" || action == "pic") {
            "Unsupported LX action: $action"
        }
        val source = args["source"]?.toString() ?: "kw"
        val info = mapOf(
            "type" to (args["type"] ?: "128k"),
            "musicInfo" to (args["musicInfo"] ?: args),
        )

        val resultRef = AtomicReference<Any?>()
        val errorRef = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        // When the runtime is reused across songs it can still be holding the reply to
        // an earlier action whose drain budget ran out - the HTTP thread keeps going
        // after the caller gives up. Dropping it here stops a late answer from being
        // handed to this action.
        pendingHttpResponses.clear()

        val requestArg = createJsObject(mapOf("source" to source, "action" to action, "info" to info))
        // LX keeps a single request handler: a second lx.on('request') replaces the
        // first one. Use the newest registration so re-registering scripts win.
        val handler = requestHandlers.lastOrNull()
        NPLogger.d(TAG, "action start source=$source quality=${args["type"]} handler=${handler != null} export=${handler == null}")
        val returned = if (handler != null) {
            handler.call(requestArg)
        } else {
            val global = context.globalObject
            val globalFunction = global.getJSFunctionProperty("musicUrl")
            val module = global.getJSObjectProperty("module")
            val exported = module?.getJSObjectProperty("exports")
            val function = globalFunction ?: exported?.getJSFunctionProperty("musicUrl")
            if (function == null) {
                throw IllegalStateException("LX request handler is not registered")
            }
            val musicInfo = (args["musicInfo"] as? Map<*, *>)
                ?.let { createJsObject(it.toStringKeyedMap()) }
                ?: createJsObject(args)
            function.call(musicInfo, args["type"]?.toString() ?: "128k")
        }
        settle(returned, onSuccess = { value ->
            resultRef.set(value)
            done.countDown()
        }, onError = { error ->
            errorRef.set(error)
            done.countDown()
        })

        drainUntil(done, timeoutMs)
        errorRef.get()?.let { throw it }
        val resolved = resultRef.get()
        if (action != "musicUrl") {
            val hostResolved = if (resolved is QuickJSObject) resolved.toMap() else resolved
            NPLogger.d(TAG, "action done source=$source action=$action result=${responseShape(hostResolved)}")
            return hostResolved
        }
        val url = if (resolved is String) resolved else urlFrom(resolved)
        NPLogger.d(TAG, "action done source=$source result=${if (url.isNullOrBlank()) "empty" else "url"}")
        return url
    }

    /** Returns the best quality this source declared through lx.send(inited, ...). */
    fun qualityFor(source: String, requested: String): String {
        val supported = sourceQualities[source].orEmpty()
        if (supported.isEmpty() || requested in supported) return requested
        val requestedIndex = QUALITY_ORDER.indexOf(requested).takeIf { it >= 0 } ?: QUALITY_ORDER.lastIndex
        return QUALITY_ORDER.asReversed()
            .firstOrNull { it in supported && QUALITY_ORDER.indexOf(it) <= requestedIndex }
            ?: supported.firstOrNull()
            ?: requested
    }

    /** Sources the script announced through `lx.send('inited', ...)`. */
    fun declaredSources(): Map<String, LxUserSourceCapability> = sourceCapabilities.toMap()

    /**
     * Whether the script announced support for [action] on [source]. Scripts that
     * never announce anything are treated as supporting everything so that older
     * sources keep working.
     */
    fun supports(source: String, action: String): Boolean {
        val capability = sourceCapabilities[source] ?: return true
        return capability.actions.isEmpty() || action in capability.actions
    }

    fun sentEvents(): List<Pair<String, Any?>> = emptyList()

    private fun installGlobals() {
        val console = context.createNewJSObject()
        listOf("log", "info", "warn", "error", "debug", "group", "groupEnd", "table", "time", "timeEnd").forEach { name ->
            console.setProperty(name, JSCallFunction { null })
        }
        context.globalObject.setProperty("console", console)

        context.globalObject.setProperty("setTimeout", JSCallFunction { args ->
            val callback = args.firstOrNull() as? JSFunction ?: return@JSCallFunction 0
            val delayMs = (args.getOrNull(1) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
            val params = args.drop(2)
            val id = nextTimerId++
            timers[id] = (System.currentTimeMillis() + delayMs) to JSCallFunction { callback.call(*params.toTypedArray()) }
            id
        })
        context.globalObject.setProperty("clearTimeout", JSCallFunction { args ->
            (args.firstOrNull() as? Number)?.toInt()?.let(timers::remove)
            null
        })

        val lx = context.createNewJSObject()
        val eventNames = context.createNewJSObject().apply {
            setProperty("request", "request")
            setProperty("inited", "inited")
            setProperty("updateAlert", "updateAlert")
        }
        lx.setProperty("EVENT_NAMES", eventNames)
        lx.setProperty("request", JSCallFunction { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            val options = args.getOrNull(1)
            val callback = args.getOrNull(2) as? JSFunction
            executeHttpRequest(url, options, callback)
        })
        lx.setProperty("on", JSCallFunction { args ->
            val event = args.getOrNull(0)?.toString().orEmpty()
            val handler = args.getOrNull(1) as? JSFunction
            if (event == "request" && handler != null) requestHandlers.add(handler)
            NPLogger.d(TAG, "event on name=$event registered=${handler != null} total=${requestHandlers.size}")
            context.evaluate("Promise.resolve()")
        })
        lx.setProperty("send", JSCallFunction { args ->
            val event = args.firstOrNull()?.toString().orEmpty()
            when (event) {
                "inited" -> recordSourceInfo(args.getOrNull(1))
                "updateAlert" -> NPLogger.i(TAG, "event updateAlert received")
            }
            NPLogger.d(TAG, "event send name=$event")
            context.evaluate("Promise.resolve()")
        })
        lx.setProperty("utils", createUtils())
        lx.setProperty("currentScriptInfo", context.createNewJSObject())
        lx.setProperty("version", "2.0.0")
        lx.setProperty("env", "mobile")
        context.globalObject.setProperty("lx", lx)

        installBuiltins()
        lockdownSandbox()
    }

    /**
     * Adds the JS built-ins that QuickJS does not always ship but that LX Music
     * user sources rely on: UTF-8 codecs, base64 helpers and `fetch`.
     */
    private fun installBuiltins() {
        context.evaluate(
            """
            (function() {
              'use strict'
              var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
              function utf8Bytes(input) {
                var text = String(input == null ? '' : input)
                var out = []
                for (var i = 0; i < text.length; i++) {
                  var code = text.charCodeAt(i)
                  if (code < 0x80) { out.push(code) }
                  else if (code < 0x800) { out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f)) }
                  else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
                    var next = text.charCodeAt(++i)
                    var point = 0x10000 + (((code & 0x3ff) << 10) | (next & 0x3ff))
                    out.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 0x3f), 0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f))
                  } else { out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f)) }
                }
                return out
              }
              function utf8String(bytes) {
                var out = []
                for (var i = 0; i < bytes.length; i++) {
                  var byte = bytes[i] & 0xff
                  if (byte < 0x80) { out.push(String.fromCharCode(byte)) }
                  else if (byte < 0xe0) { out.push(String.fromCharCode(((byte & 0x1f) << 6) | (bytes[++i] & 0x3f))) }
                  else if (byte < 0xf0) { out.push(String.fromCharCode(((byte & 0x0f) << 12) | ((bytes[++i] & 0x3f) << 6) | (bytes[++i] & 0x3f))) }
                  else {
                    var point = ((byte & 0x07) << 18) | ((bytes[++i] & 0x3f) << 12) | ((bytes[++i] & 0x3f) << 6) | (bytes[++i] & 0x3f)
                    point -= 0x10000
                    out.push(String.fromCharCode(0xd800 + (point >> 10), 0xdc00 + (point & 0x3ff)))
                  }
                }
                return out.join('')
              }
              if (typeof globalThis.TextEncoder === 'undefined') {
                globalThis.TextEncoder = function TextEncoder() {}
                globalThis.TextEncoder.prototype.encode = function(value) { return new Uint8Array(utf8Bytes(value)) }
              }
              if (typeof globalThis.TextDecoder === 'undefined') {
                globalThis.TextDecoder = function TextDecoder(encoding) { this.encoding = encoding || 'utf-8' }
                globalThis.TextDecoder.prototype.decode = function(value) { return value ? utf8String(value) : '' }
              }
              if (typeof globalThis.btoa === 'undefined') {
                globalThis.btoa = function(value) {
                  var bytes = utf8Bytes(value)
                  var out = ''
                  for (var i = 0; i < bytes.length; i += 3) {
                    var b0 = bytes[i], b1 = bytes[i + 1], b2 = bytes[i + 2]
                    out += B64[b0 >> 2]
                    out += B64[((b0 & 0x03) << 4) | ((b1 === undefined ? 0 : b1) >> 4)]
                    out += b1 === undefined ? '=' : B64[((b1 & 0x0f) << 2) | ((b2 === undefined ? 0 : b2) >> 6)]
                    out += b2 === undefined ? '=' : B64[b2 & 0x3f]
                  }
                  return out
                }
              }
              if (typeof globalThis.atob === 'undefined') {
                globalThis.atob = function(value) {
                  var text = String(value).replace(/[^A-Za-z0-9+/]/g, '')
                  var bytes = []
                  for (var i = 0; i < text.length; i += 4) {
                    var c0 = B64.indexOf(text[i]), c1 = B64.indexOf(text[i + 1])
                    var c2 = B64.indexOf(text[i + 2]), c3 = B64.indexOf(text[i + 3])
                    if (c1 >= 0) bytes.push((c0 << 2) | (c1 >> 4))
                    if (c2 >= 0) bytes.push(((c1 & 0x0f) << 4) | (c2 >> 2))
                    if (c3 >= 0) bytes.push(((c2 & 0x03) << 6) | c3)
                  }
                  return utf8String(bytes)
                }
              }
              if (typeof globalThis.fetch === 'undefined' && typeof globalThis.lx !== 'undefined') {
                globalThis.fetch = function(input, init) {
                  return new Promise(function(resolve, reject) {
                    var url = typeof input === 'string' ? input : (input && input.url) || ''
                    var options = init || {}
                    globalThis.lx.request(url, {
                      method: options.method || 'get',
                      headers: options.headers || {},
                      body: options.body,
                      binary: options.binary === true
                    }, function(err, resp, body) {
                      if (err) { reject(new Error((err && err.message) || String(err) || 'fetch failed')); return }
                      var headers = (resp && resp.headers) || {}
                      resolve({
                        ok: resp.statusCode >= 200 && resp.statusCode < 300,
                        status: resp.statusCode,
                        statusText: resp.statusMessage || '',
                        url: resp.url || url,
                        headers: {
                          get: function(name) {
                            var target = String(name).toLowerCase()
                            for (var key in headers) { if (String(key).toLowerCase() === target) return headers[key] }
                            return null
                          }
                        },
                        text: function() { return Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)) },
                        json: function() { return Promise.resolve(typeof body === 'string' ? JSON.parse(body) : body) }
                      })
                    })
                  })
                }
              }
            })()
            """.trimIndent(),
            "lx-builtins.js",
        )
    }

    /**
     * LX Music runs user scripts in a bare QuickJS context and obfuscated sources
     * legitimately use `eval` / `new Function` to unpack themselves, so dynamic code
     * execution stays available. Every native bridge is still removed and `lx`
     * stays frozen: a script can only reach the network through `lx.request`.
     */
    private fun lockdownSandbox() {
        context.evaluate(
            """
            (function() {
              'use strict'
              // Remove dangerous globals if present.
              delete globalThis.java
              delete globalThis.Java
              delete globalThis.JNI
              delete globalThis.importClass
              delete globalThis.importPackage

              // Make the LX object non-writable so scripts cannot replace it.
              try {
                Object.defineProperty(globalThis, 'lx', {
                  value: globalThis.lx,
                  writable: false,
                  configurable: false,
                  enumerable: true
                })
              } catch (e) {}
            })()
            """.trimIndent(),
            "sandbox-lockdown.js",
        )
    }

    private fun createUtils(): JSObject {
        val utils = context.createNewJSObject()
        val crypto = context.createNewJSObject()
        crypto.setProperty("md5", JSCallFunction { args ->
            // LX Music mobile hashes encodeURIComponent(str), not the raw string.
            // Sources derive signed query strings from this, so the quirk matters.
            val input = encodeUriComponent(args.firstOrNull()?.toString().orEmpty())
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        })
        crypto.setProperty("randomBytes", JSCallFunction { args ->
            val size = (args.firstOrNull() as? Number)?.toInt()?.coerceIn(0, 65_536) ?: 0
            val data = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
            createJsArray(data.map { it.toInt() and 0xff })
        })
        crypto.setProperty("aesEncrypt", JSCallFunction { args ->
            val data = bytes(args.getOrNull(0))
            val mode = args.getOrNull(1)?.toString().orEmpty()
            val key = bytes(args.getOrNull(2))
            val iv = bytes(args.getOrNull(3))
            val transformation = if (mode == "aes-128-cbc") "AES/CBC/PKCS5Padding" else "AES/ECB/NoPadding"
            val encrypted = Cipher.getInstance(transformation).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    if (transformation.contains("CBC")) IvParameterSpec(iv) else null,
                )
            }.doFinal(data)
            createJsArray(encrypted.map { it.toInt() and 0xff })
        })
        crypto.setProperty("rsaEncrypt", JSCallFunction { args ->
            val data = bytes(args.getOrNull(0))
            val keyText = args.getOrNull(1)?.toString()?.replace("-----BEGIN PUBLIC KEY-----", "")
                ?.replace("-----END PUBLIC KEY-----", "") ?: ""
            val keyBytes = Base64.getDecoder().decode(keyText)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val encrypted = Cipher.getInstance("RSA/ECB/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(data)
            createJsArray(encrypted.map { it.toInt() and 0xff })
        })
        utils.setProperty("crypto", crypto)

        val buffer = context.createNewJSObject()
        buffer.setProperty("from", JSCallFunction { args ->
            val data = bytes(args.getOrNull(0), args.getOrNull(1)?.toString())
            createJsArray(data.map { it.toInt() and 0xff })
        })
        buffer.setProperty("bufToString", JSCallFunction { args ->
            val format = args.getOrNull(1)?.toString()
            if (format == "binary") {
                args.getOrNull(0)
            } else {
                val data = bytes(args.getOrNull(0))
                when (format) {
                    "hex" -> data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    "base64" -> Base64.getEncoder().encodeToString(data)
                    else -> data.toString(Charsets.UTF_8)
                }
            }
        })
        utils.setProperty("buffer", buffer)
        return utils
    }

    /**
     * Mirrors `lx.request(url, options, callback)` from the LX Music mobile preload
     * and returns the abort function that the preload hands back to the script.
     */
    private fun executeHttpRequest(url: String, optionsValue: Any?, callback: JSFunction?): JSCallFunction {
        val abort = JSCallFunction { null }
        if (callback == null) return abort
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            enqueueHttpResponse(HttpResponse(callback, "Unsupported URL scheme: $url", null, null))
            return abort
        }
        val options = when (optionsValue) {
            is QuickJSObject -> optionsValue.toMap()
            is Map<*, *> -> optionsValue
            else -> emptyMap<String, Any?>()
        }
        val method = (options["method"] as? String)?.uppercase() ?: "GET"
        val headers = options["headers"].asHostMap()
        val bodyValue = options["body"]
        val form = options["form"].asHostMap().takeIf { it.isNotEmpty() }
        val formData = options["formData"]
        val binary = options["binary"] as? Boolean ?: (options["binary"]?.toString() == "true")
        val timeoutMs = (options["timeout"] as? Number)?.toLong()?.coerceIn(1_000L, 60_000L) ?: HTTP_TIMEOUT_MS
        NPLogger.d(TAG, "http start endpoint=${url.toSafeEndpoint()} method=$method headers=${headers.keys.joinToString(",")} " +
            "form=${form != null} formData=${formData != null} body=${bodyValue != null} binary=$binary timeoutMs=$timeoutMs")

        val callRef = AtomicReference<okhttp3.Call?>(null)
        Thread {
            try {
                val requestBuilder = Request.Builder().url(url)
                headers.forEach { (key, value) ->
                    if (key != null && value != null) requestBuilder.addHeader(key.toString(), value.toString())
                }
                if (method != "GET" && method != "HEAD") {
                    requestBuilder.method(method, requestBody(form, formData, bodyValue, headers))
                }
                val call = httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                    .newCall(requestBuilder.build())
                callRef.set(call)
                call.execute().use { response ->
                    val parsedBody: Any = if (binary) {
                        (response.body.bytes().map { it.toInt() and 0xff })
                    } else {
                        parseResponseBody(response.body.string())
                    }
                    NPLogger.i(
                        TAG,
                        "LX HTTP status=${response.code} contentType=${response.header("Content-Type").orEmpty()} " +
                            "body=${responseShape(parsedBody)}",
                    )
                    NPLogger.d(TAG, "http response endpoint=${url.toSafeEndpoint()} code=${response.code} urlAvailable=${responseShape(parsedBody).contains("url")}")
                    val result = mapOf(
                        "statusCode" to response.code,
                        "statusMessage" to response.message,
                        "headers" to response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                        "body" to parsedBody,
                        "url" to response.request.url.toString(),
                        "ok" to response.isSuccessful,
                    )
                    enqueueHttpResponse(HttpResponse(callback, null, result, parsedBody))
                }
            } catch (error: Throwable) {
                NPLogger.w(TAG, "LX HTTP failed error=${error.javaClass.simpleName}: ${error.message.safeLogMessage()}")
                enqueueHttpResponse(HttpResponse(callback, error.message ?: "request failed", null, null))
            }
        }.start()
        return JSCallFunction {
            runCatching { callRef.get()?.cancel() }
            null
        }
    }

    /** Builds the request body for `form`, `formData` or a raw `body` payload. */
    private fun requestBody(
        form: Map<*, *>?,
        formData: Any?,
        bodyValue: Any?,
        headers: Map<*, *>,
    ): okhttp3.RequestBody {
        val declaredContentType = headers.entries
            .firstOrNull { (key, _) -> key?.toString().equals("content-type", ignoreCase = true) }
            ?.value?.toString()
        if (formData != null) {
            val builder = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            var added = false
            val parts: Iterable<Pair<Any?, Any?>> = when (formData) {
                is QuickJSObject -> formData.toMap().toList()
                is Map<*, *> -> formData.toList()
                is List<*> -> formData.mapIndexed { index, value -> index.toString() to value }
                else -> emptyList()
            }
            parts.forEach { (key, value) ->
                if (key == null || value == null) return@forEach
                builder.addFormDataPart(key.toString(), value.toString())
                added = true
            }
            if (added) return builder.build()
        }
        if (form != null) {
            val builder = okhttp3.FormBody.Builder()
            form.forEach { (key, value) ->
                if (key != null && value != null) builder.add(key.toString(), value.toString())
            }
            return builder.build()
        }
        return when (val body = bodyValue) {
            is String -> body.toRequestBody(
                (declaredContentType ?: if (body.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true) {
                    JSON_MEDIA_TYPE
                } else {
                    TEXT_MEDIA_TYPE
                }).toMediaTypeOrNull(),
            )
            is QuickJSObject -> jsonBody(body.toMap())
            is Map<*, *> -> jsonBody(body)
            is List<*> -> JSONArray(body).toString().toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull())
            null -> "".toRequestBody(declaredContentType?.toMediaTypeOrNull())
            else -> body.toString().toRequestBody(TEXT_MEDIA_TYPE.toMediaTypeOrNull())
        }
    }

    private fun jsonBody(value: Map<*, *>): okhttp3.RequestBody =
        JSONObject(value.toStringKeyedMap()).toString().toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull())

    private fun enqueueHttpResponse(response: HttpResponse) {
        pendingHttpResponses.add(response)
        synchronized(drainSignal) { drainSignal.notifyAll() }
    }

    private fun processPendingHttpResponses() {
        while (true) {
            val response = pendingHttpResponses.poll() ?: break
            if (response.error != null) {
                response.callback.call(response.error, null, null)
            } else {
                val resultObj = response.result?.let { createJsObject(it) }
                val bodyObj = response.body?.let { toJsValue(it) }
                response.callback.call(null, resultObj, bodyObj)
            }
        }
    }

    private fun parseResponseBody(body: String): Any = runCatching {
        when (body.trimStart().firstOrNull()) {
            '{' -> JSONObject(body).toHostValue().let { value ->
                // Some LX endpoints wrap the actual payload in `data`, while
                // older user scripts read `body.url` directly.
                val nested = value["data"]
                when {
                    value["url"] != null -> value
                    nested is Map<*, *> -> value + nested.entries.associate { it.key.toString() to it.value }
                    nested is String && nested.startsWith("http") -> value + ("url" to nested)
                    else -> value
                }
            }
            '[' -> JSONArray(body).toHostValue()
            else -> body
        }
    }.getOrDefault(body)

    /**
     * Stores what `lx.send('inited', { sources: { kw: { type, actions, qualitys } } })`
     * announced. Older scripts pass the quality list directly, so both shapes are read.
     */
    private fun recordSourceInfo(value: Any?) {
        val data = when (value) {
            is QuickJSObject -> value.toMap()
            is Map<*, *> -> value
            else -> return
        }
        val sourceMap = when (val sources = data["sources"]) {
            is QuickJSObject -> sources.toMap()
            is Map<*, *> -> sources
            else -> return
        }
        sourceMap.forEach { (name, rawInfo) ->
            val source = name?.toString() ?: return@forEach
            val rawMap = when (rawInfo) {
                is QuickJSObject -> runCatching { rawInfo.toMap() }.getOrNull()
                is Map<*, *> -> rawInfo
                else -> null
            }
            val actions = rawMap?.let { stringList(it["actions"]) }.orEmpty()
            val qualitys = rawMap?.let { stringList(it["qualitys"]) }.orEmpty()
                .ifEmpty { stringList(rawInfo) }
            sourceCapabilities[source] = LxUserSourceCapability(actions = actions, qualitys = qualitys)
            if (qualitys.isNotEmpty()) sourceQualities[source] = qualitys
        }
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is QuickJSObject -> value.toArray().mapNotNull { it?.toString() }
        is List<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }

    private fun settle(value: Any?, onSuccess: (Any?) -> Unit, onError: (Throwable) -> Unit) {
        if (value !is QuickJSObject) {
            onSuccess(value)
            return
        }
        val then = runCatching { value.getJSFunctionProperty("then") }.getOrNull()
        if (then == null) {
            onSuccess(value)
            return
        }
        val resolve = JSCallFunction { args ->
            settle(args.firstOrNull(), onSuccess, onError)
            null
        }
        val reject = JSCallFunction { args ->
            onError(IllegalStateException(args.firstOrNull()?.toString() ?: "LX promise rejected"))
            null
        }
        runCatching { then.call(resolve, reject) }.onFailure { onSuccess(value) }
    }

    /**
     * Pumps JS timers and HTTP replies until the action settles or [timeoutMs] passes.
     *
     * The sources usually call a telemetry endpoint before their real request, and
     * that first call alone can take two seconds. This used to stop after a fixed
     * number of iterations - roughly two seconds - which discarded answers that
     * arrived moments later and made songs fall back to the official trial clip.
     */
    private fun drainUntil(done: CountDownLatch, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (done.count != 0L && System.currentTimeMillis() < deadline) {
            dispatchTimers()
            processPendingHttpResponses()
            runCatching { context.evaluate("void 0") }
            if (done.count == 0L) return
            synchronized(drainSignal) {
                if (done.count == 0L || pendingHttpResponses.isNotEmpty()) return@synchronized
                // Wake early when a reply lands so a finished action does not wait
                // out the rest of the slot.
                drainSignal.wait(10)
            }
        }
    }

    private fun drainScriptInitialization() {
        repeat(600) { iteration ->
            dispatchTimers()
            processPendingHttpResponses()
            runCatching { context.evaluate("void 0") }
            if (requestHandlers.isNotEmpty()) return
            synchronized(drainSignal) {
                if (requestHandlers.isEmpty() && pendingHttpResponses.isEmpty()) drainSignal.wait(5)
            }
            if (iteration >= 20 && pendingHttpResponses.isEmpty() && timers.isEmpty()) Thread.sleep(5)
        }
    }

    private fun dispatchTimers() {
        val now = System.currentTimeMillis()
        val ready = timers.filterValues { (deadline) -> deadline <= now }.keys.toList()
        ready.forEach { id -> timers.remove(id)?.second?.call() }
    }

    /** Converts Kotlin maps/lists into QuickJS objects by round-tripping through JSON. */
    private fun createJsObject(data: Map<String, Any?>): QuickJSObject {
        val json = jsonValueToJson(data)
        return context.evaluate("($json)") as QuickJSObject
    }

    private fun createJsArray(data: List<Any?>): QuickJSObject {
        val json = jsonValueToJson(data)
        return context.evaluate("($json)") as QuickJSObject
    }

    private fun jsonValueToJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            "{" + value.entries.joinToString(",") { (k, v) ->
                "${JSONObject.quote(k.toString())}:${jsonValueToJson(v)}"
            } + "}"
        }
        is List<*> -> {
            "[" + value.joinToString(",") { jsonValueToJson(it) } + "]"
        }
        else -> JSONObject.quote(value.toString())
    }

    private fun Any?.asHostMap(): Map<*, *> = when (this) {
        is QuickJSObject -> this.toMap()
        is Map<*, *> -> this
        else -> emptyMap<Any?, Any?>()
    }

    private fun responseShape(value: Any?): String = when (value) {
        is Map<*, *> -> {
            val keys = value.keys.joinToString(",") { it.toString() }.take(120)
            val code = value["code"]?.toString()?.take(32)
            val message = value["msg"]?.toString()?.safeLogMessage()?.take(80)
            "object:$keys" + if (code != null || message != null) " code=$code msg=$message" else ""
        }
        is List<*> -> "array:${value.size}"
        is String -> "text:${value.length}"
        null -> "null"
        else -> value.javaClass.simpleName
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.toStringKeyedMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key.toString() to value }

    private fun toJsValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> createJsObject(value as Map<String, Any?>)
        is List<*> -> createJsArray(value)
        else -> value
    }

    private fun urlFrom(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is QuickJSObject -> urlFrom(value.toMap())
        is Map<*, *> -> {
            val direct = value["url"]?.toString()
            if (!direct.isNullOrBlank() && (direct.startsWith("http://") || direct.startsWith("https://"))) return direct
            val nested = (value["data"] as? Map<*, *>)?.get("url")?.toString()
            if (!nested.isNullOrBlank() && (nested.startsWith("http://") || nested.startsWith("https://"))) return nested
            null
        }
        else -> null
    }

    private fun bytes(value: Any?, encoding: String? = null): ByteArray = when (value) {
        is ByteArray -> value
        is QuickJSObject -> value.toArray().mapNotNull { (it as? Number)?.toByte() }.toByteArray()
        is List<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
        is String -> when (encoding?.lowercase()) {
            "base64" -> Base64.getDecoder().decode(value)
            "hex" -> value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else -> value.toByteArray(Charsets.UTF_8)
        }
        else -> ByteArray(0)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            context.close()
        }
    }

    private companion object {
        val QUALITY_ORDER = listOf("128k", "320k", "flac", "flac24bit")
        fun createContext(): QuickJSContext {
            QuickJSLoader.init()
            return QuickJSContext.create()
        }
    }
}

private const val TAG = "NERI-LxRuntime"

private fun String.toSafeEndpoint(): String = runCatching {
    val uri = android.net.Uri.parse(this)
    buildString {
        append(uri.scheme.orEmpty())
        append("://")
        append(uri.host.orEmpty())
        append(uri.path.orEmpty())
        if (!uri.query.isNullOrBlank()) append("?<redacted>")
    }
}.getOrDefault("<invalid-url>")

private fun String?.safeLogMessage(): String = this.orEmpty()
    .replace(Regex("https?://\\S+"), "<url>")
    .replace(Regex("(?i)(apikey|api_key|token|key)=([^&\\s]+)"), "$1=<redacted>")
    .replace('\n', ' ')
    .take(240)

/** Matches the characters JavaScript `encodeURIComponent` leaves untouched. */
private fun encodeUriComponent(value: String): String {
    val hex = "0123456789ABCDEF"
    return buildString(value.length) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char in UnreservedCharacters) {
                append(char)
            } else {
                append('%').append(hex[code shr 4]).append(hex[code and 0xf])
            }
        }
    }
}

private const val UnreservedCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

private fun JSONObject.toHostValue(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    jsonValue(opt(key))
}

private fun JSONArray.toHostValue(): List<Any?> = (0 until length()).map { index ->
    jsonValue(opt(index))
}

private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toHostValue()
    is JSONArray -> value.toHostValue()
    else -> value
}
