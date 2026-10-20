"""Turns a GitHub link into a .fap file (same link types as the phone app)."""
import json
import re
import urllib.request

LIMIT = 8_000_000


def _http(url, accept=None):
    headers = {"User-Agent": "FlipperDesk"}
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise Exception("File is larger than 8 MB")
    return data


def direct_url(link):
    """Returns a direct download URL for file links, or (owner, repo, rest) for repo / release pages."""
    u = link.strip().split("#")[0].split("?")[0]
    if u.startswith("https://raw.githubusercontent.com/"):
        return u
    m = re.match(r"^https://github\.com/([^/]+)/([^/]+)(/.*)?$", u)
    if not m:
        raise Exception("Only github.com links are supported")
    owner, repo, rest = m.group(1), m.group(2), m.group(3) or ""
    if repo.endswith(".git"):
        repo = repo[:-4]
    if "/releases/download/" in rest or rest.startswith("/raw/"):
        return u
    if rest.startswith("/blob/"):
        return "https://github.com/%s/%s/raw/%s" % (owner, repo, rest[len("/blob/"):])
    return (owner, repo, rest)


def fetch(link, ext=".fap"):
    """Returns (file name, bytes)."""
    d = direct_url(link)
    if isinstance(d, str):
        name = d.rsplit("/", 1)[-1]
        if not name.endswith(ext):
            raise Exception("The link must point to a %s file" % ext)
        return name, _http(d)
    owner, repo, rest = d
    if rest.startswith("/releases/tag/"):
        api = "https://api.github.com/repos/%s/%s/releases/tags/%s" % (owner, repo, rest[len("/releases/tag/"):])
    else:
        api = "https://api.github.com/repos/%s/%s/releases/latest" % (owner, repo)
    release = json.loads(_http(api, "application/vnd.github+json"))
    for a in release.get("assets", []):
        if a["name"].endswith(ext):
            return a["name"], _http(a["browser_download_url"])
    raise Exception("No %s file found in that release" % ext)
