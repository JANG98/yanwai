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
 * 扫描仓库中所有 SKILL.md 文件，解析为多个独立技能。
 *
 * 一个仓库可以包含多个技能（例如 goutoujunshi 仓库可能有多个子目录，
 * 每个子目录都有自己的 SKILL.md）。
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
        data class Success(
            val repository: SkillRepository,
            val skills: List<Skill>,
        ) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * 从 GitHub 链接下载 skill 仓库。
     *
     * @param context 上下文
     * @param githubUrl GitHub 仓库链接
     * @return 下载结果，包含仓库信息和解析出的技能列表
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

            // 5. 找到仓库实际根目录（zip 解压后通常有一层 repo-branch 目录）
            val repoRoot = findRepoRoot(targetDir) ?: targetDir

            Log.d(TAG, "仓库根目录: ${repoRoot.absolutePath}")

            // 6. 扫描所有 SKILL.md 文件
            val skillFiles = findAllSkillFiles(repoRoot)

            if (skillFiles.isEmpty()) {
                return@withContext DownloadResult.Error("未找到任何 SKILL.md 文件，这可能不是一个标准的 skill 仓库")
            }

            Log.d(TAG, "找到 ${skillFiles.size} 个 SKILL.md 文件")

            // 7. 创建仓库对象
            val repositoryId = "repo_${System.currentTimeMillis()}"
            val repository = SkillRepository(
                id = repositoryId,
                name = repo,
                url = "https://github.com/$owner/$repo",
                dirName = if (repoRoot != targetDir) "${targetDir.name}/${repoRoot.name}" else targetDir.name,
                version = branch,
                skillCount = skillFiles.size,
            )

            // 8. 解析每个 SKILL.md 为技能
            val skills = skillFiles.mapIndexed { index, skillFile ->
                val (name, description, prompt) = SkillParser.parseFromFile(skillFile)
                val relativePath = skillFile.relativeTo(repoRoot).path
                Skill(
                    id = "${repositoryId}_skill_$index",
                    name = name.ifBlank { skillFile.parentFile?.name ?: "技能 $index" },
                    description = description.ifBlank { "来自仓库 $repo 的技能：$relativePath" },
                    prompt = SkillParser.truncatePrompt(prompt),
                    enabled = false,
                    source = Skill.SOURCE_LIBRARY,
                    version = branch,
                    repositoryId = repositoryId,
                    filePath = relativePath,
                )
            }

            Log.d(TAG, "解析完成: ${skills.size} 个技能")

            DownloadResult.Success(repository, skills)
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
     * 找到仓库实际根目录（zip 解压后通常有一层 repo-branch 目录）。
     */
    private fun findRepoRoot(rootDir: File): File? {
        // 如果根目录下只有一个子目录，且该子目录包含文件，则它可能是仓库根目录
        val children = rootDir.listFiles() ?: return null
        if (children.size == 1 && children[0].isDirectory) {
            return children[0]
        }
        return null
    }

    /**
     * 递归扫描目录中所有 SKILL.md 文件。
     */
    private fun findAllSkillFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.equals("SKILL.md", ignoreCase = true)) {
                result.add(file)
            }
        }
        return result
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
