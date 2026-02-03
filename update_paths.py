import os

file_path = 'app/src/main/java/com/kasumi/tool/MainActivity.kt'

with open(file_path, 'r') as f:
    content = f.read()

# Define the new code to insert
new_code = """
    private val PATH_DELTA_LEGACY = "/storage/emulated/0/Delta"
    private val PATH_DELTA_VNG = "/storage/emulated/0/Android/data/com.roblox.client.vnggames/files/gloop/external/Delta"

    private fun getDeltaDir(): File {
        val vng = File(PATH_DELTA_VNG)
        if (vng.exists()) return vng
        return File(PATH_DELTA_LEGACY)
    }

"""

# Insert before loadScriptsFromLocal
target_str = "private suspend fun loadScriptsFromLocal() {"
if target_str in content:
    content = content.replace(target_str, new_code + target_str)
else:
    print("Error: Could not find insertion point")
    exit(1)

# Replace hardcoded paths
replacements = [
    ('File("/storage/emulated/0/Delta/Autoexecute")', 'File(getDeltaDir(), "Autoexecute")'),
    ('File("/storage/emulated/0/Delta/Scripts")', 'File(getDeltaDir(), "Scripts")'),
    ('File("/storage/emulated/0/Delta/")', 'File(getDeltaDir(), targetFolder)')
]

for old, new in replacements:
    content = content.replace(old, new)

with open(file_path, 'w') as f:
    f.write(content)

print("Successfully updated MainActivity.kt")
