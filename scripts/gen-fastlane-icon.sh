#!/bin/bash
# Regenerate fastlane/metadata/android/en-US/images/icon.png — the store
# icon F-Droid shows in listings.
#
# The launcher icon itself is an adaptive icon with no raster form in the
# tree, so this renders one from the same two sources the app uses:
#
#   app/src/main/res/drawable/ic_launcher_foreground.xml   foreground paths
#   app/src/main/res/values/icon_colors.xml                background colour
#
# The result is the unmasked adaptive icon: full square canvas, background
# colour behind the foreground drawable, no launcher shape applied. That is
# the same convention as Android Studio's `ic_launcher-playstore.png`.
#
# Re-run this after any change to either source file; the PNG is checked in
# and nothing builds it automatically. See notes/building.md
# ("Store icon") for how it fits into F-Droid metadata.
#
# Requires: python3 (vector -> SVG), and one of rsvg-convert / inkscape /
# magick for the raster step.
#
# Usage:
#   scripts/gen-fastlane-icon.sh              # 512x512 to the default path
#   scripts/gen-fastlane-icon.sh --size=1024
#   scripts/gen-fastlane-icon.sh --out=/tmp/icon.png
#   scripts/gen-fastlane-icon.sh --svg-only   # print the SVG, render nothing
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VECTOR="$ROOT_DIR/app/src/main/res/drawable/ic_launcher_foreground.xml"
COLORS="$ROOT_DIR/app/src/main/res/values/icon_colors.xml"
BG_COLOR_NAME="tawc_icon_bg"
OUT="$ROOT_DIR/fastlane/metadata/android/en-US/images/icon.png"
SIZE=512
SVG_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --size=*) SIZE="${arg#--size=}" ;;
        --out=*) OUT="${arg#--out=}" ;;
        --svg-only) SVG_ONLY=1 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 1 ;;
    esac
done

command -v python3 >/dev/null || { echo "ERROR: python3 not found" >&2; exit 1; }
[ -f "$VECTOR" ] || { echo "ERROR: missing $VECTOR" >&2; exit 1; }
[ -f "$COLORS" ] || { echo "ERROR: missing $COLORS" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Vector drawable -> SVG. Android's pathData *is* SVG path syntax, so this is
# a structural translation, not a redraw: viewport becomes the viewBox, each
# <group>'s scale/pivot becomes a transform, each <path> keeps its data and
# fill verbatim. Only the subset of the vector format this icon actually uses
# is handled; the script errors on anything it doesn't understand rather than
# silently dropping it from the render.
svg="$(python3 - "$VECTOR" "$COLORS" "$BG_COLOR_NAME" <<'PY'
import sys, xml.etree.ElementTree as ET

vector_path, colors_path, bg_name = sys.argv[1:4]
A = "{http://schemas.android.com/apk/res/android}"


def attr(el, name, default=None):
    return el.get(A + name, default)


def die(msg):
    sys.exit("ERROR: %s (%s)" % (msg, vector_path))


bg = None
for c in ET.parse(colors_path).getroot().iter("color"):
    if c.get("name") == bg_name:
        bg = (c.text or "").strip()
if not bg:
    sys.exit("ERROR: colour %s not found in %s" % (bg_name, colors_path))

root = ET.parse(vector_path).getroot()
if root.tag != "vector":
    die("root element is <%s>, expected <vector>" % root.tag)
vw = float(attr(root, "viewportWidth") or die("no viewportWidth"))
vh = float(attr(root, "viewportHeight") or die("no viewportHeight"))

out = []


def emit_path(el, indent):
    data = attr(el, "pathData")
    if not data:
        die("<path> without pathData")
    fill = attr(el, "fillColor", "#000000")
    alpha = attr(el, "fillAlpha")
    extra = ' fill-opacity="%s"' % alpha if alpha else ""
    stroke = attr(el, "strokeColor")
    if stroke:
        extra += ' stroke="%s" stroke-width="%s"' % (
            stroke, attr(el, "strokeWidth", "1"))
    out.append('%s<path fill="%s"%s d="%s"/>' % (indent, fill, extra, data))


def emit_group(el, indent):
    # scale/rotate happen about (pivotX, pivotY); SVG has no pivot, so
    # bracket the transform with translations to and from the pivot.
    px = float(attr(el, "pivotX", "0"))
    py = float(attr(el, "pivotY", "0"))
    sx = float(attr(el, "scaleX", "1"))
    sy = float(attr(el, "scaleY", "1"))
    rot = float(attr(el, "rotation", "0"))
    tx = float(attr(el, "translateX", "0"))
    ty = float(attr(el, "translateY", "0"))
    parts = []
    if (tx, ty) != (0.0, 0.0):
        parts.append("translate(%g,%g)" % (tx, ty))
    if (px, py) != (0.0, 0.0):
        parts.append("translate(%g,%g)" % (px, py))
    if rot:
        parts.append("rotate(%g)" % rot)
    if (sx, sy) != (1.0, 1.0):
        parts.append("scale(%g,%g)" % (sx, sy))
    if (px, py) != (0.0, 0.0):
        parts.append("translate(%g,%g)" % (-px, -py))
    out.append('%s<g transform="%s">' % (indent, " ".join(parts)))
    walk(el, indent + "  ")
    out.append("%s</g>" % indent)


def walk(parent, indent):
    for el in parent:
        if el.tag == "path":
            emit_path(el, indent)
        elif el.tag == "group":
            emit_group(el, indent)
        elif el.tag == "clip-path":
            die("<clip-path> is not translated; extend this script")
        else:
            die("unhandled element <%s>" % el.tag)


walk(root, "  ")

print('<?xml version="1.0" encoding="UTF-8"?>')
print('<svg xmlns="http://www.w3.org/2000/svg" width="%g" height="%g" '
      'viewBox="0 0 %g %g">' % (vw, vh, vw, vh))
print('  <rect width="%g" height="%g" fill="%s"/>' % (vw, vh, bg))
print("\n".join(out))
print("</svg>")
PY
)"

if [ "$SVG_ONLY" = 1 ]; then
    printf '%s\n' "$svg"
    exit 0
fi

# ---------------------------------------------------------------------------
# SVG -> PNG. Any of these renderers produces the same image for this icon
# (flat fills, no text, no filters); we just take whichever is installed.
tmp_svg="$(mktemp -t tawc-icon-XXXXXX.svg)"
trap 'rm -f "$tmp_svg"' EXIT
printf '%s\n' "$svg" >"$tmp_svg"
mkdir -p "$(dirname "$OUT")"

if command -v rsvg-convert >/dev/null; then
    rsvg-convert -w "$SIZE" -h "$SIZE" -o "$OUT" "$tmp_svg"
elif command -v inkscape >/dev/null; then
    inkscape "$tmp_svg" -w "$SIZE" -h "$SIZE" -o "$OUT" >/dev/null 2>&1
elif command -v magick >/dev/null; then
    magick -background none "$tmp_svg" -resize "${SIZE}x${SIZE}" "$OUT"
else
    echo "ERROR: need one of rsvg-convert, inkscape, or magick" >&2
    exit 1
fi

echo "==> wrote $OUT (${SIZE}x${SIZE})"
