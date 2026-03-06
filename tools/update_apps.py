import os
import json
import hashlib
import requests
import time
import oss2
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup
from apksearch import APKMirror

APPS_JSON_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'source', 'apps.json')
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

VSPHONE_USER = "eligible1827@buzzcut.ws"
VSPHONE_PASS = "935826"


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

    def get_oss_info(self):
        url = f"{self.base_url}/oss/getOssInfo"
        try:
            resp = requests.get(url, headers=self.get_headers(), timeout=30)
            data = resp.json()
            if data.get('code') == 200:
                return data.get('data')
            print("OSS info failed:", data)
        except Exception as e:
            print(f"OSS info error: {e}")
        return None

    def get_sts_token(self):
        url = f"{self.base_url}/oss/getsts"
        try:
            resp = requests.get(url, headers=self.get_headers(), timeout=30)
            data = resp.json()
            if data.get('code') == 200:
                return data.get('data')
            print("STS token failed:", data)
        except Exception as e:
            print(f"STS token error: {e}")
        return None

    def init_cloud_file(self, file_name, file_size, file_md5):
        url = f"{self.base_url}/cloudFile/init"
        payload = {
            "fileOriginName": file_name,
            "operType": 2,
            "userId": self.user_id,
            "fileLength": file_size,
            "fileMd5": file_md5,
        }
        try:
            resp = requests.post(url, json=payload, headers=self.get_headers(), timeout=30)
            data = resp.json()
            if data.get('code') == 200:
                return data.get('data')
            print("Init cloud file failed:", data)
        except Exception as e:
            print(f"Init cloud file error: {e}")
        return None

    def finish_cloud_file(self, file_id, object_name, file_name, retries=3):
        finish_url = f"{self.base_url}/padTask/updateCloudFileFinish"
        finish_payload = {
            "fileId": file_id,
            "filePath": object_name,
            "fileOriginName": file_name,
        }

        for attempt in range(1, retries + 1):
            try:
                finish_resp = requests.post(finish_url, json=finish_payload, headers=self.get_headers(), timeout=30)
                finish_data = finish_resp.json()
                if finish_data.get('code') == 200 and finish_data.get('data'):
                    return finish_data['data']['downloadUrl']

                is_busy = finish_data.get('code') == 500 and 'busy' in str(finish_data.get('msg', '')).lower()
                if is_busy and attempt < retries:
                    wait_seconds = attempt * 2
                    print(f"Finish upload busy (attempt {attempt}/{retries}), retrying in {wait_seconds}s...")
                    time.sleep(wait_seconds)
                    continue

                print("Finish upload failed:", finish_data)
                return None
            except Exception as e:
                if attempt < retries:
                    wait_seconds = attempt * 2
                    print(f"Finish upload error on attempt {attempt}/{retries}: {e}. Retrying in {wait_seconds}s...")
                    time.sleep(wait_seconds)
                    continue
                print("Finish upload error:", e)
        return None

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

            if check_data.get('code') != 200:
                print("Upload check failed:", check_data.get('msg'))
                return None

            if check_data.get('data'):
                print("File already exists on server.")
                return check_data['data'][0]['downloadUrl']

            file_id = self.init_cloud_file(file_name, file_size, file_md5)
            if not file_id:
                return None

            oss_info = self.get_oss_info()
            sts_token = self.get_sts_token()
            if not oss_info or not sts_token:
                return None

            auth = oss2.StsAuth(
                sts_token['AccessKeyId'],
                sts_token['AccessKeySecret'],
                sts_token['SecurityToken'],
            )

            bucket = oss2.Bucket(auth, oss_info['endpoint'], oss_info['bucket'])
            ext = file_name.split('.')[-1]
            object_name = f"userFile/{file_md5}.{ext}"

            print(f"Uploading to OSS: {object_name}...")
            bucket.put_object_from_file(object_name, file_path)
            print("OSS upload complete.")

            return self.finish_cloud_file(file_id, object_name, file_name)
        except Exception as e:
            print("Upload error:", e)
        return None


def resolve_apkmirror_download_url(mirror, apk_download_page_url):
    try:
        page_resp = mirror.session.get(apk_download_page_url, headers=mirror.headers, timeout=30)
        page_resp.raise_for_status()
        page_soup = BeautifulSoup(page_resp.text, 'html.parser')

        continue_link = None
        for link in page_soup.find_all('a', href=True):
            if '/download/?key=' in link['href']:
                continue_link = urljoin(mirror.base_url, link['href'])
                break
        if not continue_link:
            return None

        continue_resp = mirror.session.get(continue_link, headers=mirror.headers, timeout=30)
        continue_resp.raise_for_status()
        continue_soup = BeautifulSoup(continue_resp.text, 'html.parser')

        direct_link = None
        for link in continue_soup.find_all('a', href=True):
            if '/wp-content/themes/APKMirror/download.php?' in link['href']:
                direct_link = urljoin(mirror.base_url, link['href'])
                break
        if not direct_link:
            return None

        final_resp = mirror.session.get(direct_link, headers=mirror.headers, allow_redirects=False, timeout=30)
        return final_resp.headers.get('Location')
    except Exception as e:
        print(f"Error resolving APKMirror URL: {e}")
        return None


