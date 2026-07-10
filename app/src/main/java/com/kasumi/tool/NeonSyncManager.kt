package com.kasumi.tool

import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Đồng bộ dữ liệu config của các script Delta client (thư mục
 * /storage/emulated/0/Delta/Workspace) lên/xuống database Neon Postgres.
 *
 * Không dùng JDBC (nặng và không ổn định trên Android). Thay vào đó gọi thẳng
 * endpoint SQL-over-HTTP của Neon: POST https://<host>/sql với header
 * "Neon-Connection-String". Mỗi request chỉ chạy đúng một câu lệnh, tham số
 * truyền qua mảng params ($1, $2, ...) để tránh SQL injection.
 *
 * CẢNH BÁO BẢO MẬT: chuỗi kết nối dưới đây chứa mật khẩu chủ sở hữu database.
 * Khi nhúng vào APK (nhất là repo công khai) thì bất kỳ ai giải nén APK cũng
 * lấy được toàn quyền truy cập DB. Nên thay bằng một role hạn chế quyền hoặc
 * đặt sau một backend proxy trước khi phát hành rộng rãi.
 */
class NeonSyncManager(private val client: OkHttpClient) {

    class NeonException(message: String) : Exception(message)

    companion object {
        private const val CONNECTION_STRING =
            "postgresql://neondb_owner:npg_hJc51UEkqjGL@ep-cold-shape-aob8jcys-pooler.c-2.ap-southeast-1.aws.neon.tech/neondb?sslmode=require"
        private const val SQL_ENDPOINT =
            "https://ep-cold-shape-aob8jcys-pooler.c-2.ap-southeast-1.aws.neon.tech/sql"

        /** Bỏ qua các tệp quá lớn để tránh vượt giới hạn payload của HTTP endpoint. */
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val workspaceDir: File
            get() = File(Environment.getExternalStorageDirectory(), "Delta/Workspace")
    }

    // --- Lớp gọi HTTP tới Neon ------------------------------------------------

