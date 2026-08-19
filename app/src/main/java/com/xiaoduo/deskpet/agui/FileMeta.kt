package com.xiaoduo.deskpet.agui

import org.json.JSONObject
import java.util.UUID

/** 上传状态（spec 数据模型 FileMeta：UPLOADING / DONE / ERROR）。 */
enum class UploadStatus { UPLOADING, DONE, ERROR }

/**
 * 上传文件元数据（spec 数据模型 FileMeta，纯逻辑零 Android import，JVM 可测）。
 * [localId] 为本地列表键（平台不返回）：UPLOADING 期 fileId 尚空，Compose 列表键/
 * 删除/状态回写全靠它；平台字段与 spec 一一对应。
 */
data class FileMeta(
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val fileSuffix: String,
    val status: UploadStatus,
    val localId: String = UUID.randomUUID().toString(),
) {
    companion object {
        /** 平台上传信封 data 段解析：{fileId,fileName,filePath,fileSize,fileSuffix}；缺失字段给兜底不抛。 */
        fun fromJson(data: JSONObject): FileMeta = FileMeta(
            fileId = data.optString("fileId", ""),
            fileName = data.optString("fileName", ""),
            filePath = data.optString("filePath", ""),
            fileSize = data.optLong("fileSize", 0L),
            fileSuffix = data.optString("fileSuffix", ""),
            status = UploadStatus.DONE,
        )

        /**
         * fileId 接入对话（平台 web bundle 静态取证 2026-08-03，CONSTRUCTION S7 待查①已关闭）：
         * content = "@file:{fileId}?{fileName}" 多条 "|" 连接 + "\n" + 用户文本；无文件原样返回。
         * fileRefs = (fileId, fileName) 对；空白 fileId 跳过（UPLOADING 占位永不该进这里，双保险）。
         * 文件名里的格式分隔符（| ? 换行）替换防前缀错位——该形态原名在平台 web 端同样无法表达，
         * 属格式固有边界（设计决策，记 implementation-notes）。
         */
        fun buildContent(userText: String, fileRefs: List<Pair<String, String>>): String {
            val valid = fileRefs.filter { it.first.isNotBlank() }
            if (valid.isEmpty()) return userText
            val prefix = valid.joinToString("|") { (id, name) -> "@file:$id?${sanitizeFileName(name)}" }
            return "$prefix\n$userText"
        }

        internal fun sanitizeFileName(name: String): String =
            name.replace('|', '_').replace('?', '_').replace('\n', ' ').replace('\r', ' ')
    }
}
