package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.agui.FileMeta
import com.xiaoduo.deskpet.agui.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待发文件列表规则（critic P2 双修复钉死）：
 * - P2-① 禁发守卫与 send/resend 同源：[uploadInFlight] 为唯一判定点，两条调用路径不可再分叉。
 * - P2-② 成功清场不连坐：[clearAttached] 只移除本轮挂载快照内 localId，
 *   turn 进行中用户新选并传完（DONE 但未挂进本轮）的文件必须存活。
 */
class FileListRulesTest {

    private fun meta(
        localId: String,
        status: UploadStatus,
        fileId: String = "fid-$localId",
    ) = FileMeta(
        fileId = fileId,
        fileName = "$localId.txt",
        filePath = "",
        fileSize = 10L,
        fileSuffix = "txt",
        status = status,
        localId = localId,
    )

    // ---- P2-① uploadInFlight：send/resend 同款禁发守卫 ----

    @Test
    fun `uploadInFlight true when any entry uploading`() {
        val files = listOf(
            meta("a", UploadStatus.DONE),
            meta("b", UploadStatus.UPLOADING, fileId = ""),
        )
        assertTrue(FileListRules.uploadInFlight(files))
    }

    @Test
    fun `uploadInFlight false when none uploading`() {
        assertFalse(FileListRules.uploadInFlight(emptyList()))
        assertFalse(
            FileListRules.uploadInFlight(
                listOf(meta("a", UploadStatus.DONE), meta("b", UploadStatus.ERROR, fileId = "")),
            ),
        )
    }

    // ---- attachedDone：本轮挂载快照（仅 DONE 且有 fileId） ----

    @Test
    fun `attachedDone takes done entries with file id carrying local id`() {
        val files = listOf(
            meta("done1", UploadStatus.DONE),
            meta("up", UploadStatus.UPLOADING, fileId = ""),
            meta("err", UploadStatus.ERROR, fileId = ""),
            meta("done2", UploadStatus.DONE),
        )
        val attached = FileListRules.attachedDone(files)
        assertEquals(listOf("done1", "done2"), attached.map { it.localId })
        assertEquals(listOf("fid-done1", "fid-done2"), attached.map { it.fileId })
        assertEquals(listOf("done1.txt", "done2.txt"), attached.map { it.fileName })
    }

    // ---- P2-② clearAttached：只清快照内 localId，不连坐 ----

    @Test
    fun `clearAttached removes only snapshot ids and keeps later done and in-flight`() {
        // 场景：turn 开始时挂载 a；STREAMING 中用户又选 b（已 DONE）与 c（仍 UPLOADING）；
        // turn 成功只能清 a，b/c 必须存活（critic P2-② 连坐修复）
        val files = listOf(
            meta("a", UploadStatus.DONE),
            meta("b", UploadStatus.DONE),
            meta("c", UploadStatus.UPLOADING, fileId = ""),
        )
        val cleared = FileListRules.clearAttached(files, setOf("a"))
        assertEquals(listOf("b", "c"), cleared.map { it.localId })
    }

    @Test
    fun `clearAttached with empty snapshot keeps everything`() {
        val files = listOf(meta("a", UploadStatus.DONE))
        assertEquals(files, FileListRules.clearAttached(files, emptySet()))
    }

    @Test
    fun `clearAttached removes full snapshot`() {
        val files = listOf(meta("a", UploadStatus.DONE), meta("b", UploadStatus.DONE))
        assertTrue(FileListRules.clearAttached(files, setOf("a", "b")).isEmpty())
    }
}
