import os
import json
import hashlib
import time
import re
import requests
import cloudscraper
import shutil
from bs4 import BeautifulSoup
from urllib.parse import urlparse, parse_qs, unquote, urljoin
from androguard.core.apk import APK

# Configuration
APPS_JSON_PATH = "source/apps.json"
VSPHONE_USER = "contradict6016@lordofmysteries.org"
VSPHONE_PASS = "155260"
VSPHONE_APPVERSION = "2001701"

DEFAULT_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
}

class VSPhoneClient:
    BASE_URL = "https://api.vsphone.com/vsphone/api"
    ORIGIN = "https://cloud.vsphone.com"

    def __init__(self):
        self.session = requests.Session()
        self.user_id = None
        self.token = None

    def login(self, username, password):
        url = f"{self.BASE_URL}/user/login"
        pass_hash = hashlib.md5(password.encode()).hexdigest()

        headers = {
            'content-type': 'application/json',
            'appversion': VSPHONE_APPVERSION,
            'requestsource': 'wechat-miniapp'
        }
        data = {
            "mobilePhone": username,
            "loginType": 1,
            "channel": "web",
            "password": pass_hash
        }

        try:
            resp = self.session.post(url, json=data, headers=headers)
            resp.raise_for_status()
            res_json = resp.json()
            if res_json.get('code') == 200 and res_json.get('data'):
                self.user_id = str(res_json['data']['userId'])
                self.token = res_json['data']['token']
                print(f"Login successful. UserID: {self.user_id}")
                return True
            else:
                print(f"Login failed: {res_json}")
                return False
        except Exception as e:
            print(f"Login exception: {e}")
            return False

    def get_headers(self):
        return {
            'accept': 'application/json, text/plain, */*',
            'appversion': VSPHONE_APPVERSION,
            'clienttype': 'web',
            'content-type': 'application/json',
            'origin': self.ORIGIN,
            'referer': self.ORIGIN + '/',
            'requestsource': 'wechat-miniapp',
            'suppliertype': '0',
            'token': self.token,
            'userid': self.user_id
        }

    def upload_file(self, file_path, file_name=None):
        if not self.token:
            print("Not logged in.")
            return None

        if not file_name:
            file_name = os.path.basename(file_path)

        file_size = os.path.getsize(file_path)

        md5_hash = hashlib.md5()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                md5_hash.update(byte_block)
        file_md5 = md5_hash.hexdigest()

        print(f"Processing upload for {file_name} (Size: {file_size}, MD5: {file_md5})")

        check_url = f"{self.BASE_URL}/cloudFile/uploadCheck"
        check_data = {
            "fileItems": [{
                "fileLength": file_size,
                "fileMd5": file_md5,
                "fileType": 2,
                "fileName": file_name
            }],
            "operType": 2
        }

        try:
            headers = self.get_headers()
            resp = self.session.post(check_url, json=check_data, headers=headers)
            resp.raise_for_status()
            check_res = resp.json()

            final_file = None

            if check_res.get('code') == 200:
                if check_res.get('data') and len(check_res['data']) > 0:
                    print("File already exists on server (Instant Upload).")
                    final_file = check_res['data'][0]
                else:
                    print("File not found on server. Attempting registration...")
                    ext = file_name.split('.')[-1] if '.' in file_name else ""
                    fake_path = f"userFile/{file_md5}.{ext}"

                    finish_url = f"{self.BASE_URL}/padTask/updateCloudFileFinish"
                    finish_data = {
                        "fileId": 0,
                        "filePath": fake_path,
                        "fileOriginName": file_name
                    }

                    resp_fin = self.session.post(finish_url, json=finish_data, headers=headers)
                    resp_fin.raise_for_status()
                    fin_res = resp_fin.json()

                    if fin_res.get('code') == 200 and fin_res.get('data'):
                        final_file = fin_res['data']
                        print("File registered successfully.")
                    else:
                        print(f"Registration failed: {fin_res}")
                        return None

            else:
                print(f"Upload check failed: {check_res}")
                return None

            if final_file and 'downloadUrl' in final_file:
                return final_file['downloadUrl']
            return None

        except Exception as e:
            print(f"Upload exception: {e}")
            return None

