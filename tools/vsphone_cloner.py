import os
import requests
import subprocess
import shutil
import re
import glob
import time
import json
import hashlib

# Configuration
API_URL = "https://api.vsphone.com/vsphone/api/appVersion/getApkUrl?channelName=seo"
WEBHOOK_URL = "https://discord.com/api/webhooks/1472581349964517447/RGUKMSuN1MMfJIFeoMByz1uZ8v_q-aPYQ-qKUzGjO2wTyo5JfzgpBaebme_EJDdlk1gv"
VERSION_FILE = ".github/vsphone_version.txt"
TOOLS_DIR = os.getcwd() # Assumes tools are in current working directory during execution
APKTOOL_JAR = "apktool.jar"
UBER_SIGNER_JAR = "uber-apk-signer.jar"

# VSPhone Credentials
VSPHONE_USER = "contradict6016@lordofmysteries.org"
VSPHONE_PASS = "155260"

class VSPhoneClient:
    def __init__(self):
        self.base_url = "https://api.vsphone.com/vsphone/api"
        self.origin = "https://cloud.vsphone.com"
        self.app_version = "2001701"
        self.token = None
        self.user_id = None

    def get_headers(self):
        return {
            'accept': 'application/json, text/plain, */*',
            'appversion': self.app_version,
            'clienttype': 'web',
            'content-type': 'application/json',
            'origin': self.origin,
            'referer': self.origin + '/',
            'requestsource': 'wechat-miniapp',
            'suppliertype': '0',
            'token': self.token or '',
            'userid': str(self.user_id) if self.user_id else ''
        }

    def login(self, username, password):
        print(f"Logging in to VSPhone as {username}...")
        url = f"{self.base_url}/user/login"
        payload = {
            "mobilePhone": username,
            "loginType": 1,
            "channel": "web",
            "password": hashlib.md5(password.encode()).hexdigest()
        }

        try:
            resp = requests.post(url, json=payload, headers=self.get_headers())
            data = resp.json()
            if data.get('code') == 200 and data.get('data'):
                self.token = data['data']['token']
                self.user_id = data['data']['userId']
                print("Login successful. UserID:", self.user_id)
                return True
            print("Login failed:", data.get('msg'))
            return False
        except Exception as e:
            print("Login error:", e)
            return False

    def upload_file(self, file_path, file_name):
        if not self.token: return None

        print(f"Preparing upload for {file_name}...")
        file_size = os.path.getsize(file_path)
        with open(file_path, 'rb') as f:
            file_md5 = hashlib.md5(f.read()).hexdigest()

        # Check upload
        check_url = f"{self.base_url}/cloudFile/uploadCheck"
        check_payload = {
            "fileItems": [{
                "fileLength": file_size,
                "fileMd5": file_md5,
                "fileType": 2, # APK/Backup type
                "fileName": file_name
            }],
            "operType": 2
        }

        try:
            check_resp = requests.post(check_url, json=check_payload, headers=self.get_headers())
            check_data = check_resp.json()

            if check_data.get('code') == 200:
                if check_data.get('data'):
                    # Instant upload (already exists)
                    print("File already exists on server.")
                    return check_data['data'][0]['downloadUrl']
                else:
                    # Finish upload (simulate actual upload flow)
                    print("Registering file upload (assuming pre-existence or instant finish)...")
                    finish_url = f"{self.base_url}/padTask/updateCloudFileFinish"
                    ext = file_name.split('.')[-1]
                    finish_payload = {
                        "fileId": 0,
                        "filePath": f"userFile/{file_md5}.{ext}",
                        "fileOriginName": file_name
                    }
                    finish_resp = requests.post(finish_url, json=finish_payload, headers=self.get_headers())
                    finish_data = finish_resp.json()

                    if finish_data.get('code') == 200 and finish_data.get('data'):
                         url = finish_data['data']['downloadUrl']
                         print(f"Upload registered successfully: {url}")
                         return url
                    else:
                         print(f"Finish upload failed: {finish_data}")
            else:
                print("Upload check failed:", check_data.get('msg'))
                print("Full Check Response:", check_data)
        except Exception as e:
            print("Upload error:", e)
        return None

def get_online_version_info():
    try:
        # The API redirects (302) to the file URL. We need to catch that redirect.
        response = requests.get(API_URL, allow_redirects=False)

        if response.status_code in (301, 302, 307):
            url = response.headers.get('Location')
            if url:
                 # Extract version from URL using regex
                 # Example: https://file.vsphone.com/common/appFile/vsphone-app-v1.0.41.1_seo.apk?t=1771161455225
                 match = re.search(r"vsphone-app-v([\d\.]+)_seo\.apk", url)
                 if match:
                     return match.group(1), url
                 else:
                     print(f"Could not extract version from URL: {url}")
                     return None, None
            else:
                print("Redirected but no Location header found.")
                return None, None
        elif response.status_code == 200:
             # Fallback if it returns JSON (though current observation says otherwise)
             try:
                 data = response.json()
                 if data.get("code") == 200 and data.get("data"):
                     url = data["data"]
                     match = re.search(r"vsphone-app-v([\d\.]+)_seo\.apk", url)
                     if match:
                         return match.group(1), url
             except:
                 pass
             print(f"Unexpected 200 response without valid JSON/Data: {response.text[:100]}")
             return None, None
        else:
            print(f"API response failed with status {response.status_code}")
            return None, None
    except Exception as e:
        print(f"Error fetching version: {e}")
        return None, None

def get_local_version():
    if os.path.exists(VERSION_FILE):
        with open(VERSION_FILE, "r") as f:
            return f.read().strip()
    return "0.0.0"

