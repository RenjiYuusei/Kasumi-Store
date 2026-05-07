package com.kasumi.tool

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lưu trữ các chuỗi nhạy cảm dưới dạng AES/GCM ciphertext để tránh bị lộ
 * khi decompile classes.dex. Khoá AES-256 được dựng tại runtime từ XOR
 * của hai mảng byte; cả hai mảng và các payload đều không xuất hiện ở
 * `const-string` nên việc grep URL trong APK sẽ không có kết quả.
 *
 * Mỗi payload có layout: [12-byte IV || ciphertext || 16-byte GCM tag].
 *
 * Khi cần xoay khoá, chạy lại `tools/secure_strings_codegen.py` rồi dán
 * lại các byte array bên dưới.
 */
internal object SecureStrings {

    private val key: ByteArray by lazy {
        val out = ByteArray(KEY_A.size)
        for (i in KEY_A.indices) {
            out[i] = (KEY_A[i].toInt() xor KEY_B[i].toInt()).toByte()
        }
        out
    }

    val appsUrl: String by lazy { decode(APPS_PAYLOAD) }
    val scriptsUrl: String by lazy { decode(SCRIPTS_PAYLOAD) }

    private fun decode(payload: ByteArray): String {
        val iv = payload.copyOfRange(0, IV_LEN)
        val ct = payload.copyOfRange(IV_LEN, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, ALGORITHM),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val KEY_A: ByteArray = byteArrayOf(
        70, 96, -21, -115, 54, -86, -82, 80, -78, -6, 41, -73, 82, 45, 48, -100,
        -3, -45, 33, -20, -55, 4, -116, -70, -69, 127, -25, -79, 101, 110, 55, -27
    )

    private val KEY_B: ByteArray = byteArrayOf(
        -41, 35, -112, 96, -30, 27, -103, -2, 66, 77, -77, -78, 127, 32, -58, 31,
        89, -7, 69, 38, -43, -93, -34, -32, 47, -41, 84, -116, 54, -30, 47, 19
    )

    private val APPS_PAYLOAD: ByteArray = byteArrayOf(
        10, 13, -63, 11, 52, -21, 64, 51, 10, 10, 118, -111, -19, 104, -123, 18,
        -126, 25, 115, -87, -127, 46, -67, -124, 73, 66, -110, 94, 92, -46, 37, 116,
        -89, 37, -60, 75, 76, 0, -34, -94, 43, -5, 45, 121, 4, 21, -31, -57,
        -13, 16, 82, 22, 71, -16, 12, 20, -9, 23, -126, 28, -128, 106, 36, 80,
        45, -110, 64, -116, -73, -18, -46, 91, -105, -58, -89, -100, 102, 102, 2, -98,
        -107, -39, -78, -60, 74, -3, 108, -79, 85, -94, -73, 11, 34, 46, 112, 31,
        -36, 113, -54, 89, -13, -52, 54, 118, 115, 59, -102, 95
    )

    private val SCRIPTS_PAYLOAD: ByteArray = byteArrayOf(
        68, 73, -46, 0, 96, -92, -100, 97, 108, -63, -27, -95, -11, 28, 112, -98,
        32, -21, -120, -98, 19, -60, 91, 82, -65, 124, 4, 105, -42, -44, -122, -89,
        74, 105, 4, -60, -55, -71, 125, -85, -116, -61, -112, 83, -48, 92, 126, 42,
        7, -109, -82, -102, -20, 40, -14, -113, 112, -100, 79, -110, -71, -116, 6, -31,
        -4, 110, 99, -83, -45, 86, 123, 53, 57, 19, -1, 45, -63, 59, -4, -126,
        -23, 46, -75, 26, -120, -33, -37, -91, 99, -64, -75, 103, 53, 36, -24, -67,
        -60, 14, 112, 5, 27, -120, -54, 66, -87, -54, -79, -4, -11, -77, 94
    )
}
