"""One-off B1 helper: fetch Material icon SVGs and emit Android vector drawables."""
import re
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/src"
ICONS = [
    ("auto_awesome", "filled"),
    ("close", "filled"),
    ("chat_bubble_outline", "filled"),
    ("hourglass_top", "filled"),
    ("error_outline", "filled"),
    ("wifi_off", "filled"),
    ("refresh", "filled"),
    ("lock", "filled"),
    ("check", "filled"),
    ("content_copy", "filled"),
    ("send", "filled"),
    ("info", "filled"),
    ("format_list_bulleted", "filled"),
    ("check_circle", "filled"),
    ("checklist", "filled"),
    ("help_outline", "filled"),
    ("forum", "outlined"),
    ("arrow_back", "filled"),
    ("edit", "filled"),
    ("language", "filled"),
    ("palette", "filled"),
    ("visibility", "filled"),
    ("logout", "filled"),
    ("devices", "filled"),
    ("image", "filled"),
    ("description", "filled"),
    ("play_arrow", "filled"),
    ("music_note", "filled"),
    ("people", "filled"),
    ("account_circle", "filled"),
    ("add", "filled"),
    ("delete", "filled"),
    ("person", "outlined", "person_outline"),
    ("search", "filled"),
    ("search", "outlined", "search_outline"),
    ("reply", "filled"),
    ("forward", "filled"),
    ("push_pin", "filled"),
    ("call", "filled"),
    ("more_vert", "filled"),
    ("keyboard_arrow_down", "filled"),
    ("attach_file", "filled"),
    ("warning", "filled"),
    ("keyboard_arrow_right", "filled"),
    ("visibility_off", "filled"),
]
AUTO_MIRROR = {
    "arrow_back",
    "send",
    "reply",
    "forward",
    "format_list_bulleted",
    "help_outline",
    "logout",
    "keyboard_arrow_right",
}
OUT_DIR = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable"


def svg_to_vector(svg_text: str, automirror: bool) -> str | None:
    paths = re.findall(r'<path[^>]+d="([^"]+)"', svg_text)
    if not paths:
        paths = re.findall(r"d='([^']+)'", svg_text)
    if not paths:
        return None
    am = ' android:autoMirrored="true"' if automirror else ""
    paths_xml = "".join(
        f'    <path android:fillColor="@android:color/white" android:pathData="{d}"/>\n'
        for d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        f'    android:viewportHeight="24"{am}>\n'
        f"{paths_xml}</vector>\n"
    )


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ok = fail = 0
    for item in ICONS:
        if len(item) == 3:
            icon, style, out_name = item
        else:
            icon, style = item
            out_name = icon if style != "outlined" else f"{icon}_outline"
        folder = "materialicons" if style == "filled" else "materialiconsoutlined"
        url = f"{BASE}/{icon}/{folder}/24px.svg"
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                svg = resp.read().decode("utf-8")
            mirror = out_name.replace("_outline", "") in AUTO_MIRROR or out_name in AUTO_MIRROR
            vec = svg_to_vector(svg, mirror)
            if not vec:
                fail += 1
                print("no paths", icon, style)
                continue
            (OUT_DIR / f"ic_{out_name}.xml").write_text(vec, encoding="utf-8")
            ok += 1
        except Exception as exc:
            fail += 1
            print("FAIL", icon, style, exc)
    print("done", ok, "ok", fail, "fail")


if __name__ == "__main__":
    main()
