package com.kasumi.tool

import android.content.Context
import androidx.annotation.StringRes

/**
 * A user-facing message produced outside the UI layer.
 *
 * ViewModels and managers must not hold a [Context], so they cannot resolve
 * string resources themselves. They emit a [UiText] instead and the composable
 * that displays it calls [asString]. This keeps every user-visible string in
 * `strings.xml` while leaving the domain layer Context-free and unit-testable.
 */
sealed interface UiText {

    /** A localised string resource, optionally with format arguments. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * Text that is already final — device output, an exception message, a value
     * echoed back from a server. Not translatable by definition.
     */
    data class Raw(val value: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.toTypedArray())
        }
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}
