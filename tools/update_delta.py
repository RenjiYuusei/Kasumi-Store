import os
import re
import json
import requests
import hashlib
from urllib.parse import urljoin
from bs4 import BeautifulSoup
import cloudscraper
from androguard.core.apk import APK

# Configuration
APPS_JSON_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'source', 'apps.json')
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# VSPhone Credentials (Hardcoded)
VSPHONE_USER = "shallow9210@whitehousecalculator.com"
VSPHONE_PASS = "871985"

DEFAULT_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
}

DELTA_INTL_OFFICIAL_URL = "https://delta.filenetwork.vip/android.html"
DELTA_VNG_OFFICIAL_URL = "https://gloopup.net/Delta/android_vn/"


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
            resp = requests.post(url, json=payload, headers=self.get_headers(), timeout=30)
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
        if not self.token:
            return None

        print(f"Preparing upload for {file_name}...")
        file_size = os.path.getsize(file_path)
        with open(file_path, 'rb') as f:
            file_md5 = hashlib.md5(f.read()).hexdigest()

        check_url = f"{self.base_url}/cloudFile/uploadCheck"
        check_payload = {
            "fileItems": [{
                "fileLength": file_size,
                "fileMd5": file_md5,
                "fileType": 2,
                "fileName": file_name
            }],
            "operType": 2
        }

        try:
            check_resp = requests.post(check_url, json=check_payload, headers=self.get_headers(), timeout=30)
            check_data = check_resp.json()

            if check_data.get('code') == 200:
                if check_data.get('data'):
                    print("File already exists on server.")
                    return check_data['data'][0]['downloadUrl']

                print("Registering file upload (assuming pre-existence or instant finish)...")
                finish_url = f"{self.base_url}/padTask/updateCloudFileFinish"
                ext = file_name.split('.')[-1]
                finish_payload = {
                    "fileId": 0,
                    "filePath": f"userFile/{file_md5}.{ext}",
                    "fileOriginName": file_name
                }
                finish_resp = requests.post(finish_url, json=finish_payload, headers=self.get_headers(), timeout=30)
                finish_data = finish_resp.json()

                if finish_data.get('code') == 200 and finish_data.get('data'):
                    url = finish_data['data']['downloadUrl']
                    print(f"Upload registered successfully: {url}")
                    return url

                print(f"Finish upload failed: {finish_data}")
            else:
                print("Upload check failed:", check_data.get('msg'))
                print("Full Check Response:", check_data)
        except Exception as e:
            print("Upload error:", e)

        return None


def resolve_official_download_link(page_url):
    """
    Resolve official Delta page URL into final APK URL.
    Supports HTTP redirects and HTML redirects (canonical/meta refresh/apk anchor).
    """
    session = requests.Session()
    session.headers.update(DEFAULT_HEADERS)

    try:
        response = session.get(page_url, allow_redirects=True, timeout=30)
        final_url = response.url

        if re.search(r'\.apk(?:$|\?)', final_url, re.IGNORECASE):
            return final_url

        soup = BeautifulSoup(response.text or "", 'html.parser')

        canonical = soup.find('link', attrs={'rel': 'canonical'})
        if canonical and canonical.get('href'):
            canonical_url = canonical.get('href').strip()
            if re.search(r'\.apk(?:$|\?)', canonical_url, re.IGNORECASE):
                return canonical_url

        meta_refresh = soup.find('meta', attrs={'http-equiv': re.compile(r'refresh', re.IGNORECASE)})
        if meta_refresh and meta_refresh.get('content'):
            match = re.search(r'url\s*=\s*(.+)$', meta_refresh['content'], re.IGNORECASE)
            if match:
                refresh_url = match.group(1).strip().strip('"\'')
                refresh_url = urljoin(response.url, refresh_url)
                if re.search(r'\.apk(?:$|\?)', refresh_url, re.IGNORECASE):
                    return refresh_url

        apk_anchor = soup.find('a', href=re.compile(r'\.apk(?:$|\?)', re.IGNORECASE))
        if apk_anchor and apk_anchor.get('href'):
            return urljoin(response.url, apk_anchor.get('href').strip())

        return None
    except Exception as e:
        print(f"Error resolving official link from {page_url}: {e}")
        return None


