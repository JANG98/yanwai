package dev.jev.wechatmood.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * GitHub Skill 下载器。
 *
 * 从 GitHub 仓库链接下载 skill 仓库（zip 格式），解压到本地技能库目录，
 * 并解析 SKILL.md 生成 Skill 对象。
 *
 * 支持的链接格式：
 * - https://github.com/username/repo
 * - https://github.com/username/repo/tree/main
 * - https://github.com/username/repo/
 */
object SkillDownloader {

    private const val TAG = "SkillDownloader"
    private const val SKILLS_DIR = "skills"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 60000

    /**
     * 下载结果。
     */
    sealed class DownloadResult {
        data class Success(val skill: Skill) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * 从 GitHub 链接下载 skill。
     *
     * @param context 上下文
     * @param githubUrl GitHub 仓库链接
     * @return 下载结果
     */
    suspend fun download(context: Context, githubUrl: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // 1. 解析 GitHub 链接，提取 owner/repo
            val (owner, repo) = parseGithubUrl(githubUrl)
                ?: return@withContext DownloadResult.Error("无效的 GitHub 链接格式，请输入类似 https://github.com/username/repo 的链接")

            Log.d(TAG, "解析到 owner=$owner, repo=$repo")

            // 2. 确定分支名（先试 main，再试 master）
            val branch = detectBranch(owner, repo)
                ?: return@withContext DownloadResult.Error("无法访问仓库，请检查链接是否正确，或仓库是否为公开仓库")

            Log.d(TAG, "检测到分支: $branch")

            // 3. 下载 zip
            val zipUrl = "https://github.com/$owner/$repo/archive/refs/heads/$branch.zip"
            val zipFile = File(context.cacheDir, "skill_download_${System.currentTimeMillis()}.zip")

            downloadFile(zipUrl, zipFile)

            if (!zipFile.exists() || zipFile.length() == 0L) {
                return@withContext DownloadResult.Error("下载失败，文件为空")
            }

            Log.d(TAG, "下载完成，大小: ${zipFile.length()} 字节")

            // 4. 解压到技能库目录
            val skillsDir = File(context.filesDir, SKILLS_DIR)
            if (!skillsDir.exists()) skillsDir.mkdirs()

            // 目标目录名：repo 名（如果已存在则加时间戳）
            var targetDirName = repo
            var targetDir = File(skillsDir, targetDirName)
            if (targetDir.exists()) {
                targetDirName = "${repo}_${System.currentTimeMillis()}"
                targetDir = File(skillsDir, targetDirName)
            }
            targetDir.mkdirs()

            unzip(zipFile, targetDir)

            // 清理 zip
            zipFile.delete()

            Log.d(TAG, "解压完成: ${targetDir.absolutePath}")

            // 5. 查找实际包含 SKILL.md 的目录（zip 解压后通常有一层 repo-branch 目录）
            val skillDir = findSkillDir(targetDir)
                ?: return@withContext DownloadResult.Error("未找到 SKILL.md 文件，这可能不是一个标准的 skill 仓库")

            Log.d(TAG, "找到 skill 目录: ${skillDir.absolutePath}")

            // 6. 解析 SKILL.md
            val (name, description, prompt) = SkillParser.parseFromDir(skillDir)
                ?: return@withContext DownloadResult.Error("解析 SKILL.md 失败")

            // 7. 生成 Skill 对象
            val skill = Skill(
                id = Skill.newId(),
                name = name,
                description = description.ifBlank { "从 GitHub 下载的技能：$owner/$repo" },
                prompt = SkillParser.truncatePrompt(prompt),
                enabled = false,
                source = Skill.SOURCE_LIBRARY,
                version = branch,
                dirName = if (skillDir != targetDir) "${targetDir.name}/${skillDir.name}" else targetDir.name,
                repoUrl = "https://github.com/$owner/$repo",
            )

            Log.d(TAG, "技能解析完成: name=${skill.name}, prompt长度=${skill.prompt.length}")

            DownloadResult.Success(skill)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            DownloadResult.Error("下载失败: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 解析 GitHub 链接，提取 owner 和 repo。
     */
    private fun parseGithubUrl(url: String): Pair<String, String>? {
        val cleaned = url.trim().removeSuffix("/")
            .removeSuffix("/tree/main")
            .removeSuffix("/tree/master")

        // 匹配 https://github.com/owner/repo
        val regex = Regex("""https?://github\.com/([^/]+)/([^/]+)""")
        val match = regex.find(cleaned) ?: return null

        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")

        if (owner.isBlank() || repo.isBlank()) return null
        return Pair(owner, repo)
    }

    /**
     * 检测仓库的默认分支（main 或 master）。
     */
    private fun detectBranch(owner: String, repo: String): String? {
        // 先试 main
        if (checkUrlExists("https://github.com/$owner/$repo/archive/refs/heads/main.zip")) {
            return "main"
        }
        // 再试 master
        if (checkUrlExists("https://github.com/$owner/$repo/archive/refs/heads/master.zip")) {
            return "master"
        }
        return null
    }

    /**
     * 检查 URL 是否可访问（HEAD 请求）。
     */
    private fun checkUrlExists(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 下载文件到本地。
     */
    private fun downloadFile(urlString: String, destFile: File) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "yanwai-skill-downloader")

        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
    }

    /**
     * 解压 zip 文件到目标目录。
     */
    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryFile = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 在解压后的目录中查找包含 SKILL.md 的目录。
     * GitHub zip 解压后通常有一层 repo-branch 目录。
     */
    private fun findSkillDir(rootDir: File): File? {
        // 先检查根目录
        if (File(rootDir, "SKILL.md").exists()) return rootDir

        // 检查一级子目录
        rootDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory && File(subDir, "SKILL.md").exists()) {
                return subDir
            }
        }

        // 检查二级子目录
        rootDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                subDir.listFiles()?.forEach { subSubDir ->
                    if (subSubDir.isDirectory && File(subSubDir, "SKILL.md").exists()) {
                        return subSubDir
                    }
                }
            }
        }

        return null
    }

    /**
     * 获取技能库根目录。
     */
    fun getSkillsDir(context: Context): File =
        File(context.filesDir, SKILLS_DIR)

    /**
     * 删除已下载的 skill 目录。
     */
    fun deleteSkillDir(context: Context, dirName: String): Boolean {
        val dir = File(getSkillsDir(context), dirName)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * 列出所有已下载的 skill 目录。
     */
    fun listDownloadedSkills(context: Context): List<File> {
        val skillsDir = getSkillsDir(context)
        if (!skillsDir.exists()) return emptyList()
        return skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }
}
