"""Lightweight unit checks for drive_uploader_bridge.py parsing, filtering, and sorting."""

import tempfile
from pathlib import Path
from unittest.mock import patch

import drive_uploader_bridge as bridge


def test_parse_media_target() -> None:
    assert bridge.parse_media_target(r"@@user\TV\The.Office.US.S01E03.1080p.WEB-DL.x264.mkv") == (True, "The Office US", 1)
    assert bridge.parse_media_target(r"@@user\Shows\Breaking Bad\Season 2\2x05 - Breakage.mp4") == (True, "Breaking Bad", 2)
    assert bridge.parse_media_target(r"@@user\TV\Andor\Season 01\S01E01.mkv") == (True, "Andor", 1)
    assert bridge.parse_media_target(r"@@user\Movies\Red Dragon (2002) 1080p.mkv") == (False, None, None)


def test_search_filtering_and_sorting() -> None:
    fake_slskd = bridge.SlskdClient()
    fake_resp = {
        "isComplete": True,
        "responses": [
            {
                "username": "slow_busy",
                "hasFreeUploadSlot": False,
                "uploadSpeed": 9_000_000,
                "queueLength": 5,
                "files": [
                    {"filename": r"@@slow_busy\The Office\Season 1\S01E01.mkv", "size": 500_000_000},
                    {"filename": r"@@slow_busy\The Office\Season 1\cover.jpg", "size": 100_000},
                ],
            },
            {
                "username": "fast_free",
                "hasFreeUploadSlot": True,
                "uploadSpeed": 6_000_000,
                "queueLength": 0,
                "files": [
                    {"filename": r"@@fast_free\The.Office.S01.1080p\S01E01.1080p.mkv", "size": 900_000_000},
                    {"filename": r"@@fast_free\The.Office.S01.1080p\S01E02.1080p.mkv", "size": 950_000_000},
                    {"filename": r"@@fast_free\The.Office.S01.1080p\notes.nfo", "size": 1200},
                ],
            },
        ],
    }

    with patch.object(fake_slskd, "request", return_value=fake_resp):
        coord = bridge.BridgeCoordinator(fake_slskd)
        res = coord.get_search_results("s1")

    assert len(res["files"]) == 3
    assert all(f["filename"].endswith((".mkv", ".mp4", ".m4v", ".avi")) for f in res["files"])
    # Free upload slot first, then larger file first at equal speed
    assert res["files"][0]["username"] == "fast_free"
    assert res["files"][0]["size"] == 950_000_000
    assert res["files"][-1]["username"] == "slow_busy"

    assert len(res["folders"]) == 2
    assert res["folders"][0]["username"] == "fast_free"
    assert res["folders"][0]["fileCount"] == 2
    assert res["folders"][0]["qualityTag"] == "1080p"


def test_find_downloaded_file() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        dl = Path(tmp) / "downloads"
        sub = dl / "The.Office.S01"
        sub.mkdir(parents=True)
        ep = sub / "S01E01.mkv"
        ep.write_bytes(b"12345")
        with patch.object(bridge, "DOWNLOADS_DIR", dl):
            coord = bridge.BridgeCoordinator(bridge.SlskdClient())
            found = coord.find_downloaded_file(r"@@peer\TV\The.Office.S01\S01E01.mkv", 5)
            assert found == ep


def test_lan_ip_detection() -> None:
    assert not bridge.is_usable_lan_ipv4("172.20.96.1")
    assert not bridge.is_usable_lan_ipv4("169.254.154.173")
    assert not bridge.is_usable_lan_ipv4("127.0.0.1")
    assert bridge.is_usable_lan_ipv4("192.168.1.42")

    sample_ipconfig = """
Windows IP Configuration

Ethernet adapter Ethernet:
   Connection-specific DNS Suffix  . : home
   IPv4 Address. . . . . . . . . . . : 192.168.1.42(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.0
   Default Gateway . . . . . . . . . : 192.168.1.1

Ethernet adapter vEthernet (Default Switch):
   IPv4 Address. . . . . . . . . . . : 172.20.96.1(Preferred)
   Default Gateway . . . . . . . . . :

Ethernet adapter Radmin VPN:
   IPv4 Address. . . . . . . . . . . : 26.249.209.14(Preferred)
   Default Gateway . . . . . . . . . : 26.0.0.1

Wireless LAN adapter Wi-Fi 2:
   Autoconfiguration IPv4 Address. . : 169.254.154.173(Preferred)
   Default Gateway . . . . . . . . . :
"""
    assert bridge.parse_windows_ipconfig_lan_ip(sample_ipconfig) == "192.168.1.42"
    payload = bridge.BridgeHandler._health_payload()
    assert payload["status"] == "ok" and payload["ok"] is True
    assert payload["service"] == "drivestream-soulseek-bridge"
    assert payload["port"] == bridge.BRIDGE_PORT
    assert isinstance(payload["slskdReady"], bool)


if __name__ == "__main__":
    test_parse_media_target()
    test_search_filtering_and_sorting()
    test_find_downloaded_file()
    test_lan_ip_detection()
    print("All soulseek-bridge self-tests passed!")
