package dev.jev.wechatmood.core

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 聊天分析数据备份管理。
 *
 * 支持将所有聊天分析记录导出为 zip 文件，或从 zip 文件导入。
 * zip 内包含 analysis.json（所有分析记录）和 manifest.json（元数据）。
 */
object AnalysisBackupManager {
    private const val JSON_FILENAME = "analysis.json"
    private const val MANIFEST_FILENAME = "manifest.json"
    private const val BACKUP_DIR = "backup"

    /**
     * 导出所有聊天分析记录为 zip 文件。
     * @return 生成的 zip 文件路径
     */
    fun exportToZip(context: Context): File {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        val fileName = "yanwai_analysis_${System.currentTimeMillis()}.zip"
        val zipFile = File(dir, fileName)

        val json = ChatAnalysisStore.exportAll(context)
        val manifest = org.json.JSONObject()
            .put("app", "yanwai")
            .put("version", BuildConfig.VERSION_NAME)
            .put("exportTime", System.currentTimeMillis())
            .put("type", "chat_analysis_backup")
            .toString(2)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 写入 analysis.json
            zos.putNextEntry(ZipEntry(JSON_FILENAME))
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 写入 manifest.json
            zos.putNextEntry(ZipEntry(MANIFEST_FILENAME))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        MoodLog.i("导出聊天分析备份: ${zipFile.absolutePath} (${zipFile.length() / 1024} KB)")
        return zipFile
    }

    /**
     * 从 zip 文件导入聊天分析记录。
     * @param uri zip 文件的 Uri
     * @return 导入的记录数
     */
    fun importFromZip(context: Context, uri: Uri): Int {
        var jsonContent: String? = null

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    if (entry?.name == JSON_FILENAME) {
                        jsonContent = zis.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                }
            }
        }

        if (jsonContent.isNullOrBlank()) {
            throw IllegalArgumentException("zip 文件中未找到 analysis.json")
        }

        val imported = ChatAnalysisStore.importAll(context, jsonContent!!)
        MoodLog.i("导入聊天分析备份: $imported 条记录")
        return imported
    }

    /**
     * 获取所有已导出的备份文件列表。
     */
    fun listBackups(context: Context): List<File> {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 删除指定的备份文件。
     */
    fun deleteBackup(file: File): Boolean = file.delete()
}