def download_file(url, output_path):
    print(f"Downloading {url} -> {output_path}")
    try:
        with requests.get(url, stream=True, timeout=60) as r:
            r.raise_for_status()
            with open(output_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
        return True
    except Exception as e:
        print(f"Download failed: {e}")
        return False


def get_release_data(package_name):
    mirror = APKMirror(package_name)
    api_url = mirror.api_url + '/app_exists'
    resp = mirror.session.post(api_url, json={'pnames': package_name}, headers=mirror.headers, timeout=30)
    result = resp.json()['data'][0]
    if not result or not result.get('exists'):
        return None, None
    return mirror, result


def get_latest_stable_release(mirror, app_link):
    app_url = urljoin(mirror.base_url, app_link)
    try:
        resp = mirror.session.get(app_url, headers=mirror.headers, timeout=30)
        resp.raise_for_status()
    except Exception as e:
        print(f"Failed to load app page for stable release lookup: {e}")
        return None, None

    soup = BeautifulSoup(resp.text, 'html.parser')
    for row in soup.select('div.appRow'):
        release_link_el = row.select_one('h5 a[href*="-release/"]')
        if not release_link_el:
            continue

        release_href = release_link_el.get('href', '')
        if not release_href.startswith(app_link):
            continue

        release_title = ' '.join(release_link_el.get_text(' ', strip=True).split())
        if 'stable' not in release_title.lower():
            continue

        return release_title, release_href

    print("No stable release entry found on APKMirror app page")
    return None, None


def get_first_download_page_from_release(mirror, release_link):
    release_url = urljoin(mirror.base_url, release_link)
    try:
        resp = mirror.session.get(release_url, headers=mirror.headers, timeout=30)
        resp.raise_for_status()
    except Exception as e:
        print(f"Failed to load release page: {e}")
        return None

    soup = BeautifulSoup(resp.text, 'html.parser')
    for apk_link_el in soup.select('a[href*="android-apk-download"], a[href*="android-apkm-download"]'):
        apk_href = apk_link_el.get('href', '')
        if '/apk/' in apk_href and '-download/' in apk_href:
            return apk_href
    return None


def process_app(client, apps_data, app_name, package_name, output_prefix, stable_only=False):
    target = next((a for a in apps_data if a.get('name') == app_name), None)
    mirror, result = get_release_data(package_name)
    if not result:
        print(f"No APKMirror result for {package_name}")
        return False

    app_info = result.get('app') or {}
    release = result.get('release') or {}
    apks = result.get('apks') or []

    if target is None:
        target = {
            'name': app_name,
            'url': '',
            'versionName': '',
            'iconUrl': app_info.get('icon_url', '')
        }
        apps_data.append(target)
        print(f"Added missing app entry: {app_name}")

    latest_version = release.get('version')
    selected_apk_link = apks[0].get('link') if apks else None

    if stable_only:
        release_title, stable_release_link = get_latest_stable_release(mirror, app_info.get('link', ''))
        stable_apk_link = get_first_download_page_from_release(mirror, stable_release_link) if stable_release_link else None
        if release_title and stable_apk_link:
            version_channel = release_title.rsplit(' ', 1)[-1]
            version_value = release_title.split()[-3] if ' - ' in release_title else release.get('version')
            latest_version = f"{version_value} - {version_channel}" if version_value else release.get('version')
            selected_apk_link = stable_apk_link

    if not latest_version or not selected_apk_link:
        print(f"Missing release/apk data for {app_name}")
        return False

    print(f"{app_name}: current={target.get('versionName')} latest={latest_version}")
    if latest_version == target.get('versionName') and target.get('url'):
        print(f"{app_name}: already up to date")
        return False

    apk_page_url = urljoin(mirror.base_url, selected_apk_link)
    final_url = resolve_apkmirror_download_url(mirror, apk_page_url)
    if not final_url:
        print(f"{app_name}: could not resolve final APKMirror URL")
        return False

    parsed_path = urlparse(final_url).path.lower()
    ext = '.apkm' if parsed_path.endswith('.apkm') else '.apk'
    temp_path = os.path.join(BASE_DIR, f"{output_prefix}_new{ext}")

    if not download_file(final_url, temp_path):
        return False

    clean_version = ''.join(c for c in str(latest_version) if c.isalnum() or c in '.-_')
    upload_name = f"{output_prefix}_{clean_version}{ext}"
    new_url = client.upload_file(temp_path, upload_name)

    try:
        os.remove(temp_path)
    except OSError:
        pass

    if not new_url:
        print(f"{app_name}: upload failed")
        return False

    target['url'] = new_url
    target['versionName'] = latest_version
    if app_info.get('icon_url'):
        target['iconUrl'] = app_info['icon_url']
    print(f"{app_name}: updated to {latest_version}")
    return True


def main():
    if not os.path.exists(APPS_JSON_PATH):
        print(f"Missing file: {APPS_JSON_PATH}")
        return

    with open(APPS_JSON_PATH, 'r', encoding='utf-8') as f:
        apps_data = json.load(f)

    client = VSPhoneClient()
    if not client.login(VSPHONE_USER, VSPHONE_PASS):
        print("Aborting due to login failure")
        return

    any_update = False
    if process_app(client, apps_data, 'Roblox VN', 'com.roblox.client.vnggames', 'roblox_vng'):
        any_update = True
    if process_app(client, apps_data, 'Roblox Quốc Tế', 'com.roblox.client', 'roblox_intl'):
        any_update = True
    if process_app(client, apps_data, 'Discord', 'com.discord', 'discord', stable_only=True):
        any_update = True

    if any_update:
        with open(APPS_JSON_PATH, 'w', encoding='utf-8') as f:
            json.dump(apps_data, f, indent=2, ensure_ascii=False)
        print('Saved source/apps.json')
    else:
        print('No updates made')


if __name__ == '__main__':
    main()
