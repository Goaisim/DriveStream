#!/usr/bin/env python3
"""
DriveStream Soulseek + Google Drive Auto-Uploader Bridge.

Runs an HTTP JSON API on 0.0.0.0:5031 in front of slskd (127.0.0.1:5030),
exposing video-filtered search (with 1-click folder grouping), batch download
queuing, unified transfer progress, and a background resumable Google Drive
v3 uploader that organizes TV shows into Show/Season XX folders and removes
local files once uploaded.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import mimetypes
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BRIDGE_DIR = Path(__file__).resolve().parent
DOWNLOADS_DIR = BRIDGE_DIR / "downloads"
STATE_FILE = BRIDGE_DIR / ".bridge_state.json"

SLSKD_URL = os.environ.get("SLSKD_URL", "http://127.0.0.1:5030").rstrip("/")
SLSKD_API_KEY = os.environ.get("SLSKD_API_KEY", "drivestream-slskd-key-2026")
DEFAULT_TARGET_FOLDER_ID = (
    os.environ.get("DRIVE_TARGET_FOLDER_ID", "89VXpiNB2PxwnR__gNUwAuwU2wnsT1ME").strip()
    or "89VXpiNB2PxwnR__gNUwAuwU2wnsT1ME"
)
BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT", "5031"))
GOOGLE_CLIENT_ID = "134960325804-ohug3donf2m9l58oj794tah7f0il4arb.apps.googleusercontent.com"

VIDEO_EXTENSIONS = (".mkv", ".mp4", ".m4v", ".avi")
CHUNK_SIZE_BYTES = 16 * 1024 * 1024  # 16 MB (multiple of 256 KB for Google Drive resumable upload)
POLL_INTERVAL_SECONDS = 4.0

RELEASE_NOISE_RE = re.compile(
    r"\b(2160p|4k|uhd|1080p|720p|480p|bluray|bdrip|brrip|web[-.\s]?dl|webrip|"
    r"hdrip|dvdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|av1|aac|ac3|dts|ddp?\d|"
    r"10bit|hdr|remux|proper|repack|complete|extended|unrated)\b.*$",
    re.IGNORECASE,
)
SEASON_DIR_RE = re.compile(r"^(?:season|series|s)\s*0*(\d{1,2})\b", re.IGNORECASE)
EPISODE_SXE_RE = re.compile(r"\bS(\d{1,2})[\s._-]*E(\d{1,3})\b", re.IGNORECASE)
EPISODE_X_RE = re.compile(r"\b(\d{1,2})x(\d{1,3})\b", re.IGNORECASE)


def normalize_sep(path: str) -> str:
    return path.replace("\\", "/")


def remote_basename(remote_path: str) -> str:
    parts = [p for p in normalize_sep(remote_path).split("/") if p]
    return parts[-1] if parts else remote_path


def remote_parent(remote_path: str) -> str:
    norm = normalize_sep(remote_path)
    idx = norm.rfind("/")
    return norm[:idx] if idx > 0 else ""


def is_video_filename(name: str) -> bool:
    return name.lower().endswith(VIDEO_EXTENSIONS)


def extract_quality_tag(text: str) -> str:
    lower = text.lower()
    for tag, label in (
        ("2160p", "4K"),
        ("4k", "4K"),
        ("1080p", "1080p"),
        ("720p", "720p"),
        ("480p", "480p"),
        ("dvdrip", "DVDRip"),
        ("bluray", "BluRay"),
        ("web-dl", "WEB-DL"),
        ("webrip", "WEBRip"),
    ):
        if tag in lower:
            return label
    return "Video"


def format_size_gb(size_bytes: int) -> str:
    if size_bytes >= 1024 ** 3:
        return f"{size_bytes / (1024 ** 3):.1f} GB"
    return f"{max(1, size_bytes // (1024 ** 2))} MB"


def clean_title_fragment(raw: str) -> str:
    s = raw.replace(".", " ").replace("_", " ").strip()
    s = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", s)
    s = RELEASE_NOISE_RE.sub("", s)
    s = re.sub(r"\b(19|20)\d{2}\b\s*$", "", s)
    s = re.sub(r"[\s\-._]+$", "", s).strip()
    s = re.sub(r"\s{2,}", " ", s)
    if not s:
        return ""
    if s.islower() or s.isupper():
        return " ".join(w.capitalize() for w in s.split())
    return s


def folder_display_name(parent_folder: str) -> str:
    parts = [p for p in normalize_sep(parent_folder).split("/") if p and not p.startswith("@@")]
    if not parts:
        return "Shared Videos"
    last = parts[-1]
    if len(parts) >= 2 and (SEASON_DIR_RE.match(last) or len(last) <= 6):
        return f"{parts[-2]} / {last}"
    return last


def parse_media_target(remote_filename: str) -> tuple[bool, str | None, int | None]:
    """
    Returns (is_tv, show_name, season_number).
    Matches SxxExx or NxNN in filename or parent path.
    """
    base = remote_basename(remote_filename)
    parent = remote_parent(remote_filename)
    stem = os.path.splitext(base)[0]

    for candidate in (stem, f"{parent} / {stem}"):
        m = EPISODE_SXE_RE.search(candidate) or EPISODE_X_RE.search(candidate)
        if not m:
            continue
        season_num = int(m.group(1))
        # Prefer text before SxxExx inside basename; fall back to parent folder name.
        m_base = EPISODE_SXE_RE.search(stem) or EPISODE_X_RE.search(stem)
        prefix = stem[: m_base.start()] if m_base else ""
        show_name = clean_title_fragment(prefix)
        if not show_name:
            for seg in reversed([p for p in normalize_sep(parent).split("/") if p and not p.startswith("@@")]):
                if SEASON_DIR_RE.match(seg):
                    continue
                candidate_name = clean_title_fragment(seg)
                if candidate_name:
                    show_name = candidate_name
                    break
        if show_name:
            return True, show_name, season_num

    return False, None, None


class SlskdClient:
    def request(self, method: str, path: str, body: object | None = None, timeout: float = 20.0) -> object:
        url = f"{SLSKD_URL}{path}"
        headers = {"X-API-Key": SLSKD_API_KEY}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", errors="replace").strip()
                if not raw:
                    return {}
                return json.loads(raw)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"slskd HTTP {e.code}: {err_body or e.reason}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"slskd unreachable at {SLSKD_URL}: {e.reason}") from e


class BridgeCoordinator:
    def __init__(self, slskd: SlskdClient) -> None:
        self.slskd = slskd
        self.lock = threading.RLock()
        self.auth_lock = threading.Lock()
        self.drive_folder_lock = threading.Lock()
        self.drive_folder_cache: dict[tuple[str, str], str] = {}

        self.oauth: dict[str, str] = {
            "access_token": "",
            "refresh_token": "",
            "target_folder_id": DEFAULT_TARGET_FOLDER_ID,
        }
        # Keyed by transfer_key = f"{username}::{filename}"
        self.jobs: dict[str, dict[str, object]] = {}
        self.completed_uploads_count = 0
        self.executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="drive-upload")
        self._load_state()

    @staticmethod
    def job_key(username: str, filename: str) -> str:
        return f"{username}::{filename}"

    def _load_state(self) -> None:
        if not STATE_FILE.exists():
            return
        try:
            data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
            oauth = data.get("oauth", {})
            if isinstance(oauth, dict):
                self.oauth["access_token"] = str(oauth.get("access_token", ""))
                self.oauth["refresh_token"] = str(oauth.get("refresh_token", ""))
                tf = str(oauth.get("target_folder_id", "")).strip()
                if tf:
                    self.oauth["target_folder_id"] = tf
            self.completed_uploads_count = int(data.get("completed_uploads_count", 0))
            saved_jobs = data.get("jobs", {})
            if isinstance(saved_jobs, dict):
                for k, job in saved_jobs.items():
                    if isinstance(job, dict):
                        # Re-queue interrupted uploads cleanly on restart.
                        if job.get("stage") == "uploading":
                            job["stage"] = "queued_upload"
                            job["percent"] = 0.0
                            job["statusText"] = "Queued for Google Drive Upload"
                        self.jobs[k] = job
        except Exception as exc:
            print(f"[Bridge] Failed loading state: {exc}", file=sys.stderr)

    def _save_state(self) -> None:
        with self.lock:
            payload = {
                "oauth": self.oauth,
                "completed_uploads_count": self.completed_uploads_count,
                "jobs": self.jobs,
            }
        try:
            tmp = STATE_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
            tmp.replace(STATE_FILE)
        except Exception as exc:
            print(f"[Bridge] Failed saving state: {exc}", file=sys.stderr)

    def start_search(self, query: str) -> dict[str, object]:
        payload = {
            "searchText": query,
            "searchTimeout": 15000,
            "filterResponses": True,
            "maximumPeerQueueLength": 50,
        }
        resp = self.slskd.request("POST", "/api/v0/searches", payload)
        if not isinstance(resp, dict) or not resp.get("id"):
            raise RuntimeError("slskd did not return a search ID")
        return {"searchId": str(resp["id"])}

    def get_search_results(self, search_id: str) -> dict[str, object]:
        raw = self.slskd.request(
            "GET",
            f"/api/v0/searches/{urllib.parse.quote(search_id, safe='')}?includeResponses=true",
        )
        if not isinstance(raw, dict):
            return {"searchId": search_id, "isComplete": True, "files": [], "folders": []}

        responses = raw.get("responses") or []
        file_results: list[dict[str, object]] = []
        folder_map: dict[tuple[str, str], dict[str, object]] = {}

        for peer in responses:
            if not isinstance(peer, dict):
                continue
            username = str(peer.get("username", "")).strip()
            if not username:
                continue
            has_free = bool(peer.get("hasFreeUploadSlot", False))
            upload_speed = float(peer.get("uploadSpeed", 0) or 0)
            queue_len = int(peer.get("queueLength", 0) or 0)

            for f in peer.get("files") or []:
                if not isinstance(f, dict):
                    continue
                remote_name = str(f.get("filename", ""))
                if not remote_name or not is_video_filename(remote_name):
                    continue
                size = int(f.get("size", 0) or 0)
                base = remote_basename(remote_name)
                parent = remote_parent(remote_name)
                qtag = extract_quality_tag(remote_name)

                file_entry = {
                    "id": self.job_key(username, remote_name),
                    "username": username,
                    "filename": remote_name,
                    "basename": base,
                    "size": size,
                    "qualityTag": qtag,
                    "uploadSpeed": upload_speed,
                    "hasFreeUploadSlot": has_free,
                    "queueLength": queue_len,
                }
                file_results.append(file_entry)

                folder_key = (username, parent)
                grp = folder_map.get(folder_key)
                if grp is None:
                    grp = {
                        "id": f"{username}::{parent}",
                        "username": username,
                        "parentFolder": parent,
                        "folderDisplayName": folder_display_name(parent),
                        "uploadSpeed": upload_speed,
                        "hasFreeUploadSlot": has_free,
                        "queueLength": queue_len,
                        "totalSize": 0,
                        "qualityCounts": {},
                        "files": [],
                    }
                    folder_map[folder_key] = grp

                grp["totalSize"] = int(grp["totalSize"]) + size
                qcounts: dict[str, int] = grp["qualityCounts"]  # type: ignore[assignment]
                qcounts[qtag] = qcounts.get(qtag, 0) + 1
                grp["files"].append(
                    {
                        "id": file_entry["id"],
                        "username": username,
                        "filename": remote_name,
                        "basename": base,
                        "size": size,
                        "qualityTag": qtag,
                        "uploadSpeed": upload_speed,
                        "hasFreeUploadSlot": has_free,
                        "queueLength": queue_len,
                    }
                )

        file_results.sort(
            key=lambda x: (
                0 if x["hasFreeUploadSlot"] else 1,
                -float(x["uploadSpeed"]),
                -int(x["size"]),
            )
        )

        folders_out: list[dict[str, object]] = []
        for grp in folder_map.values():
            files_list: list[dict[str, object]] = grp["files"]  # type: ignore[assignment]
            files_list.sort(key=lambda item: str(item["basename"]).lower())
            qcounts = grp.pop("qualityCounts")  # type: ignore[misc]
            top_quality = max(qcounts.items(), key=lambda kv: kv[1])[0] if qcounts else "Video"
            count = len(files_list)
            total_size = int(grp["totalSize"])
            disp = str(grp["folderDisplayName"])
            noun = "video file" if count == 1 else "video files"
            grp["fileCount"] = count
            grp["qualityTag"] = top_quality
            grp["summary"] = f"{disp} ({count} {noun} — {format_size_gb(total_size)})"
            folders_out.append(grp)

        folders_out.sort(
            key=lambda g: (
                0 if int(g["fileCount"]) >= 2 else 1,
                0 if g["hasFreeUploadSlot"] else 1,
                -float(g["uploadSpeed"]),
                -int(g["fileCount"]),
                -int(g["totalSize"]),
            )
        )

        return {
            "searchId": search_id,
            "isComplete": bool(raw.get("isComplete", False)),
            "files": file_results[:300],
            "folders": folders_out[:120],
        }

    def enqueue_download(self, payload: dict[str, object]) -> dict[str, object]:
        username = str(payload.get("username", "")).strip()
        raw_files = payload.get("files") or []
        if not username or not isinstance(raw_files, list) or not raw_files:
            raise ValueError("Missing 'username' or non-empty 'files' array.")

        access_token = str(payload.get("accessToken", "")).strip()
        refresh_token = str(payload.get("refreshToken", "")).strip()
        target_folder_id = str(payload.get("targetFolderId", "")).strip() or DEFAULT_TARGET_FOLDER_ID

        batch: list[dict[str, object]] = []
        for entry in raw_files:
            if not isinstance(entry, dict):
                continue
            fname = str(entry.get("filename", ""))
            if not fname:
                continue
            batch.append({"filename": fname, "size": int(entry.get("size", 0) or 0)})

        if not batch:
            raise ValueError("No valid files to enqueue.")

        with self.lock:
            if access_token:
                self.oauth["access_token"] = access_token
            if refresh_token:
                self.oauth["refresh_token"] = refresh_token
            self.oauth["target_folder_id"] = target_folder_id

            for item in batch:
                key = self.job_key(username, str(item["filename"]))
                self.jobs[key] = {
                    "id": key,
                    "username": username,
                    "filename": str(item["filename"]),
                    "title": remote_basename(str(item["filename"])),
                    "size": int(item["size"]),
                    "percent": 0.0,
                    "speedBytesPerSec": 0.0,
                    "stage": "queued",
                    "statusText": "Queued on Peer",
                    "targetFolderId": target_folder_id,
                    "accessToken": access_token or self.oauth["access_token"],
                    "refreshToken": refresh_token or self.oauth["refresh_token"],
                    "driveFileId": "",
                    "driveFolderPath": "",
                    "error": "",
                    "updatedAt": time.time(),
                }
        self._save_state()

        self.slskd.request(
            "POST",
            f"/api/v0/transfers/downloads/{urllib.parse.quote(username, safe='')}",
            batch,
        )
        return {"status": "ok", "queued": len(batch)}

    def get_unified_transfers(self) -> dict[str, object]:
        slskd_transfers: list[dict[str, object]] = []
        try:
            raw = self.slskd.request("GET", "/api/v0/transfers/downloads", timeout=8.0)
            if isinstance(raw, list):
                slskd_transfers = raw
        except Exception:
            slskd_transfers = []

        merged: dict[str, dict[str, object]] = {}

        with self.lock:
            for key, job in self.jobs.items():
                merged[key] = self._public_transfer_view(job)
            completed_count = self.completed_uploads_count

        for user_entry in slskd_transfers:
            if not isinstance(user_entry, dict):
                continue
            username = str(user_entry.get("username", ""))
            for dir_entry in user_entry.get("directories") or []:
                if not isinstance(dir_entry, dict):
                    continue
                for f in dir_entry.get("files") or []:
                    if not isinstance(f, dict):
                        continue
                    fname = str(f.get("filename", ""))
                    if not fname:
                        continue
                    key = self.job_key(username, fname)
                    with self.lock:
                        existing = self.jobs.get(key)
                        existing_stage = str(existing.get("stage", "")) if existing else ""

                    # Do not clobber active or completed Drive upload states with raw slskd state.
                    if existing_stage in ("queued_upload", "uploading", "completed", "failed"):
                        continue

                    state_str = str(f.get("state", ""))
                    pct = float(f.get("percentComplete", 0.0) or 0.0)
                    speed = float(f.get("averageSpeed", 0.0) or 0.0)
                    stage, status_text = self._format_slskd_state(state_str, pct, speed)

                    merged[key] = {
                        "id": key,
                        "username": username,
                        "filename": fname,
                        "title": remote_basename(fname),
                        "size": int(f.get("size", 0) or 0),
                        "percent": round(pct, 1),
                        "speedBytesPerSec": speed,
                        "stage": stage,
                        "statusText": status_text,
                        "driveFileId": "",
                        "driveFolderPath": "",
                        "error": status_text if stage == "failed" else "",
                    }

        items = sorted(merged.values(), key=lambda x: (self._stage_rank(str(x["stage"])), str(x["title"]).lower()))
        return {
            "transfers": items,
            "completedCount": completed_count,
        }

    @staticmethod
    def _stage_rank(stage: str) -> int:
        return {
            "uploading": 0,
            "queued_upload": 1,
            "downloading": 2,
            "queued": 3,
            "failed": 4,
            "completed": 5,
        }.get(stage, 6)

    @staticmethod
    def _format_slskd_state(state_str: str, pct: float, speed_bps: float) -> tuple[str, str]:
        lower = state_str.lower()
        if "succeeded" in lower:
            return "queued_upload", "Queued for Google Drive Upload"
        if "completed" in lower and ("rejected" in lower or "errored" in lower or "cancelled" in lower or "timedout" in lower):
            return "failed", f"Transfer failed ({state_str})"
        if "inprogress" in lower or (pct > 0.0 and "queued" not in lower):
            mbps = speed_bps / (1024 * 1024)
            return "downloading", f"Downloading {pct:.0f}% ({mbps:.1f} MB/s)"
        return "queued", "Queued on Peer"

    @staticmethod
    def _public_transfer_view(job: dict[str, object]) -> dict[str, object]:
        return {
            "id": job.get("id", ""),
            "username": job.get("username", ""),
            "filename": job.get("filename", ""),
            "title": job.get("title", ""),
            "size": int(job.get("size", 0) or 0),
            "percent": round(float(job.get("percent", 0.0) or 0.0), 1),
            "speedBytesPerSec": float(job.get("speedBytesPerSec", 0.0) or 0.0),
            "stage": job.get("stage", "queued"),
            "statusText": job.get("statusText", ""),
            "driveFileId": job.get("driveFileId", ""),
            "driveFolderPath": job.get("driveFolderPath", ""),
            "error": job.get("error", ""),
        }

    def poll_and_dispatch_uploads(self) -> None:
        try:
            raw = self.slskd.request("GET", "/api/v0/transfers/downloads", timeout=8.0)
        except Exception:
            return
        if not isinstance(raw, list):
            return

        to_upload: list[tuple[str, str, str, int]] = []
        for user_entry in raw:
            if not isinstance(user_entry, dict):
                continue
            username = str(user_entry.get("username", ""))
            for dir_entry in user_entry.get("directories") or []:
                if not isinstance(dir_entry, dict):
                    continue
                for f in dir_entry.get("files") or []:
                    if not isinstance(f, dict):
                        continue
                    fname = str(f.get("filename", ""))
                    tid = str(f.get("id", ""))
                    state_str = str(f.get("state", "")).lower()
                    size = int(f.get("size", 0) or 0)
                    if not fname or not is_video_filename(fname):
                        continue
                    if "completed" in state_str and "succeeded" in state_str:
                        key = self.job_key(username, fname)
                        with self.lock:
                            existing = self.jobs.get(key)
                            stage = str(existing.get("stage", "")) if existing else ""
                            if stage in ("uploading", "completed"):
                                continue
                            # Skip files that already errored locally unless user retries via new download.
                            if stage == "failed":
                                continue
                            local_file = self.find_downloaded_file(fname, size)
                            if local_file is None:
                                continue
                            target_folder = (
                                str(existing.get("targetFolderId", "")).strip()
                                if existing
                                else ""
                            ) or self.oauth["target_folder_id"] or DEFAULT_TARGET_FOLDER_ID
                            self.jobs[key] = {
                                "id": key,
                                "username": username,
                                "filename": fname,
                                "title": remote_basename(fname),
                                "size": size or local_file.stat().st_size,
                                "percent": 0.0,
                                "speedBytesPerSec": 0.0,
                                "stage": "uploading",
                                "statusText": "Uploading to Google Drive 0%",
                                "targetFolderId": target_folder,
                                "accessToken": (
                                    str(existing.get("accessToken", ""))
                                    if existing
                                    else ""
                                )
                                or self.oauth["access_token"],
                                "refreshToken": (
                                    str(existing.get("refreshToken", ""))
                                    if existing
                                    else ""
                                )
                                or self.oauth["refresh_token"],
                                "driveFileId": "",
                                "driveFolderPath": "",
                                "error": "",
                                "updatedAt": time.time(),
                            }
                        to_upload.append((key, username, tid, str(local_file)))

        for key, username, slskd_transfer_id, local_path in to_upload:
            self.executor.submit(self._run_drive_upload, key, username, slskd_transfer_id, Path(local_path))

    def find_downloaded_file(self, remote_filename: str, expected_size: int) -> Path | None:
        if not DOWNLOADS_DIR.exists():
            return None
        parts = [p for p in normalize_sep(remote_filename).split("/") if p and not p.startswith("@@")]
        if parts:
            for start_idx in range(max(0, len(parts) - 3), len(parts)):
                candidate = DOWNLOADS_DIR.joinpath(*parts[start_idx:])
                if candidate.is_file():
                    return candidate

        target_base = remote_basename(remote_filename).lower()
        fallback: Path | None = None
        for root, _, filenames in os.walk(DOWNLOADS_DIR):
            for name in filenames:
                if name.lower() == target_base:
                    p = Path(root) / name
                    try:
                        sz = p.stat().st_size
                    except OSError:
                        continue
                    if expected_size > 0 and sz == expected_size:
                        return p
                    if sz > 0 and fallback is None:
                        fallback = p
        return fallback

    def _run_drive_upload(self, key: str, username: str, slskd_transfer_id: str, local_path: Path) -> None:
        try:
            with self.lock:
                job = dict(self.jobs.get(key, {}))
            remote_name = str(job.get("filename", local_path.name))
            root_folder_id = str(job.get("targetFolderId", "")).strip() or DEFAULT_TARGET_FOLDER_ID

            token = self._ensure_token(job)
            is_tv, show_name, season_num = parse_media_target(remote_name)

            if is_tv and show_name and season_num is not None:
                season_label = f"Season {season_num:02d}"
                show_folder_id, token = self._get_or_create_folder(show_name, root_folder_id, token)
                dest_folder_id, token = self._get_or_create_folder(season_label, show_folder_id, token)
                folder_label = f"{show_name} / {season_label}"
            else:
                dest_folder_id = root_folder_id
                folder_label = "Movies"

            def on_progress(pct: float, bps: float) -> None:
                with self.lock:
                    cur = self.jobs.get(key)
                    if cur:
                        cur["percent"] = pct
                        cur["speedBytesPerSec"] = bps
                        cur["driveFolderPath"] = folder_label
                        cur["statusText"] = f"Uploading to Google Drive {pct:.0f}%"
                        cur["updatedAt"] = time.time()

            drive_file_id, token = self._resumable_upload(local_path, dest_folder_id, token, on_progress)

            # Remove completed local file & clean empty subdirectories under downloads/
            try:
                if local_path.exists():
                    local_path.unlink()
                parent = local_path.parent
                while parent != DOWNLOADS_DIR and DOWNLOADS_DIR in parent.parents:
                    if not any(parent.iterdir()):
                        parent.rmdir()
                        parent = parent.parent
                    else:
                        break
            except OSError as cleanup_err:
                print(f"[Bridge] Warning deleting local file {local_path}: {cleanup_err}", file=sys.stderr)

            if slskd_transfer_id:
                try:
                    self.slskd.request(
                        "DELETE",
                        f"/api/v0/transfers/downloads/{urllib.parse.quote(username, safe='')}/"
                        f"{urllib.parse.quote(slskd_transfer_id, safe='')}?remove=true",
                    )
                except Exception:
                    pass

            with self.lock:
                cur = self.jobs.get(key)
                if cur:
                    cur["stage"] = "completed"
                    cur["percent"] = 100.0
                    cur["speedBytesPerSec"] = 0.0
                    cur["driveFileId"] = drive_file_id
                    cur["driveFolderPath"] = folder_label
                    cur["statusText"] = "In Google Drive ✓"
                    cur["error"] = ""
                    cur["updatedAt"] = time.time()
                self.completed_uploads_count += 1
            self._save_state()
        except Exception as exc:
            err_msg = str(exc)
            print(f"[Bridge] Drive upload failed for {local_path.name}: {err_msg}", file=sys.stderr)
            with self.lock:
                cur = self.jobs.get(key)
                if cur:
                    cur["stage"] = "failed"
                    cur["statusText"] = f"Upload failed: {err_msg}"
                    cur["error"] = err_msg
                    cur["updatedAt"] = time.time()
            self._save_state()

    def _ensure_token(self, job: dict[str, object]) -> str:
        with self.auth_lock:
            token = str(job.get("accessToken", "")).strip() or self.oauth["access_token"].strip()
            refresh = str(job.get("refreshToken", "")).strip() or self.oauth["refresh_token"].strip()
            if token:
                return token
            if not refresh:
                raise RuntimeError("Missing Google Drive OAuth token. Re-sign in in DriveStream to grant Drive write access.")
            return self._refresh_access_token_locked(refresh)

    def _refresh_access_token_locked(self, refresh_token: str) -> str:
        if not refresh_token:
            raise RuntimeError("Missing OAuth refresh_token. Please re-sign in in DriveStream.")
        body = urllib.parse.urlencode(
            {
                "client_id": GOOGLE_CLIENT_ID,
                "refresh_token": refresh_token,
                "grant_type": "refresh_token",
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            "https://oauth2.googleapis.com/token",
            data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=20.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            err_raw = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Google token refresh HTTP {e.code}: {err_raw}") from e

        new_token = str(data.get("access_token", "")).strip()
        if not new_token:
            raise RuntimeError("Google token refresh did not return access_token.")
        with self.lock:
            self.oauth["access_token"] = new_token
        self._save_state()
        return new_token

    def _refresh_with_saved(self, failed_token: str) -> str:
        with self.auth_lock:
            if self.oauth["access_token"] and self.oauth["access_token"] != failed_token:
                return self.oauth["access_token"]
            return self._refresh_access_token_locked(self.oauth["refresh_token"])

    def _drive_json_request(
        self,
        method: str,
        url: str,
        token: str,
        payload: dict[str, object] | None = None,
    ) -> tuple[dict[str, object], str]:
        active = token
        for attempt in range(2):
            body_bytes = json.dumps(payload).encode("utf-8") if payload is not None else None
            headers = {"Authorization": f"Bearer {active}"}
            if body_bytes is not None:
                headers["Content-Type"] = "application/json; charset=UTF-8"
            req = urllib.request.Request(url, data=body_bytes, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=30.0) as resp:
                    raw = resp.read().decode("utf-8", errors="replace").strip()
                    return (json.loads(raw) if raw else {}), active
            except urllib.error.HTTPError as e:
                raw_err = e.read().decode("utf-8", errors="replace")
                if e.code == 401 and attempt == 0:
                    active = self._refresh_with_saved(active)
                    continue
                raise RuntimeError(f"Drive API HTTP {e.code}: {raw_err}") from e
        raise RuntimeError("Google Drive API authentication failed")

    def _get_or_create_folder(self, name: str, parent_id: str, token: str) -> tuple[str, str]:
        cache_key = (parent_id, name.lower())
        with self.drive_folder_lock:
            cached = self.drive_folder_cache.get(cache_key)
            if cached:
                return cached, token

            escaped_name = name.replace("\\", "\\\\").replace("'", "\\'")
            query = (
                f"name = '{escaped_name}' and '{parent_id}' in parents "
                "and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            )
            list_url = (
                "https://www.googleapis.com/drive/v3/files?"
                + urllib.parse.urlencode({"q": query, "fields": "files(id,name)", "pageSize": "1"})
            )
            listed, token = self._drive_json_request("GET", list_url, token)
            files = listed.get("files") or []
            if isinstance(files, list) and files and isinstance(files[0], dict) and files[0].get("id"):
                fid = str(files[0]["id"])
                self.drive_folder_cache[cache_key] = fid
                return fid, token

            created, token = self._drive_json_request(
                "POST",
                "https://www.googleapis.com/drive/v3/files?fields=id",
                token,
                {
                    "name": name,
                    "mimeType": "application/vnd.google-apps.folder",
                    "parents": [parent_id],
                },
            )
            fid = str(created.get("id", "")).strip()
            if not fid:
                raise RuntimeError(f"Failed creating Google Drive folder '{name}'")
            self.drive_folder_cache[cache_key] = fid
            return fid, token

    def _resumable_upload(
        self,
        file_path: Path,
        parent_id: str,
        token: str,
        on_progress: callable,
    ) -> tuple[str, str]:
        total_size = file_path.stat().st_size
        if total_size <= 0:
            raise RuntimeError(f"Cannot upload empty file: {file_path.name}")

        mime_type, _ = mimetypes.guess_type(file_path.name)
        if not mime_type:
            ext = file_path.suffix.lower()
            mime_type = {
                ".mkv": "video/x-matroska",
                ".mp4": "video/mp4",
                ".m4v": "video/x-m4v",
                ".avi": "video/x-msvideo",
            }.get(ext, "application/octet-stream")

        # 1. Initiate resumable session (refresh once if 401)
        upload_uri = ""
        active = token
        meta_bytes = json.dumps({"name": file_path.name, "parents": [parent_id]}).encode("utf-8")
        for attempt in range(2):
            init_req = urllib.request.Request(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,parents",
                data=meta_bytes,
                headers={
                    "Authorization": f"Bearer {active}",
                    "Content-Type": "application/json; charset=UTF-8",
                    "X-Upload-Content-Type": mime_type,
                    "X-Upload-Content-Length": str(total_size),
                },
                method="POST",
            )
            try:
                with urllib.request.urlopen(init_req, timeout=30.0) as resp:
                    upload_uri = resp.headers.get("Location", "").strip()
                    break
            except urllib.error.HTTPError as e:
                err_raw = e.read().decode("utf-8", errors="replace")
                if e.code == 401 and attempt == 0:
                    active = self._refresh_with_saved(active)
                    continue
                raise RuntimeError(f"Resumable init HTTP {e.code}: {err_raw}") from e

        if not upload_uri:
            raise RuntimeError("Google Drive did not return resumable Location header.")

        # 2. Stream chunks
        uploaded = 0
        with file_path.open("rb") as fp:
            while uploaded < total_size:
                fp.seek(uploaded)
                chunk = fp.read(CHUNK_SIZE_BYTES)
                if not chunk:
                    break
                end_byte = uploaded + len(chunk) - 1
                start_ts = time.monotonic()

                chunk_req = urllib.request.Request(
                    upload_uri,
                    data=chunk,
                    headers={
                        "Content-Length": str(len(chunk)),
                        "Content-Range": f"bytes {uploaded}-{end_byte}/{total_size}",
                        "Content-Type": mime_type,
                    },
                    method="PUT",
                )
                try:
                    with urllib.request.urlopen(chunk_req, timeout=180.0) as resp:
                        result_raw = resp.read().decode("utf-8", errors="replace").strip()
                        result_obj = json.loads(result_raw) if result_raw else {}
                        drive_id = str(result_obj.get("id", "")).strip()
                        if not drive_id:
                            raise RuntimeError("Drive upload finished without file ID")
                        elapsed = max(0.05, time.monotonic() - start_ts)
                        on_progress(100.0, len(chunk) / elapsed)
                        return drive_id, active
                except urllib.error.HTTPError as e:
                    if e.code == 308:
                        range_hdr = e.headers.get("Range", "")
                        if range_hdr and "-" in range_hdr:
                            last_ack = int(range_hdr.rsplit("-", 1)[-1])
                            uploaded = last_ack + 1
                        else:
                            uploaded = end_byte + 1
                        elapsed = max(0.05, time.monotonic() - start_ts)
                        pct = min(99.9, (uploaded / total_size) * 100.0)
                        on_progress(pct, len(chunk) / elapsed)
                        continue
                    err_raw = e.read().decode("utf-8", errors="replace")
                    raise RuntimeError(f"Chunk upload HTTP {e.code}: {err_raw}") from e

        raise RuntimeError("Resumable upload ended unexpectedly before completion.")


COORDINATOR = BridgeCoordinator(SlskdClient())


class BridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        # Keep console focused on key lifecycle logs; silence routine /api/transfers 2s poll noise.
        if args and "/api/transfers" in str(args[0]):
            return
        super().log_message(fmt, *args)

    def _send_json(self, status: int, payload: object) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def _read_json_body(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0 or length > 8 * 1024 * 1024:
            return {}
        raw = self.rfile.read(length).decode("utf-8", errors="replace")
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        try:
            if path == "/api/health":
                self._send_json(
                    HTTPStatus.OK,
                    {"ok": True, "service": "drivestream-soulseek-bridge", "version": 1},
                )
                return

            if path == "/api/transfers":
                self._send_json(HTTPStatus.OK, COORDINATOR.get_unified_transfers())
                return

            if path.startswith("/api/search/"):
                search_id = urllib.parse.unquote(path[len("/api/search/") :]).strip("/")
                if not search_id:
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Missing searchId"})
                    return
                self._send_json(HTTPStatus.OK, COORDINATOR.get_search_results(search_id))
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
        except Exception as exc:
            self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        try:
            body = self._read_json_body()
            if path == "/api/search":
                query = str(body.get("query", "")).strip()
                if not query:
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Missing query"})
                    return
                self._send_json(HTTPStatus.OK, COORDINATOR.start_search(query))
                return

            if path == "/api/download":
                self._send_json(HTTPStatus.OK, COORDINATOR.enqueue_download(body))
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except Exception as exc:
            self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})


def start_watcher(stop_event: threading.Event) -> None:
    def loop() -> None:
        while not stop_event.is_set():
            COORDINATOR.poll_and_dispatch_uploads()
            stop_event.wait(POLL_INTERVAL_SECONDS)

    t = threading.Thread(target=loop, name="slskd-transfer-watcher", daemon=True)
    t.start()


def main() -> None:
    DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    stop_event = threading.Event()
    start_watcher(stop_event)

    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer(("0.0.0.0", BRIDGE_PORT), BridgeHandler)
    print(f"[Bridge] Listening on http://0.0.0.0:{BRIDGE_PORT} -> slskd {SLSKD_URL}")
    print(f"[Bridge] Default Google Drive target folder: {DEFAULT_TARGET_FOLDER_ID}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[Bridge] Shutting down...")
    finally:
        stop_event.set()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