def fetch_international_link():
    print("Fetching Delta International from official site...")
    url = "https://delta.filenetwork.vip/android.html"
    try:
        scraper = cloudscraper.create_scraper(browser={'browser': 'chrome', 'platform': 'android', 'desktop': False})
        resp = scraper.get(url)

        if resp.status_code == 403:
            print("International: Cloudflare blocked the request (403).")
            return None

        soup = BeautifulSoup(resp.text, 'html.parser')

        download_btn = soup.find('a', string=re.compile(r'Download', re.I))
        if not download_btn:
             download_btn = soup.find('a', class_=re.compile(r'btn|download', re.I))

        if download_btn:
            link = download_btn.get('href')
            if link:
                if not link.startswith('http'):
                    link = "https://delta.filenetwork.vip/" + link.lstrip('/')
                print(f"Found International link candidate: {link}")
                return link

        for a in soup.find_all('a', href=True):
            if '.apk' in a['href']:
                print(f"Found .apk link: {a['href']}")
                return a['href']

        print("Could not find International link on official site.")
        return None
    except Exception as e:
        print(f"Error fetching International: {e}")
        return None

def extract_google_url(url):
    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    if 'url' in qs:
        return qs['url'][0]
    return None

def bypass_sub2unlock(url):
    print(f"Bypassing Sub2Unlock: {url}")
    session = requests.Session()
    session.headers.update(DEFAULT_HEADERS)

    try:
        resp = session.get(url, allow_redirects=True)
        final_url = resp.url
        print(f"Landed on: {final_url}")

        target_page_url = final_url

        if "google.com" in final_url:
            print("Detected Google redirect wrapper...")
            target_url = extract_google_url(final_url)
            if target_url:
                print(f"Extracted target: {target_url}")
                target_page_url = target_url
                resp = session.get(target_page_url, allow_redirects=True)
                final_url = resp.url
                print(f"Landed on (after Google): {final_url}")

        if "bloggingdaze.com" in final_url or "bloggingdaze.com" in target_page_url:
            soup = BeautifulSoup(resp.text, 'html.parser')

            mf_links = soup.find_all('a', href=re.compile(r'mediafire\.com'))
            if mf_links:
                return mf_links[0]['href']

            for a in soup.find_all('a', href=True):
                if 'mediafire.com' in a['href']:
                    return a['href']

            print("Could not find MediaFire link on BloggingDaze page.")

        elif "mediafire.com" in final_url:
            return final_url

        return None

    except Exception as e:
        print(f"Error bypassing sub2unlock: {e}")
        return None

def fetch_anotepad_content(url):
    try:
        resp = requests.get(url, headers=DEFAULT_HEADERS)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, 'html.parser')

        content_div = soup.find('div', class_='plaintext') or \
                      soup.find('div', class_='richtext') or \
                      soup.find('div', id='note_content') or \
                      soup.find('div', class_='note-content')

        if content_div:
            return soup, str(content_div)

        return soup, ""
    except Exception as e:
        print(f"Error fetching Anotepad: {e}")
        return None, ""

def parse_anotepad_links(root_url):
    print(f"Parsing Anotepad root: {root_url}")
    soup, content_html = fetch_anotepad_content(root_url)
    if not content_html:
        return None, None

    intl_sub_url = None
    vng_sub_url = None

    content_soup = BeautifulSoup(content_html, 'html.parser')
    links = content_soup.find_all('a', href=re.compile(r'/notes/[\w]+'))

    for link in links:
        href = link['href']
        if 'pntxb676' in href: continue

        context_text = ""
        curr = link
        for _ in range(5):
            if curr.parent:
                curr = curr.parent
                context_text += curr.get_text() + " "
            else:
                break

        context_text = context_text.lower()

        if "delta" in context_text or "deltax" in context_text:
            if "quốc tế" in context_text or "quoc te" in context_text or "international" in context_text:
                if not intl_sub_url:
                    print(f"Found candidate for International (fallback): {href}")
                    intl_sub_url = urljoin("https://vi.anotepad.com", href)
            elif "vng" in context_text:
                if not vng_sub_url:
                    print(f"Found candidate for VNG: {href}")
                    vng_sub_url = urljoin("https://vi.anotepad.com", href)

    return intl_sub_url, vng_sub_url

def resolve_sub2unlock_from_note(note_url):
    if not note_url: return None
    print(f"Resolving Sub2Unlock from: {note_url}")
    _, content = fetch_anotepad_content(note_url)
    match = re.search(r'https?://sub2unlock\.io/[\w]+', content)
    if match:
        return match.group(0)
    return None

