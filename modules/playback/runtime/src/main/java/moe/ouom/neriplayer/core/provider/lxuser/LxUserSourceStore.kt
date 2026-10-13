package moe.ouom.neriplayer.core.provider.lxuser

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Stores LX user-source metadata separately from the executable script body. */
object LxUserSourceStore {
    private const val PreferencesName = "neri_lx_user_sources"
    private const val KeyList = "list"
    private const val MaxSources = 20

    /** 脚本正文体积上限；[importFromUri] 读的时候按它截断，超了直接拒绝。 */
    const val MaxScriptBytes = 9_000_000L

    private const val ReadBufferSize = 8 * 1024

    fun list(context: Context): List<LxUserSourceRecord> {
        val raw = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(KeyList, null) ?: return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    LxUserSourceRecord(
                        id = id,
                        metadata = LxUserScriptMetadata(
                            name = item.optString("name").takeIf(String::isNotBlank),
                            version = item.optString("version").takeIf(String::isNotBlank),
                            author = item.optString("author").takeIf(String::isNotBlank),
                            description = item.optString("description").takeIf(String::isNotBlank),
                            homepage = item.optString("homepage").takeIf(String::isNotBlank),
                        ),
                    ),
                )
            }
        }
    }

    fun import(context: Context, script: String): LxUserSourceRecord {
        val normalizedScript = script.removePrefix("\uFEFF")
        require(normalizedScript.toByteArray(Charsets.UTF_8).size <= MaxScriptBytes) { "音乐源脚本过大（最大 9 MB）" }
        require(looksLikeLxMusicSource(normalizedScript)) { "不是有效的 LX Music 音乐源脚本" }
        val metadata = LxUserScriptMetadata.parse(normalizedScript)
        val id = "user_api_${System.currentTimeMillis()}_${(100..999).random()}"
        val record = LxUserSourceRecord(id, metadata)
        val app = context.applicationContext
        val dir = File(app.filesDir, "lx-user-sources").apply { mkdirs() }
        File(dir, "$id.js").writeText(normalizedScript, Charsets.UTF_8)
        val records = (list(app) + record).takeLast(MaxSources)
        saveList(app, records)
        return record
    }

    fun script(context: Context, id: String): String? =
        File(File(context.applicationContext.filesDir, "lx-user-sources"), "$id.js")
            .takeIf(File::isFile)?.readText(Charsets.UTF_8)

    /**
     * 从用户选中的 content Uri 读脚本并导入。
     *
     * 自己实现限长读取而不是复用 `data.config.LimitedTextReader`：那个工具是 `:local` 模块的
     * internal 实现，跨模块不可见；而「读多少算超限」本来就是本类的职责。
     * 读的过程中就判大小，超限立刻停，不把超大文件整个吃进内存。
     */
    fun importFromUri(context: Context, uri: Uri, maxBytes: Long = MaxScriptBytes): LxUserSourceRecord {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input")
        val script = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(ReadBufferSize)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read.toLong()
                if (total > maxBytes) throw IOException("Input file is too large")
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
        return import(context, script)
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        File(File(app.filesDir, "lx-user-sources"), "$id.js").delete()
        saveList(app, list(app).filterNot { it.id == id })
    }

    private fun saveList(context: Context, records: List<LxUserSourceRecord>) {
        val json = JSONArray().apply {
            records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id)
                    put("name", record.metadata.name.orEmpty())
                    put("version", record.metadata.version.orEmpty())
                    put("author", record.metadata.author.orEmpty())
                    put("description", record.metadata.description.orEmpty())
                    put("homepage", record.metadata.homepage.orEmpty())
                })
            }
        }
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit().putString(KeyList, json.toString()).apply()
    }

    /** LX v5 sources are often obfuscated and encode public names as Unicode escapes. */
    internal fun looksLikeLxMusicSource(script: String): Boolean {
        val decoded = EscapedUnicode.replace(script) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        val hasMetadata = decoded.contains("@name") && decoded.contains("@version")
        val hasRuntimeMarker = decoded.contains("globalThis") && (
            decoded.contains("lx") || decoded.contains("musicUrl") || decoded.contains("apiUrl")
        )
        return hasMetadata && hasRuntimeMarker
    }

    private val EscapedUnicode = Regex("\\\\u([0-9a-fA-F]{4})")
}

data class LxUserSourceRecord(
    val id: String,
    val metadata: LxUserScriptMetadata,
)
