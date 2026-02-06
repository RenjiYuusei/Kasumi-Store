package com.kasumi.tool

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
}