def get_mediafire_direct_link(url):
    try:
        session = requests.Session()
        session.headers.update(DEFAULT_HEADERS)
        resp = session.get(url)
        soup = BeautifulSoup(resp.text, 'html.parser')
        link = soup.find('a', {'aria-label': 'Download file'})
        if link:
            return link.get('href')
        link = soup.find('a', class_='input popsok')
        if link:
            return link.get('href')
        return url
    except:
        return url

def download_file(url, output_path):
    print(f"Downloading {url} to {output_path}...")

    if "mediafire.com" in url:
        direct_url = get_mediafire_direct_link(url)
        if direct_url != url:
            print(f"Resolved MediaFire direct link: {direct_url}")
            url = direct_url

    try:
        scraper = cloudscraper.create_scraper()
        with scraper.get(url, stream=True) as r:
            r.raise_for_status()
            with open(output_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    f.write(chunk)
        return True
    except Exception as e:
        print(f"Download failed: {e}")
        return False

def get_apk_version(apk_path):
    try:
        apk = APK(apk_path)
        version_name = apk.get_androidversion_name()
        print(f"Extracted version from APK: {version_name}")
        return version_name
    except Exception as e:
        print(f"Error analyzing APK: {e}")
        return None

def process_app_update(client, apps_data, app_name_keyword, source_link, output_name_prefix):
    target_app = next((a for a in apps_data if app_name_keyword in a['name']), None)
    if not target_app:
        print(f"App {app_name_keyword} not found in apps.json")
        return False

    print(f"Processing {target_app['name']}...")

    sub2unlock_url = resolve_sub2unlock_from_note(source_link)
    if not sub2unlock_url:
        print(f"No sub2unlock link found for {app_name_keyword}")
        return False

    mf_link = bypass_sub2unlock(sub2unlock_url)
    if not mf_link:
        print("Bypass failed.")
        return False

    print(f"MediaFire Link: {mf_link}")

    # Download new file
    new_file_path = f"{output_name_prefix}_new.apk"
    if not download_file(mf_link, new_file_path):
        return False

    # Get Version from APK
    new_version = get_apk_version(new_file_path)
    if not new_version:
        print("Failed to get version from new APK. Aborting update.")
        if os.path.exists(new_file_path): os.remove(new_file_path)
        return False

    current_version = target_app.get('versionName')
    print(f"Current Version: {current_version}, New Version: {new_version}")

    updated = False

    if new_version != current_version:
        print("Version mismatch! Update detected.")

        # Calculate MD5 for upload
        with open(new_file_path, "rb") as f: new_md5 = hashlib.md5(f.read()).hexdigest()

        print("Uploading to VSPhone...")
        upload_name = f"{output_name_prefix}_{new_version}.apk"
        new_url = client.upload_file(new_file_path, upload_name)

        if new_url:
            target_app['url'] = new_url
            target_app['versionName'] = new_version
            updated = True
            print(f"Updated {target_app['name']} to version {new_version} @ {new_url}")
        else:
            print("Upload failed.")
    else:
        print("Versions match. No update needed.")

    if os.path.exists(new_file_path): os.remove(new_file_path)
    return updated

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
    anotepad_root = "https://vi.anotepad.com/notes/pntxb676"
    intl_note, vng_note = parse_anotepad_links(anotepad_root)

    # 1. Update International
    # Try official first
    intl_link_official = fetch_international_link()

    if intl_link_official:
        # TODO: Direct download for official logic is simpler if no bypass needed
        # But we haven't implemented get_apk_version integration for direct link download path perfectly yet.
        # Since 403 is common, I'll rely on Anotepad fallback logic for now which is robust in `process_app_update`.
        # If I wanted to use official link, I would need to adapt `process_app_update` to take a direct link instead of note_link.
        # For now, let's prioritize Anotepad since we know it works.
        pass

    # Use Anotepad for International if found
    if intl_note:
        print("Using Anotepad source for International.")
        if process_app_update(client, apps_data, "Roblox Quốc Tế (Delta)", intl_note, "delta_intl"):
            any_update = True
    else:
        print("Could not find International source.")

    # 2. Update VNG
    if vng_note:
        if process_app_update(client, apps_data, "Roblox VN (Delta)", vng_note, "delta_vng"):
            any_update = True
    else:
        print("Could not find VNG note link in root.")

    if any_update:
        print("Saving apps.json...")
        with open(APPS_JSON_PATH, 'w', encoding='utf-8') as f:
            json.dump(apps_data, f, indent=2, ensure_ascii=False)
    else:
        print("No updates made.")

if __name__ == "__main__":
    main()