def download_file(url, output_path):
    print(f"Downloading {url} to {output_path}...")
    try:
        scraper = cloudscraper.create_scraper()
        with scraper.get(url, stream=True, timeout=60) as r:
            r.raise_for_status()
            with open(output_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
        return True
    except Exception as e:
        print(f"Download failed: {e}")
        return False


def get_apk_version(apk_path):
    try:
        apk = APK(apk_path)
        version_name = apk.get_androidversion_name()

        if isinstance(version_name, bytes):
            version_name = version_name.decode('utf-8', errors='ignore')
        elif isinstance(version_name, str) and version_name.startswith("b'") and version_name.endswith("'"):
            version_name = version_name[2:-1]

        print(f"Extracted version from APK: {version_name}")
        return version_name
    except Exception as e:
        print(f"Error analyzing APK: {e}")
        return None


def process_app_update(client, apps_data, app_name_keyword, official_page_url, output_name_prefix):
    target_app = next((a for a in apps_data if app_name_keyword in a['name']), None)
    if not target_app:
        print(f"App {app_name_keyword} not found in apps.json")
        return False

    print(f"Processing {target_app['name']} from official source: {official_page_url}")
    apk_link = resolve_official_download_link(official_page_url)
    if not apk_link:
        print("Could not resolve official APK link.")
        return False

    print(f"Resolved official APK link: {apk_link}")

    new_file_path = os.path.join(BASE_DIR, f"{output_name_prefix}_new.apk")
    if not download_file(apk_link, new_file_path):
        return False

    apk_version = get_apk_version(new_file_path)
    if not apk_version:
        print("Failed to get version from new APK. Aborting update.")
        if os.path.exists(new_file_path):
            os.remove(new_file_path)
        return False

    current_version = target_app.get('versionName')
    print(f"Current Version: {current_version}, New APK Version: {apk_version}")

    clean_version = "".join(c for c in str(apk_version) if c.isalnum() or c in ".-_")
    upload_name = f"{output_name_prefix}_{clean_version}.apk"

    print("Uploading/Checking file on VSPhone...")
    new_url = client.upload_file(new_file_path, upload_name)

    if os.path.exists(new_file_path):
        os.remove(new_file_path)

    if not new_url:
        print("Upload failed.")
        return False

    if new_url == target_app.get('url') and apk_version == current_version:
        print("File content and version unchanged.")
        return False

    target_app['url'] = new_url
    target_app['versionName'] = apk_version
    print(f"Updated {target_app['name']} to version {apk_version} @ {new_url}")
    return True


def main():
    if not os.path.exists(APPS_JSON_PATH):
        print(f"Error: {APPS_JSON_PATH} not found.")
        return

    with open(APPS_JSON_PATH, 'r', encoding='utf-8') as f:
        apps_data = json.load(f)

    client = VSPhoneClient()
    if not client.login(VSPHONE_USER, VSPHONE_PASS):
        print("Aborting due to login failure.")
        return

    any_update = False

    if process_app_update(client, apps_data, "Roblox Quốc Tế (Delta)", DELTA_INTL_OFFICIAL_URL, "delta_intl"):
        any_update = True

    if process_app_update(client, apps_data, "Roblox VN (Delta)", DELTA_VNG_OFFICIAL_URL, "delta_vng"):
        any_update = True

    if any_update:
        print("Saving apps.json...")
        with open(APPS_JSON_PATH, 'w', encoding='utf-8') as f:
            json.dump(apps_data, f, indent=2, ensure_ascii=False)
    else:
        print("No updates made.")


if __name__ == "__main__":
    main()
