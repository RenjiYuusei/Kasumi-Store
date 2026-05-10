import os
import json
import requests

APPS_JSON_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'source', 'apps.json')

# Only clear dead links for apps that have auto-update scripts
AUTO_UPDATED_APPS = [
    "Roblox VN",
    "Roblox Quốc Tế",
    "Discord",
    "ZArchiver",
    "Roblox Quốc Tế (Delta)",
    "Roblox VN (Delta)"
]

def check_link(url):
    if not url:
        return False
    try:
        r = requests.head(url, allow_redirects=True, timeout=10)
        if r.status_code == 404:
            return False
        if r.status_code == 200:
            return True
        r2 = requests.get(url, stream=True, timeout=10)
        return r2.status_code == 200
    except Exception as e:
        print(f"Error checking {url}: {e}")
        return False

def main():
    if not os.path.exists(APPS_JSON_PATH):
        print(f"Missing file: {APPS_JSON_PATH}")
        return

    with open(APPS_JSON_PATH, 'r', encoding='utf-8') as f:
        apps_data = json.load(f)

    modified = False
    for app in apps_data:
        app_name = app.get('name')
        url = app.get('url')

        if not url:
            print(f"No URL for {app_name}")
            continue

        if app_name not in AUTO_UPDATED_APPS:
            # We don't auto-update these, so don't clear their URLs
            continue

        print(f"Checking {app_name}...")
        is_valid = check_link(url)
        if not is_valid:
            print(f"Link dead for {app_name}: {url}")
            app['url'] = ""  # Clear the URL so update scripts will re-upload
            modified = True
        else:
            print(f"Link OK for {app_name}")

    if modified:
        with open(APPS_JSON_PATH, 'w', encoding='utf-8') as f:
            json.dump(apps_data, f, indent=2, ensure_ascii=False)
        print('Saved source/apps.json with cleared dead links')
    else:
        print('All auto-updated app links are active. No changes made.')

if __name__ == '__main__':
    main()
