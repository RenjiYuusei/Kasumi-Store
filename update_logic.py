import sys

file_path = 'app/src/main/java/com/kasumi/tool/AutoRejoinManager.kt'
with open(file_path, 'r') as f:
    lines = f.readlines()

# Replace Step 3
start_3 = 353 # 0-indexed is 354
end_3 = 378   # 0-indexed is 379
new_step_3 = [
    "        // Step 3: logcat — dòng join URL mới nhất (xem doc ở trên). Luôn quét\n",
    "        // logcat nếu chưa có accessCode (để chắc chắn svv/svth).\n",
    "        if (placeId.isNullOrEmpty() || accessCode.isNullOrEmpty()) {\n",
    "            val logR = executeAsRoot(\n",
    "                \"logcat -d -t 20000 2>/dev/null | grep -iE 'placeid=[0-9]' | \" +\n",
    "                    \"grep -iE 'rbx\\\\.web|games/start|gameinstanceid|accesscode|roblox://' | tail -80\",\n",
    "                timeoutSec = 15L\n",
    "            )\n",
    "            val logLines = logR.output.lineSequence().toList()\n",
    "            // 1. Tìm placeId mới nhất từ logcat nếu dumpsys chưa có.\n",
    "            if (placeId.isNullOrEmpty()) {\n",
    "                placeId = logLines.lastOrNull { PLACE_URL_REGEX.containsMatchIn(it) }\n",
    "                    ?.let { PLACE_URL_REGEX.find(it)?.groupValues?.getOrNull(1) }\n",
    "            }\n",
    "\n",
    "            // 2. Tìm metadata (accessCode/gid) từ dòng join URL tốt nhất.\n",
    "            // \"Tốt nhất\" = dòng mới nhất có cùng placeId và chứa accessCode hoặc log từ rbx.web.\n",
    "            if (!placeId.isNullOrEmpty()) {\n",
    "                val bestLine = logLines.lastOrNull { line ->\n",
    "                    PLACE_URL_REGEX.find(line)?.groupValues?.getOrNull(1) == placeId &&\n",
    "                        (ACCESS_CODE_REGEX.containsMatchIn(line) || line.contains(\"rbx.web\", ignoreCase = true))\n",
    "                } ?: logLines.lastOrNull { line ->\n",
    "                    // Fallback: dòng cuối cùng có placeId này.\n",
    "                    PLACE_URL_REGEX.find(line)?.groupValues?.getOrNull(1) == placeId\n",
    "                }\n",
    "\n",
    "                if (bestLine != null) {\n",
    "                    if (gid.isNullOrEmpty()) {\n",
    "                        gid = GID_LOG_REGEX.find(bestLine)?.groupValues?.getOrNull(1)\n",
    "                            ?.takeIf { it.isNotBlank() }\n",
    "                    }\n",
    "                    if (accessCode.isNullOrEmpty()) {\n",
    "                        accessCode = ACCESS_CODE_REGEX.find(bestLine)?.groupValues?.getOrNull(1)\n",
    "                            ?.takeIf { it.isNotBlank() }\n",
    "                    }\n",
    "                }\n",
    "            }\n",
    "        }\n"
]

# Replace return logic
start_ret = 395 # 0-indexed is 396
end_ret = 405   # 0-indexed is 406
new_ret = [
    "        // svv ⟺ có accessCode (dấu hiệu server riêng/VIP). gameInstanceId\n",
    "        // chỉ là định danh instance, không đủ để coi là svv nếu thiếu\n",
    "        // accessCode (ví dụ svth cũng có gid trong dumpsys).\n",
    "        val isPrivate = !accessCode.isNullOrEmpty()\n",
    "        return DetectedGame(\n",
    "            placeId = placeId,\n",
    "            gameInstanceId = gid,\n",
    "            accessCode = accessCode,\n",
    "            isPrivateServer = isPrivate,\n",
    "        )\n"
]

updated_lines = lines[:start_3] + new_step_3 + lines[end_3:start_ret] + new_ret + lines[end_ret:]

with open(file_path, 'w') as f:
    f.writelines(updated_lines)
