# SPDX-License-Identifier: Apache-2.0
"""Small helpers around the GitHub CLI (`gh`), shared by the scripts in this folder."""
import json
import subprocess


class GhError(RuntimeError):
    pass


def gh(*args, input_text=None, check=True):
    try:
        p = subprocess.run(
            ["gh", *args], input=input_text, capture_output=True, text=True, encoding="utf-8"
        )
    except FileNotFoundError:
        raise GhError("GitHub CLI 'gh' not found. Install it and run 'gh auth login'.") from None
    if check and p.returncode != 0:
        raise GhError(f"gh {' '.join(args)}\n{p.stderr.strip()}")
    return p.stdout


def api(path, method="GET", body=None):
    """Call the GitHub REST API. `body` is sent as JSON."""
    args = ["api", "-X", method, path]
    if body is not None:
        args += ["--input", "-"]
    out = gh(*args, input_text=json.dumps(body) if body is not None else None)
    return json.loads(out) if out.strip() else None


def api_list(path):
    """GET a paginated list endpoint and return all items."""
    out = gh("api", "--paginate", path).strip()
    decoder = json.JSONDecoder()
    items, i = [], 0
    while i < len(out):
        obj, i = decoder.raw_decode(out, i)
        items.extend(obj if isinstance(obj, list) else [obj])
        while i < len(out) and out[i].isspace():
            i += 1
    return items


def repo_name():
    return json.loads(gh("repo", "view", "--json", "nameWithOwner"))["nameWithOwner"]


def default_branch():
    data = json.loads(gh("repo", "view", "--json", "defaultBranchRef"))
    return data["defaultBranchRef"]["name"]


MILESTONES = {
    "M0": ("M0 – Fundament (0.1.0)", "Repository steht, Contract ist definiert, ein Build läuft."),
    "M1": ("M1 – Engine-Kern (0.2.0)", "Eine Engine führt eine Fabric mit mehreren Blocks in einem Prozess aus."),
    "M2": ("M2 – Schema und Tether-Typen (0.3.0)", "Typisierte Kommunikation über verschiedene Transporte."),
    "M3": ("M3 – Artefakte und Repository (0.4.0)", "Projects und Plugins kommen aus einer verwalteten Quelle."),
    "M4": ("M4 – Maschinenebene und erstes Deployment (0.5.0)", "Erstes vollständiges Deployment über ManagementServer, Daemon und Registry."),
    "M5": ("M5 – Trust und Verschlüsselung (0.6.0)", "Alle Komponenten kommunizieren abgesichert."),
    "M6": ("M6 – Verteilung und Shared Services (0.7.0)", "Anwendungen über mehrere Engines und Projects hinweg."),
    "M7": ("M7 – Observability (0.8.0)", "Der Betrieb ist beobachtbar und analysierbar."),
    "M8": ("M8 – WebUI und Blueprint-Editor (0.9.0)", "Blueprints werden visuell gebaut, die Plattform wird grafisch bedient."),
    "M9": ("M9 – Betrieb, Sicherheit und Härtung (1.0.0)", "Produktionsreife."),
}

LABELS = {
    "agent-task": ("1d76db", "Implementation task that an agent or human can pick up"),
    "ready": ("0e8a16", "Defined by Claude, ready for implementation by opencode"),
    "in-progress": ("fbca04", "Claimed: a branch issue/<n>-... exists"),
    "blocked": ("d93f0b", "Cannot proceed; reason is in the comments"),
    "deferred": ("cfd3d7", "Postponed on purpose, do not pick up"),
    "follow-up": ("5319e7", "Found while working on another issue"),
    "needs-owner-decision": ("b60205", "Waiting for a decision by the project owner"),
}
