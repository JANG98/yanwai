package dev.jev.wechatmood.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天分析结果持久化存储。
 *
 * 按聊天对象（talker）分组存储消息和分析结果，微信进程重启后不丢失，
 * 避免对已分析过的消息进行二次 AI 调用。
 *
 * 存储格式：每个 talker 一个 JSON 文件，位于 filesDir/chat_analysis/ 目录下。
 * 文件名使用 talker 的 hashCode，避免特殊字符问题。
 */
object ChatAnalysisStore {
    private const val DIR_NAME = "chat_analysis"
    private const val MAX_PER_TALKER = 500  // 每个聊天对象最多保存 500 条分析记录

    // 内存缓存，避免每次都读文件
    private val memoryCache = ConcurrentHashMap<String, MutableList<StoredAnalysis>>()
    private val keyIndex = ConcurrentHashMap<String, StoredAnalysis>()  // key -> 记录，用于快速查找

    /**
     * 单条分析记录。
     */
    data class StoredAnalysis(
        val key: String,           // MoodStore.keyOf 生成的唯一键
        val talker: String,        // 聊天对象
        val text: String,          // 消息文本
        val speaker: String,       // 发送者（对方/我）
        val timestamp: Long,       // 分析时间戳
        val moodLabel: String,     // 分析结果：标签
        val moodScore: Double,     // 分析结果：情绪分数
        val moodRisk: Int,         // 分析结果：风险等级
        val moodDetail: String,    // 分析结果：详细内容
        val moodRaw: String,       // 分析结果：原始 JSON
        val relationship: String,  // 当时的关系描述（可选）
    ) {
        fun toMood(): Mood = Mood(moodLabel, moodScore, moodRisk, moodRaw, moodDetail)

        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("talker", talker)
            .put("text", text)
            .put("speaker", speaker)
            .put("timestamp", timestamp)
            .put("moodLabel", moodLabel)
            .put("moodScore", moodScore)
            .put("moodRisk", moodRisk)
            .put("moodDetail", moodDetail)
            .put("moodRaw", moodRaw)
            .put("relationship", relationship)

        companion object {
            fun fromJson(obj: JSONObject): StoredAnalysis = StoredAnalysis(
                key = obj.getString("key"),
                talker = obj.optString("talker", ""),
                text = obj.optString("text", ""),
                speaker = obj.optString("speaker", "对方"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                moodLabel = obj.optString("moodLabel", ""),
                moodScore = obj.optDouble("moodScore", 0.0),
                moodRisk = obj.optInt("moodRisk", 0),
                moodDetail = obj.optString("moodDetail", ""),
                moodRaw = obj.optString("moodRaw", ""),
                relationship = obj.optString("relationship", ""),
            )
        }
    }

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileFor(context: Context, talker: String): File =
        File(dir(context), "${talker.hashCode()}.json")

    /** 判断当前是否运行在微信进程中。 */
    private fun isWeChatProcess(context: Context): Boolean =
        context.packageName == "com.tencent.mm"

    /**
     * 保存一条分析结果。
     * 微信进程中通过 ContentProvider 持久化到言外进程，言外进程直接写本地文件。
     */
    @Synchronized
    fun save(context: Context, input: AnalysisInput, mood: Mood) {
        val talker = input.talker
        val record = StoredAnalysis(
            key = input.key,
            talker = talker,
            text = input.text,
            speaker = input.speaker,
            timestamp = System.currentTimeMillis(),
            moodLabel = mood.label,
            moodScore = mood.score,
            moodRisk = mood.risk,
            moodDetail = mood.detail,
            moodRaw = mood.raw,
            relationship = input.relationship,
        )

        // 更新内存缓存（无论哪个进程都先更新内存）
        val list = memoryCache.getOrPut(talker) { loadFromFile(context, talker) }
        list.removeAll { it.key == record.key }
        list.add(0, record)
        while (list.size > MAX_PER_TALKER) {
            list.removeAt(list.size - 1)
        }
        keyIndex[record.key] = record

        // 持久化
        if (isWeChatProcess(context)) {
            // 微信进程：通过 ContentProvider 保存到言外进程
            runCatching {
                val extras = android.os.Bundle().apply { putString("record", record.toJson().toString()) }
                context.contentResolver.call(SettingsProvider.URI, "analysis_save", null, extras)
            }.onFailure { MoodLog.e("ANALYSIS_SAVE_IPC_FAILED", it) }
        } else {
            // 言外进程：直接写本地文件
            saveToFile(context, talker, list)
        }
    }

    /**
     * 从 JSON 导入单条记录（供 ContentProvider 调用，运行在言外进程）。
     */
    @Synchronized
    fun importRecord(context: Context, json: String) {
        runCatching {
            val record = StoredAnalysis.fromJson(org.json.JSONObject(json))
            val list = memoryCache.getOrPut(record.talker) { loadFromFile(context, record.talker) }
            if (list.none { it.key == record.key }) {
                list.add(0, record)
                keyIndex[record.key] = record
                saveToFile(context, record.talker, list)
            }
        }.onFailure { MoodLog.e("ANALYSIS_IMPORT_RECORD_FAILED", it) }
    }

    /**
     * 根据 key 查找分析结果。
     * 先查内存缓存，再查持久化存储。
     */
    fun findByKey(context: Context, key: String): Mood? {
        // 先查内存索引
        keyIndex[key]?.let { return it.toMood() }
        // 内存没有，微信进程通过 ContentProvider 查
        if (isWeChatProcess(context)) {
            runCatching {
                val result = context.contentResolver.call(SettingsProvider.URI, "analysis_find", key, null)
                if (result?.containsKey("label") == true) {
                    val mood = Mood(
                        label = result.getString("label") ?: "",
                        score = result.getDouble("score"),
                        risk = result.getInt("risk"),
                        raw = result.getString("raw") ?: "",
                        detail = result.getString("detail") ?: "",
                    )
                    // 缓存到内存
                    keyIndex[key] = StoredAnalysis(
                        key = key, talker = "", text = "", speaker = "",
                        timestamp = 0, moodLabel = mood.label, moodScore = mood.score,
                        moodRisk = mood.risk, moodDetail = mood.detail, moodRaw = mood.raw,
                        relationship = "",
                    )
                    return mood
                }
            }.onFailure { MoodLog.e("ANALYSIS_FIND_IPC_FAILED", it) }
            return null
        }
        // 言外进程：从文件加载（warmup 应该已经加载过了）
        return null
    }

    /** 本地查找（供 ContentProvider 调用，运行在言外进程）。 */
    fun findByKeyLocal(context: Context, key: String): Mood? {
        keyIndex[key]?.let { return it.toMood() }
        return null
    }

    /** 本地计数（供 ContentProvider 调用，运行在言外进程）。 */
    fun totalCountLocal(context: Context): Int {
        warmup(context)
        return keyIndex.size
    }

    /**
     * 预热缓存：加载所有聊天对象的分析记录到内存。
     * 微信进程通过 ContentProvider 从言外进程加载，言外进程直接读本地文件。
     * 在微信进程启动时调用一次。
     */
    @Synchronized
    fun warmup(context: Context) {
        if (keyIndex.isNotEmpty()) return  // 已经预热过

        if (isWeChatProcess(context)) {
            // 微信进程：通过 ContentProvider 从言外进程加载
            runCatching {
                val result = context.contentResolver.call(SettingsProvider.URI, "analysis_warmup", null, null)
                val json = result?.getString("data")
                if (!json.isNullOrBlank()) {
                    val root = JSONObject(json)
                    val arr = root.getJSONArray("records")
                    for (i in 0 until arr.length()) {
                        val record = StoredAnalysis.fromJson(arr.getJSONObject(i))
                        keyIndex[record.key] = record
                        memoryCache.getOrPut(record.talker) { mutableListOf() }.add(record)
                    }
                    MoodLog.i("聊天分析缓存预热完成（IPC），共 ${keyIndex.size} 条记录")
                }
            }.onFailure { MoodLog.e("CHAT_ANALYSIS_WARMUP_IPC_FAILED", it) }
            return
        }

        // 言外进程：直接读本地文件
        val d = dir(context)
        d.listFiles()?.forEach { file ->
            runCatching {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val record = StoredAnalysis.fromJson(arr.getJSONObject(i))
                    keyIndex[record.key] = record
                    memoryCache.getOrPut(record.talker) { mutableListOf() }.add(record)
                }
            }.onFailure { MoodLog.e("CHAT_ANALYSIS_LOAD_FAILED", it) }
        }
        MoodLog.i("聊天分析缓存预热完成，共 ${keyIndex.size} 条记录")
    }

