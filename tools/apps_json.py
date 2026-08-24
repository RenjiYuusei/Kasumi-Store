"""Shared helpers for the source/apps.json maintenance scripts.

Keeps the three CI scripts (check_app_links, update_apps, update_delta) writing
apps.json the same way and talking HTTP the same way.
"""

import json
import os
import tempfile

import requests
from requests.adapters import HTTPAdapter

try:  # urllib3 v2 and v1 expose Retry from different places
    from urllib3.util.retry import Retry
except ImportError:  # pragma: no cover
    from requests.packages.urllib3.util.retry import Retry  # type: ignore

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPS_JSON_PATH = os.path.join(REPO_ROOT, "source", "apps.json")

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

DEFAULT_TIMEOUT = 20


def load_apps(path=APPS_JSON_PATH):
    """Reads apps.json. Returns None when the file is missing."""
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_apps(apps_data, path=APPS_JSON_PATH):
    """
    Writes apps.json atomically.

    The scripts used to json.dump() straight onto the real path, so a crash or a
    cancelled CI job midway through left a truncated apps.json in the working
    tree — which the very next scheduled run would then fail to parse. Writing a
    temporary file in the same directory and renaming it means readers only ever
    see the old file or the complete new one.
    """
    directory = os.path.dirname(path) or "."
    fd, tmp_path = tempfile.mkstemp(dir=directory, prefix=".apps.json.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            json.dump(apps_data, f, indent=2, ensure_ascii=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, path)
    except BaseException:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise


def save_apps_if_changed(apps_data, original_snapshot, path=APPS_JSON_PATH):
    """
    Writes only when the content really differs, so an unchanged run cannot
    produce an empty commit.

    Returns True when the file was written.
    """
    if json.dumps(apps_data, sort_keys=True, ensure_ascii=False) == original_snapshot:
        return False
    save_apps(apps_data, path)
    return True


def snapshot(apps_data):
    """Stable representation used by save_apps_if_changed."""
    return json.dumps(apps_data, sort_keys=True, ensure_ascii=False)


def make_session(retries=3, backoff=0.5):
    """
    A requests Session with connection reuse, a browser User-Agent and retries
    on the transient statuses these mirrors return under load.
    """
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})
    retry = Retry(
        total=retries,
        connect=retries,
        read=retries,
        backoff_factor=backoff,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["HEAD", "GET", "OPTIONS"]),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=8, pool_maxsize=8)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session
