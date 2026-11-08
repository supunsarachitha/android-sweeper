"""Check dependencies for known vulnerabilities.

Fails (exit 1) on any advisory affecting:
  - a library in the release runtime classpath (shipped in the APK),
  - the Android or Kotlin Gradle plugin,
  - the Gradle version in the wrapper (Gradle's own advisory feed on GitHub,
    which OSV does not include),
  - a pinned GitHub Action (they run with access to the signing key).
Build-time-only artifacts from gradle/verification-metadata.xml are reported as
warnings: they never ship and many are only reachable inside the build.
"""
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

OSV = "https://api.osv.dev/v1/querybatch"


def query(pkgs):
    """pkgs: list of (ecosystem, name, version) -> {pkg: [ids]}"""
    found = {}
    for i in range(0, len(pkgs), 500):
        chunk = pkgs[i:i + 500]
        body = {"queries": [{"package": {"ecosystem": e, "name": n}, "version": v} for e, n, v in chunk]}
        req = urllib.request.Request(OSV, json.dumps(body).encode(), {"Content-Type": "application/json"})
        results = json.load(urllib.request.urlopen(req, timeout=60))["results"]
        for pkg, res in zip(chunk, results):
            ids = [v["id"] for v in res.get("vulns", [])]
            if ids:
                found[pkg] = ids
    return found


def in_range(version, events):
    """OSV ECOSYSTEM range events -> is version affected (numeric dotted versions)."""
    v = vtuple(version)
    affected = False
    for ev in events:
        if "introduced" in ev:
            affected = ev["introduced"] == "0" or v >= vtuple(ev["introduced"].lstrip("v"))
        elif "fixed" in ev and affected and v >= vtuple(ev["fixed"].lstrip("v")):
            affected = False
        elif "last_affected" in ev and affected and v > vtuple(ev["last_affected"].lstrip("v")):
            affected = False
    return affected


def action_advisories(name, version):
    """OSV cannot match versions for the GitHub Actions ecosystem, so evaluate ranges here."""
    body = {"package": {"ecosystem": "GitHub Actions", "name": name}}
    req = urllib.request.Request("https://api.osv.dev/v1/query", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    ids = []
    for vuln in json.load(urllib.request.urlopen(req, timeout=60)).get("vulns", []):
        for aff in vuln.get("affected", []):
            if aff["package"]["name"] != name:
                continue
            if version in (aff.get("versions") or []) or any(
                    in_range(version, r["events"]) for r in aff.get("ranges", []) if r["type"] in ("ECOSYSTEM", "SEMVER")):
                ids.append(vuln["id"])
    return ids


def runtime_deps(path):
    deps = set()
    for line in Path(path).read_text().splitlines():
        m = re.search(r"--- ([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?", line)
        if m:
            g, a, v, resolved = m.groups()
            version = resolved or v
            if version:
                deps.add(("Maven", f"{g}:{a}", version))
    return deps


def metadata_deps(path):
    p = Path(path)
    if not p.exists():
        return set()
    ns = {"m": "https://schema.gradle.org/dependency-verification"}
    root = ET.parse(p).getroot()
    return {("Maven", f"{c.get('group')}:{c.get('name')}", c.get("version"))
            for c in root.iterfind(".//m:component", ns)}


BUILD_PLUGINS = {"com.android.tools.build:gradle", "org.jetbrains.kotlin:kotlin-gradle-plugin"}


def vtuple(s):
    return tuple(int(x) for x in s.split("."))


def gradle_version():
    props = Path("gradle/wrapper/gradle-wrapper.properties").read_text()
    return re.search(r"gradle-([\d.]+)-(bin|all)\.zip", props).group(1)


def gradle_advisories(version):
    """Advisories from github.com/gradle/gradle that affect this Gradle version.

    The feed's version ranges are free text, so this uses the patched versions:
    affected when a fix exists in our major line and we are below it, or when
    every fix is in a newer major. (It can over-report for a fix that applied
    to one minor only; it never under-reports a fixed-later issue.)
    """
    headers = {"Accept": "application/vnd.github+json"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    url = "https://api.github.com/repos/gradle/gradle/security-advisories?per_page=100"
    advisories = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60))
    v = vtuple(version)
    hits = []
    for adv in advisories:
        if adv.get("withdrawn_at"):
            continue
        patched = [vtuple(x) for vuln in adv.get("vulnerabilities", [])
                   for x in re.findall(r"\d+(?:\.\d+)+|\b\d+\b", vuln.get("patched_versions") or "")]
        if not patched:
            continue
        same_major = [p for p in patched if p[0] == v[0]]
        if same_major:
            affected = v < max(same_major)
        else:
            affected = all(p[0] > v[0] for p in patched)
        if affected:
            hits.append(adv["ghsa_id"])
    return hits


def actions():
    pkgs = set()
    for wf in Path(".github/workflows").glob("*.yml"):
        for m in re.finditer(r"uses:\s*([\w.\-]+/[\w.\-]+)(?:/[\w.\-/]+)?@[0-9a-f]{40}\s*#\s*v?([\d.]+)", wf.read_text()):
            pkgs.add(("GitHub Actions", m.group(1), m.group(2)))
    return pkgs


def main():
    runtime = runtime_deps(sys.argv[1])
    if not runtime:
        print("::error::No runtime dependencies parsed; refusing to report a clean result")
        return 1
    build = metadata_deps("gradle/verification-metadata.xml")
    plugins = {d for d in build if d[1] in BUILD_PLUGINS}
    blocking = runtime | plugins
    build_only = build - blocking
    hits = query(sorted(blocking | build_only))
    for _, name, ver in sorted(actions()):
        ids = action_advisories(name, ver)
        if ids:
            hits[("GitHub Actions", name, ver)] = ids
            blocking.add(("GitHub Actions", name, ver))
    gradle = gradle_version()
    print(f"Checked {len(runtime)} runtime libraries, {len(plugins)} build plugins, Gradle {gradle}, "
          f"{len(actions())} actions, {len(build_only)} other build-time artifacts.")
    fail = 0
    gradle_hits = gradle_advisories(gradle)
    if gradle_hits:
        print(f"::error::Gradle {gradle}: {', '.join(gradle_hits)}")
        fail = 1
    for pkg, ids in sorted(hits.items()):
        eco, name, ver = pkg
        if pkg in blocking:
            print(f"::error::{eco} {name} {ver}: {', '.join(ids)}")
            fail = 1
        else:
            print(f"::warning::build-time only: {name} {ver}: {', '.join(ids)}")
    if not hits and not gradle_hits:
        print("No known vulnerabilities.")
    return fail


if __name__ == "__main__":
    sys.exit(main())
