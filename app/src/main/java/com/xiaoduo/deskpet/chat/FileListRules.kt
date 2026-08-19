package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.agui.FileMeta
import com.xiaoduo.deskpet.agui.UploadStatus

/**
 * 待发文件列表规则（纯逻辑零 Android import，JVM 可测；VM 只做 IO 接线）。
 *
 * - 禁发守卫 single source（critic P2-①）：send 与 resend 共用 [uploadInFlight] 判定，
 *   结构上杜绝「send 有守卫、resend 漏同款检查」的平行代码分叉。
 * - 成功清场不连坐（critic P2-②）：turn 成功只清**本轮挂载快照**（发送一刻捕获的
 *   DONE localId 集）——turn 进行中（STREAMING）用户可再选文件并传完 DONE，那份文件
 *   从未挂进本轮消息，不能被本轮成功连带清空（曾导致 chip 静默消失需重选）。
 *   会话级整清（切会话/新建/删当前）仍走 VM 的 resetFiles，语义不同不相干。
 */
object FileListRules {

    /** 有 UPLOADING 即禁发/禁重发（VM send/resend 双保险与 UI 多功能键 DISABLED 同源）。 */
    fun uploadInFlight(files: List<FileMeta>): Boolean =
        files.any { it.status == UploadStatus.UPLOADING }

    /** 本轮挂载快照条目：localId（清场键）+ fileId/fileName（content 前缀注入用）。 */
    data class Attached(val localId: String, val fileId: String, val fileName: String)

    /** 发送一刻捕获本轮挂载快照：仅 DONE 且有 fileId 的条目（UPLOADING/ERROR 不挂载）。 */
    fun attachedDone(files: List<FileMeta>): List<Attached> =
        files.filter { it.status == UploadStatus.DONE && it.fileId.isNotBlank() }
            .map { Attached(it.localId, it.fileId, it.fileName) }

    /** 发送成功清场：仅移除快照内 localId；快照外条目（后到的 DONE/UPLOADING）原样保留。 */
    fun clearAttached(files: List<FileMeta>, attachedLocalIds: Set<String>): List<FileMeta> =
        files.filterNot { it.localId in attachedLocalIds }
}