    /**
     * 获取某个聊天对象的所有分析记录。
     */
    fun getByTalker(context: Context, talker: String): List<StoredAnalysis> {
        return memoryCache.getOrPut(talker) { loadFromFile(context, talker) }.toList()
    }

    /**
     * 获取所有聊天对象及其记录数量。
     */
    fun getAllTalkers(context: Context): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        // 先从内存缓存
        memoryCache.forEach { (talker, list) -> result[talker] = list.size }
        // 再检查文件系统中是否有未加载的
        dir(context).listFiles()?.forEach { file ->
            runCatching {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                // 从第一条记录获取 talker
                if (arr.length() > 0) {
                    val talker = arr.getJSONObject(0).optString("talker", "")
                    if (talker.isNotBlank() && !result.containsKey(talker)) {
                        result[talker] = arr.length()
                    }
                }
            }
        }
        return result
    }

    /**
     * 获取总记录数。
     */
    fun totalCount(context: Context): Int {
        warmup(context)
        return keyIndex.size
    }

    /**
     * 清除某个聊天对象的所有分析记录。
     */
    @Synchronized
    fun clearTalker(context: Context, talker: String) {
        memoryCache.remove(talker)
        keyIndex.entries.removeAll { it.value.talker == talker }
        fileFor(context, talker).delete()
    }

    /**
     * 清除所有分析记录。
     */
    @Synchronized
    fun clearAll(context: Context) {
        memoryCache.clear()
        keyIndex.clear()
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * 导出所有分析记录为一个 JSON 字符串（用于打包 zip）。
     */
    fun exportAll(context: Context): String {
        warmup(context)
        val allRecords = mutableListOf<StoredAnalysis>()
        memoryCache.values.forEach { allRecords.addAll(it) }
        val arr = JSONArray()
        allRecords.sortedByDescending { it.timestamp }.forEach { arr.put(it.toJson()) }
        val root = JSONObject()
            .put("version", 1)
            .put("exportTime", System.currentTimeMillis())
            .put("count", allRecords.size)
            .put("records", arr)
        return root.toString(2)
    }

    /**
     * 从 JSON 字符串导入分析记录（用于从 zip 解包后恢复）。
     * 返回导入的记录数。
     */
    @Synchronized
    fun importAll(context: Context, json: String): Int {
        runCatching {
            val root = JSONObject(json)
            val arr = root.getJSONArray("records")
            var imported = 0
            for (i in 0 until arr.length()) {
                val record = StoredAnalysis.fromJson(arr.getJSONObject(i))
                val talker = record.talker
                if (talker.isBlank()) continue

                val list = memoryCache.getOrPut(talker) { loadFromFile(context, talker) }
                if (list.none { it.key == record.key }) {
                    list.add(record)
                    keyIndex[record.key] = record
                    imported++
                }
            }
            // 按 talker 分组保存
            memoryCache.forEach { (talker, list) ->
                if (list.isNotEmpty()) saveToFile(context, talker, list)
            }
            MoodLog.i("导入聊天分析记录 $imported 条")
            return imported
        }.onFailure { MoodLog.e("CHAT_ANALYSIS_IMPORT_FAILED", it) }
        return 0
    }

    private fun loadFromFile(context: Context, talker: String): MutableList<StoredAnalysis> {
        val file = fileFor(context, talker)
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<StoredAnalysis>()
            for (i in 0 until arr.length()) {
                val record = StoredAnalysis.fromJson(arr.getJSONObject(i))
                list.add(record)
                keyIndex[record.key] = record
            }
            list
        }.getOrDefault(mutableListOf())
    }

    private fun saveToFile(context: Context, talker: String, list: List<StoredAnalysis>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            fileFor(context, talker).writeText(arr.toString(), Charsets.UTF_8)
        }.onFailure { MoodLog.e("CHAT_ANALYSIS_SAVE_FAILED", it) }
    }
}
