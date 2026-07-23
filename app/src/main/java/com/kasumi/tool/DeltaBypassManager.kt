package com.kasumi.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Gọi API bypass của Kasumi-Bypass (api_server.py, cổng mặc định 8078) để lấy
 * key Delta từ một link platoboost / platorelay.
 *
 * API phải được tự host công khai (ví dụ trên VPS) vì phần giải captcha + AES
 * của Delta không thể chạy trực tiếp trên điện thoại. App chỉ gửi HTTP:
 *
 *     GET  {base}/bypass?url=<link Delta>
 *
 * và nhận về JSON:
 *
 *     { "success": true, "key": "...", "minutes_left": 120, "elapsed": 4.2, "type": "delta" }
 *     { "success": false, "error": "Link expired", "type": "delta" }
 */
class DeltaBypassManager(private val client: OkHttpClient) {

    class BypassException(message: String) : Exception(message)

    data class BypassResult(
        val key: String,
        val minutesLeft: Int?,
        val elapsedSeconds: Double?
    )

    suspend fun bypass(apiBaseUrl: String, link: String): BypassResult =
        withContext(Dispatchers.IO) {
            val base = apiBaseUrl.trim().trimEnd('/')
            if (base.isEmpty()) {
                throw BypassException("Chưa cấu hình địa chỉ API. Hãy nhập URL API bạn đã host.")
            }
            val target = link.trim()
            if (target.isEmpty()) {
                throw BypassException("Hãy dán link Delta (platoboost/platorelay) hoặc key token.")
            }

            val httpBase = base.toHttpUrlOrNull()
                ?: throw BypassException("URL API không hợp lệ. Ví dụ đúng: http://1.2.3.4:8078")

            val url = httpBase.newBuilder()
                .addPathSegment("bypass")
                .addQueryParameter("url", target)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            val text: String
            val code: Int
            try {
                client.newCall(request).execute().use { resp ->
                    code = resp.code
                    text = resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                throw BypassException("Không kết nối được tới API: ${e.message}")
            }

            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw BypassException("Phản hồi không hợp lệ từ API (HTTP $code).")
            }

            if (!json.optBoolean("success", false)) {
                val err = json.optString("error").ifBlank {
                    if (code != 200) "Máy chủ trả về HTTP $code" else "Bypass thất bại"
                }
                throw BypassException(err)
            }

            val type = json.optString("type")
            if (type.isNotEmpty() && type != "delta") {
                throw BypassException("Link không phải Delta (API nhận diện: $type).")
            }

            val key = json.optString("key")
            if (key.isBlank()) {
                throw BypassException("API không trả về key.")
            }

            BypassResult(
                key = key,
                minutesLeft = if (json.has("minutes_left") && !json.isNull("minutes_left")) {
                    json.optInt("minutes_left")
                } else null,
                elapsedSeconds = if (json.has("elapsed") && !json.isNull("elapsed")) {
                    json.optDouble("elapsed")
                } else null
            )
        }
}
