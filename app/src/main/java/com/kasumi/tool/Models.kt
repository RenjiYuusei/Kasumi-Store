package com.kasumi.tool

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer
import java.util.UUID

enum class SortMode { NAME_ASC, NAME_DESC, SIZE_DESC, DATE_DESC }
data class FileStats(val exists: Boolean, val size: Long, val lastModified: Long)

data class ApkItem(
    val id: String,
    val name: String,
    val sourceType: SourceType,
    val url: String?,
    val uri: String?,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconUrl: String? = null
) {
    companion object {
        private val gson = GsonBuilder()
            .registerTypeAdapter(ApkItem::class.java, object : TypeAdapter<ApkItem>() {
                override fun write(out: JsonWriter, value: ApkItem?) {
                    if (value == null) {
                        out.nullValue()
                        return
                    }
                    out.beginObject()
                    out.name("id").value(value.id)
                    out.name("name").value(value.name)
                    out.name("sourceType").value(value.sourceType.name)
                    out.name("url").value(value.url)
                    out.name("uri").value(value.uri)
                    out.name("versionName").value(value.versionName)
                    out.name("versionCode")
                    if (value.versionCode == null) out.nullValue() else out.value(value.versionCode)
                    out.name("iconUrl").value(value.iconUrl)
                    out.endObject()
                }

                override fun read(input: JsonReader): ApkItem {
                    if (input.peek() == JsonToken.NULL) {
                        input.nextNull()
                        throw IllegalStateException("ApkItem cannot be null")
                    }

                    var id: String? = null
                    var name: String? = null
                    var sourceType: SourceType = SourceType.URL
                    var url: String? = null
                    var uri: String? = null
                    var versionName: String? = null
                    var versionCode: Long? = null
                    var iconUrl: String? = null

                    input.beginObject()
                    while (input.hasNext()) {
                        val propertyName = input.nextName()
                        if (input.peek() == JsonToken.NULL) {
                            input.nextNull()
                            continue
                        }
                        when (propertyName) {
                            "id" -> id = input.nextString()
                            "name" -> name = input.nextString()
                            "sourceType" -> {
                                sourceType = try {
                                    SourceType.valueOf(input.nextString())
                                } catch (_: Exception) {
                                    SourceType.URL
                                }
                            }
                            "url" -> url = input.nextString()
                            "uri" -> uri = input.nextString()
                            "versionName" -> versionName = input.nextString()
                            "versionCode" -> versionCode = input.nextLong()
                            "iconUrl" -> iconUrl = input.nextString()
                            else -> input.skipValue()
                        }
                    }
                    input.endObject()

                    return ApkItem(
                        id = id ?: UUID.randomUUID().toString(),
                        name = name ?: "APK",
                        sourceType = sourceType,
                        url = url,
                        uri = uri,
                        versionName = versionName,
                        versionCode = versionCode,
                        iconUrl = iconUrl
                    )
                }
            })
            .create()

        fun toJsonList(list: List<ApkItem>): String {
            return gson.toJson(list)
        }

        fun fromJsonList(json: String?): List<ApkItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                gson.fromJson(json, Array<ApkItem>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun writeListTo(list: List<ApkItem>, writer: Writer) {
            val type = object : TypeToken<List<ApkItem>>() {}.type
            gson.toJson(list, type, writer)
        }

        fun readListFrom(reader: Reader): List<ApkItem> {
            val type = object : TypeToken<List<ApkItem>>() {}.type
            return gson.fromJson<List<ApkItem>>(reader, type) ?: emptyList()
        }
    }
}

enum class SourceType { URL, LOCAL }

data class PreloadApp(
    val name: String,
    val url: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconUrl: String? = null
)