    private suspend fun query(sql: String, params: List<Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            val paramArray = JSONArray()
            for (p in params) paramArray.put(p ?: JSONObject.NULL)
            val payload = JSONObject()
                .put("query", sql)
                .put("params", paramArray)

            val request = Request.Builder()
                .url(SQL_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Neon-Connection-String", CONNECTION_STRING)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(text).optString("message").ifBlank { "HTTP ${resp.code}" }
                    } catch (_: Exception) {
                        "HTTP ${resp.code}"
                    }
                    throw NeonException(msg)
                }
                try {
                    JSONObject(text)
                } catch (_: Exception) {
                    throw NeonException("Phản hồi không hợp lệ từ máy chủ")
                }
            }
        }

    private suspend fun ensureSchema() {
        query(
            "create table if not exists delta_configs (" +
                "id bigserial primary key, " +
                "profile_name text not null, " +
                "file_path text not null, " +
                "content text not null, " +
                "size bigint not null, " +
                "is_binary boolean not null default false, " +
                "updated_at timestamptz not null default now(), " +
                "unique(profile_name, file_path))",
            emptyList()
        )
    }

    // --- Đọc thư mục Workspace ------------------------------------------------

    /** Kết quả quét thư mục Workspace trên máy. */
    data class LocalScan(
        val files: List<LocalFile>,
        val skipped: List<String>
    )

    data class LocalFile(val relativePath: String, val file: File, val size: Long)

    suspend fun scanLocal(): LocalScan = withContext(Dispatchers.IO) {
        val root = workspaceDir
        if (!root.exists() || !root.isDirectory) {
            return@withContext LocalScan(emptyList(), emptyList())
        }
        val files = mutableListOf<LocalFile>()
        val skipped = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            val size = f.length()
            if (size > MAX_FILE_BYTES) {
                skipped.add(rel)
            } else {
                files.add(LocalFile(rel, f, size))
            }
        }
        LocalScan(files.sortedBy { it.relativePath }, skipped.sorted())
    }

    // --- Thao tác dữ liệu từ xa ----------------------------------------------

    data class RemoteEntry(
        val path: String,
        val size: Long,
        val isBinary: Boolean,
        val updatedAt: String
    )

    suspend fun fetchRemote(profile: String): List<RemoteEntry> {
        ensureSchema()
        val res = query(
            "select file_path, size, is_binary, updated_at from delta_configs " +
                "where profile_name = \$1 order by file_path",
            listOf(profile)
        )
        val rows = res.optJSONArray("rows") ?: JSONArray()
        val out = ArrayList<RemoteEntry>(rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            out.add(
                RemoteEntry(
                    path = r.optString("file_path"),
                    size = r.optString("size").toLongOrNull() ?: 0L,
                    isBinary = r.optBoolean("is_binary", false),
                    updatedAt = r.optString("updated_at")
                )
            )
        }
        return out
    }

    /**
     * Tải toàn bộ config trên máy lên DB dưới tên [profile]. DB sẽ phản chiếu
     * đúng nội dung thư mục Workspace hiện tại: tệp mới/đổi được cập nhật, tệp
     * đã xoá khỏi máy cũng bị xoá khỏi DB.
     *
     * @param onProgress callback (đãXong, tổng, tênTệp) để cập nhật UI.
     * @return số tệp đã tải lên.
     */
    suspend fun syncUp(
        profile: String,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): Int {
        ensureSchema()
        val scan = scanLocal()
        val local = scan.files
        val remotePaths = fetchRemote(profile).map { it.path }.toSet()
        val localPaths = local.map { it.relativePath }.toSet()

        local.forEachIndexed { index, lf ->
            onProgress(index, local.size, lf.relativePath)
            val bytes = withContext(Dispatchers.IO) { lf.file.readBytes() }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val binary = looksBinary(bytes)
            query(
                "insert into delta_configs(profile_name, file_path, content, size, is_binary, updated_at) " +
                    "values (\$1, \$2, \$3, \$4, \$5, now()) " +
                    "on conflict (profile_name, file_path) do update set " +
                    "content = excluded.content, size = excluded.size, " +
                    "is_binary = excluded.is_binary, updated_at = now()",
                listOf(profile, lf.relativePath, encoded, lf.size, binary)
            )
            onProgress(index + 1, local.size, lf.relativePath)
        }

        // Xoá khỏi DB những tệp không còn trên máy.
        for (stale in remotePaths - localPaths) {
            query(
                "delete from delta_configs where profile_name = \$1 and file_path = \$2",
                listOf(profile, stale)
            )
        }
        return local.size
    }

    /**
     * Tải config từ DB về thư mục Workspace của máy này. Ghi đè tệp trùng tên,
     * tạo thư mục con nếu cần. Không xoá các tệp local không có trên DB.
     *
     * @return số tệp đã ghi.
     */
    suspend fun syncDown(
        profile: String,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        ensureSchema()
        val res = query(
            "select file_path, content from delta_configs where profile_name = \$1 order by file_path",
            listOf(profile)
        )
        val rows = res.optJSONArray("rows") ?: JSONArray()
        val total = rows.length()
        val root = workspaceDir
        if (!root.exists()) root.mkdirs()

        for (i in 0 until total) {
            val r = rows.getJSONObject(i)
            val rel = r.optString("file_path")
            onProgress(i, total, rel)
            val bytes = Base64.decode(r.optString("content"), Base64.NO_WRAP)
            val target = File(root, rel)
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            target.writeBytes(bytes)
            onProgress(i + 1, total, rel)
        }
        total
    }

    suspend fun deleteRemote(profile: String): Int {
        ensureSchema()
        val res = query(
            "delete from delta_configs where profile_name = \$1",
            listOf(profile)
        )
        return res.optInt("rowCount", 0)
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 8000)
        for (i in 0 until limit) {
            if (bytes[i].toInt() == 0) return true
        }
        return false
    }
}
