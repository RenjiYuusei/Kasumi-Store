package com.kasumi.tool

data class ScriptItem(
    val id: String,
    val name: String,
    val gameName: String,
    val url: String? = null,
    val localPath: String? = null
)
