package com.kasumi.tool

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScriptUtils {
    /**
     * Merges online scripts with local scripts.
     *
     * Logic:
     * 1. Matches local scripts to online scripts by name.
     * 2. Updates matching online scripts with the local path.
     * 3. Appends unmatched local scripts to the end of the list.
     *
     * @param onlineScripts List of scripts fetched from online source.
     * @param localScripts List of scripts found in local storage.
     * @return A merged list of ScriptItems.
     */
    fun mergeScripts(onlineScripts: List<ScriptItem>, localScripts: List<ScriptItem>): List<ScriptItem> {
        // OPTIMIZATION: Use Map for O(1) lookup (O(N+M) complexity)
        val localMap = HashMap<String, ScriptItem>()
        for (local in localScripts) {
            // localScripts logic often prioritizes the first found item if duplicates exist
            if (!localMap.containsKey(local.name)) {
                localMap[local.name] = local
            }
        }

        val mergedList = onlineScripts.map { onlineScript ->
            val match = localMap[onlineScript.name]
            if (match != null) {
                onlineScript.copy(localPath = match.localPath)
            } else {
                onlineScript
            }
        }

        // Efficiently identify used paths to filter unmatched locals
        val usedLocalPaths = HashSet<String>()
        for (item in mergedList) {
            if (item.localPath != null) {
                usedLocalPaths.add(item.localPath)
            }
        }

        val unmatchedLocals = localScripts.filter { local ->
            local.localPath != null && !usedLocalPaths.contains(local.localPath)
        }

        return mergedList + unmatchedLocals
    }

    /**
     * Saves the script content to a file in the specified directory.
     * This operation is performed on the IO dispatcher to avoid blocking the caller.
     *
     * @param directory The target directory.
     * @param fileName The name of the file to save (should include extension).
     * @param content The content to write.
     * @return The File object that was written.
     */
    suspend fun saveScriptToFile(directory: File, fileName: String, content: String): File = withContext(Dispatchers.IO) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, fileName)
        file.writeText(content)
        file
    }
}
