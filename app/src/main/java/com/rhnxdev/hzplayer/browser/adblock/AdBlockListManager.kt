package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import androidx.annotation.StringRes
import com.rhnxdev.hzplayer.R
import java.io.File

data class FilterListDescriptor(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val rawUrl: String,
    val assetPath: String? = null,
    val defaultEnabled: Boolean = true,
)

object AdBlockListManager {

    val BUILTIN_LISTS = listOf(
        FilterListDescriptor(
            id = "easylist",
            nameRes = R.string.browser_filter_easylist,
            descriptionRes = R.string.browser_filter_easylist_desc,
            rawUrl = "https://raw.githubusercontent.com/easylist/easylist/master/easylist/easylist.txt",
            assetPath = "adblock/default_easylist.txt",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "easyprivacy",
            nameRes = R.string.browser_filter_easyprivacy,
            descriptionRes = R.string.browser_filter_easyprivacy_desc,
            rawUrl = "https://raw.githubusercontent.com/easylist/easylist/master/easyprivacy/easyprivacy.txt",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "peter_lowe",
            nameRes = R.string.browser_filter_peter_lowe,
            descriptionRes = R.string.browser_filter_peter_lowe_desc,
            rawUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "ublock_filters",
            nameRes = R.string.browser_filter_ublock,
            descriptionRes = R.string.browser_filter_ublock_desc,
            rawUrl = "https://raw.githubusercontent.com/gorhill/uBlock/master/assets/ublock/filters.txt",
            defaultEnabled = true,
        ),
    )

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, "adblock_lists")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getListFile(context: Context, listId: String): File {
        return File(getStorageDir(context), "$listId.txt")
    }

    /**
     * Ensures default bundled filter lists are copied to internal storage if missing.
     */
    fun ensureDefaultAssets(context: Context) {
        BUILTIN_LISTS.forEach { list ->
            val targetFile = getListFile(context, list.id)
            if (!targetFile.exists() && list.assetPath != null) {
                try {
                    context.assets.open(list.assetPath).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Reads raw text contents of all active filter list files and custom rules
     * for passing directly to native adblock-rust engine parser.
     */
    fun readActiveFilterContents(
        context: Context,
        enabledListIds: Set<String>,
        customRules: String = "",
    ): List<String> {
        ensureDefaultAssets(context)
        val contents = mutableListOf<String>()

        BUILTIN_LISTS.filter { enabledListIds.contains(it.id) }.forEach { descriptor ->
            val file = getListFile(context, descriptor.id)
            if (file.exists()) {
                val content = try { file.readText() } catch (_: Exception) { "" }
                if (content.isNotBlank()) {
                    contents.add(content)
                }
            }
        }

        if (customRules.isNotBlank()) {
            contents.add(customRules)
        }

        return contents
    }
}
