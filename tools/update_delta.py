import os
import re
import json
import time
import requests
import hashlib
from urllib.parse import urlparse, parse_qs, urljoin
from bs4 import BeautifulSoup
import cloudscraper
from androguard.core.apk import APK

# Configuration
# Resolve paths relative to this script file
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
APPS_JSON_PATH = os.path.join(BASE_DIR, "../source/apps.json")
VSPHONE_USER = "contradict6016@lordofmysteries.org"
VSPHONE_PASS = "155260"

DEFAULT_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
}

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
                    print("Registering file upload...")
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
                        return finish_data['data']['downloadUrl']
            print("Upload check failed:", check_data.get('msg'))
        except Exception as e:
            print("Upload error:", e)
        return None

def fetch_international_link():
    url = "https://delta.filenetwork.vip/android.html"
    print(f"Fetching Delta International from official site...")
    try:
        scraper = cloudscraper.create_scraper()
        resp = scraper.get(url)
        if resp.status_code == 403:
            print("International: Cloudflare blocked the request (403).")
            return None

        soup = BeautifulSoup(resp.text, 'html.parser')

        # Heuristic: Find first direct .apk link
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

        # Check for MediaFire direct link
        if "mediafire.com" in final_url:
            return final_url

        # Generic scan for MediaFire links on the final page (handles bloggingdaze, dusarisalary, etc.)
        print(f"Scanning {final_url} for MediaFire links...")
        soup = BeautifulSoup(resp.text, 'html.parser')

        mf_links = soup.find_all('a', href=re.compile(r'mediafire\.com'))
        if mf_links:
            return mf_links[0]['href']

        for a in soup.find_all('a', href=True):
            if 'mediafire.com' in a['href']:
                return a['href']

        print("Could not find MediaFire link on page.")
        return None

    except Exception as e:
        print(f"Error bypassing sub2unlock: {e}")
        return None

def fetch_anotepad_content(url):
    try:
        resp = requests.get(url, headers=DEFAULT_HEADERS)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, 'html.parser')

        content_div = soup.find('div', class_='plaintext') or                       soup.find('div', class_='richtext') or                       soup.find('div', id='note_content') or                       soup.find('div', class_='note-content')

        if content_div:
            return soup, str(content_div)

        return soup, ""
    except Exception as e:
        print(f"Error fetching Anotepad: {e}")
        return None, ""

def find_link_in_siblings(element):
    # Check current element first
    link = element.find('a', href=re.compile(r'/notes/[\w]+'))
    if link and 'pntxb676' not in link['href']:
        return link['href']

    # Check next siblings
    curr = element
    for _ in range(5): # Look ahead 5 siblings
        curr = curr.find_next_sibling()
        if not curr: break

        link = curr.find('a', href=re.compile(r'/notes/[\w]+'))
        if link and 'pntxb676' not in link['href']:
            return link['href']

    return None

def parse_anotepad_links(root_url):
    print(f"Parsing Anotepad root: {root_url}")
    soup, content_html = fetch_anotepad_content(root_url)
    if not content_html:
        return None, None

    intl_sub_url = None
    vng_sub_url = None

    content_soup = BeautifulSoup(content_html, 'html.parser')

    # Iterate all DIVs to find title blocks
    for div in content_soup.find_all('div'):
        text = div.get_text().strip().lower()
        if not text: continue

        if "delta" in text or "deltax" in text:
            # Check for International
            if "quốc tế" in text or "quoc te" in text or "international" in text:
                if not intl_sub_url:
                    link = find_link_in_siblings(div)
                    if link:
                        print(f"Found candidate for International: {link}")
                        intl_sub_url = urljoin("https://vi.anotepad.com", link)

            # Check for VNG
            elif "vng" in text:
                if "fix lag" in text:
                    print(f"Skipping VNG Fix Lag block: {text[:30]}...")
                    continue

                if not vng_sub_url:
                    link = find_link_in_siblings(div)
                    if link:
                        print(f"Found candidate for VNG (Official): {link}")
                        vng_sub_url = urljoin("https://vi.anotepad.com", link)

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
    new_file_path = os.path.join(BASE_DIR, f"{output_name_prefix}_new.apk")
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
    intl_link_official = fetch_international_link()

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