def save_local_version(version):
    with open(VERSION_FILE, "w") as f:
        f.write(version)

def download_file(url, filename):
    print(f"Downloading {url} to {filename}...")
    with requests.get(url, stream=True) as r:
        r.raise_for_status()
        with open(filename, 'wb') as f:
            for chunk in r.iter_content(chunk_size=8192):
                f.write(chunk)
    print("Download complete.")

def run_command(command):
    print(f"Running: {' '.join(command)}")
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Command failed: {result.stderr}")
        raise Exception(f"Command failed: {result.stderr}")
    return result.stdout, result.stderr

def modify_manifest(manifest_path, clone_index):
    print(f"Modifying {manifest_path} for clone {clone_index}...")
    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Change package name
    new_package = f"com.kasumi.clone{clone_index}"
    # Regex to find package="com.something"
    content = re.sub(r'package="[^"]+"', f'package="{new_package}"', content)

    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(content)

def modify_strings(strings_path, clone_index):
    print(f"Modifying {strings_path} for clone {clone_index}...")
    if not os.path.exists(strings_path):
        print(f"Warning: Strings file not found at {strings_path}")
        return

    with open(strings_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Change app name
    new_name = f"Kasumi-{clone_index}"
    # Regex to find <string name="app_name">Old Name</string>
    # Note: It might be spread across lines or have attributes.
    content = re.sub(r'<string name="app_name">.*?</string>', f'<string name="app_name">{new_name}</string>', content, flags=re.DOTALL)

    with open(strings_path, "w", encoding="utf-8") as f:
        f.write(content)

def send_discord_notification(links, original_version):
    print("Sending Discord notification...")
    embeds = []

    # Updated description to be cleaner and in Vietnamese
    description = f"VSPhone phiên bản mới {original_version} !\n\n"

    for name, link in links.items():
        # Using [Download](url) format as requested
        description += f"**{name}**: [Download]({link})\n"

    payload = {
        # Removed "content" to send only embed
        "embeds": [{
            "title": f"Phiên bản mới Kasumi",
            "description": description,
            "color": 3447003
        }]
    }

    try:
        response = requests.post(WEBHOOK_URL, json=payload)
        response.raise_for_status()
        print("Notification sent.")
    except Exception as e:
        print(f"Failed to send notification: {e}")

def main():
    print("Starting VSPhone Cloner...")

    # Initialize VSPhone Client first to check if we can login
    client = VSPhoneClient()
    if not client.login(VSPHONE_USER, VSPHONE_PASS):
        print("Failed to login to VSPhone. Aborting.")
        return

    online_version, download_url = get_online_version_info()

    if not online_version:
        print("Could not retrieve online version.")
        return

    local_version = get_local_version()
    print(f"Online version: {online_version}, Local version: {local_version}")

    if online_version == local_version:
        print("Versions match. No update needed.")
        return

    print("Update found! Starting cloning process...")

    # Setup directories
    work_dir = "temp_work"
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(work_dir)

    apk_path = os.path.join(work_dir, "original.apk")
    download_file(download_url, apk_path)

    links = {}

    # Decode once
    decoded_dir = os.path.join(work_dir, "decoded")
    print("Decoding APK...")
    out, err = run_command(["java", "-jar", APKTOOL_JAR, "d", "-f", "-o", decoded_dir, apk_path])
    print(out)
    if err: print(f"Stderr: {err}")

    # Loop for 5 clones
    for i in range(1, 6):
        clone_name = f"Kasumi-{i}"
        print(f"\nProcessing {clone_name}...")

        modify_manifest(os.path.join(decoded_dir, "AndroidManifest.xml"), i)
        modify_strings(os.path.join(decoded_dir, "res", "values", "strings.xml"), i)

        unsigned_apk = os.path.join(work_dir, f"unsigned_{i}.apk")
        print("Building APK...")
        out, err = run_command(["java", "-jar", APKTOOL_JAR, "b", decoded_dir, "-o", unsigned_apk])
        print(out)
        if err: print(f"Stderr: {err}")

        # Verify built APK exists
        if not os.path.exists(unsigned_apk):
             print(f"Error: Build failed, {unsigned_apk} not found.")
             continue

        print(f"Unsigned APK size: {os.path.getsize(unsigned_apk)} bytes")

        print("Signing APK...")
        # uber-apk-signer with --overwrite modifies the file in place
        out, err = run_command(["java", "-jar", UBER_SIGNER_JAR, "-a", unsigned_apk, "--allowResign", "--overwrite"])
        print(out)
        if err: print(f"Stderr: {err}")

        # Since we used --overwrite, the signed APK is just unsigned_apk
        signed_apk = unsigned_apk

        if not os.path.exists(signed_apk):
             print(f"Error: Signed APK not found at {signed_apk}")
             continue

        final_apk_name = f"{clone_name}_v{online_version}.apk"
        final_apk_path = os.path.join(work_dir, final_apk_name)
        os.rename(signed_apk, final_apk_path)

        # Use VSPhone Client upload instead of Catbox
        print(f"Uploading {clone_name} to VSPhone Cloud...")
        link = client.upload_file(final_apk_path, final_apk_name)

        if link:
            links[clone_name] = link
            print(f"Uploaded {clone_name}: {link}")
        else:
            print(f"Failed to upload {clone_name}")

        # Add a small delay to avoid rate limiting
        time.sleep(2)

    if links:
        send_discord_notification(links, online_version)
        save_local_version(online_version)
        print("Update complete and notified.")
    else:
        print("No links generated. Something went wrong.")

    # Cleanup
    # shutil.rmtree(work_dir) # Optional: keep for debugging if needed, but in CI it's wiped anyway

if __name__ == "__main__":
    main()
