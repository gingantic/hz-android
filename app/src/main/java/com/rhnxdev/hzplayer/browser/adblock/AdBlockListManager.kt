package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import java.io.File

data class FilterListDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val rawUrl: String,
    val assetPath: String? = null,
    val defaultEnabled: Boolean = true,
)

object AdBlockListManager {

    val BUILTIN_LISTS = listOf(
        FilterListDescriptor(
            id = "easylist",
            name = "EasyList",
            description = "Standard ad-blocking list (ads, popups, banners)",
            rawUrl = "https://raw.githubusercontent.com/easylist/easylist/master/easylist/easylist.txt",
            assetPath = "adblock/default_easylist.txt",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "easyprivacy",
            name = "EasyPrivacy",
            description = "Blocks telemetry, tracking scripts, and analytics",
            rawUrl = "https://raw.githubusercontent.com/easylist/easylist/master/easyprivacy/easyprivacy.txt",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "peter_lowe",
            name = "Peter Lowe's Ad & Tracking List",
            description = "Adservers and tracker hostname list",
            rawUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            defaultEnabled = true,
        ),
        FilterListDescriptor(
            id = "ublock_filters",
            name = "uBlock Filters (Core)",
            description = "Core fixes and unbreak rules from uBlock Origin",
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
