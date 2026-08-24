import sys

from apps_json import APPS_JSON_PATH, load_apps, make_session, save_apps_if_changed, snapshot

# Only clear dead links for apps that have auto-update scripts
AUTO_UPDATED_APPS = [
    "Roblox VN",
    "Roblox Quốc Tế",
    "Discord",
    "ZArchiver",
    "Roblox Quốc Tế (Delta)",
    "Roblox VN (Delta)"
]

TIMEOUT = 15


def check_link(session, url):
    if not url:
        return False
    try:
        r = session.head(url, allow_redirects=True, timeout=TIMEOUT)
        if r.status_code == 404:
            return False
        if r.status_code == 200:
            return True
        # Some mirrors reject HEAD; confirm with a GET but never download the
        # body — and close the response so the connection returns to the pool.
        with session.get(url, stream=True, timeout=TIMEOUT) as r2:
            return r2.status_code == 200
    except Exception as e:
        print(f"Error checking {url}: {e}")
        return False


def main():
    apps_data = load_apps()
    if apps_data is None:
        print(f"Missing file: {APPS_JSON_PATH}")
        return 1

    before = snapshot(apps_data)
    session = make_session()

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
        if check_link(session, url):
            print(f"Link OK for {app_name}")
        else:
            print(f"Link dead for {app_name}: {url}")
            app['url'] = ""  # Clear the URL so update scripts will re-upload

    if save_apps_if_changed(apps_data, before):
        print('Saved source/apps.json with cleared dead links')
    else:
        print('All auto-updated app links are active. No changes made.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
