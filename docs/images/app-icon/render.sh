#!/usr/bin/env bash
# Renders app-icon.svg into the iOS app icon and Android's pre-8.0 launcher
# PNGs. Needs ImageMagick. Run from anywhere; rerun after editing the SVG.
# Android 8.0+ uses the vector adaptive icon instead (drawable*/ic_launcher_*.xml).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
svg="$here/app-icon.svg"
res="$root/androidApp/src/main/res"

# Crop to the middle 78 of the 108 units: the adaptive-icon margin is wasted
# on a square icon, so the handle fills more of it.
square() { # size out
  local big=$(( $1 * 108 / 78 ))
  magick -background none -density 1200 "$svg" -resize "${big}x${big}" \
    -gravity center -extent "$1x$1" -depth 8 "$2"
}

# App Store icons must be opaque.
square 1024 "$root/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
magick "$root/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png" \
  -alpha remove -alpha off "$root/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png"

for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  dir="$res/mipmap-${d%%:*}" size=${d##*:}
  tmp=$(mktemp -t icon).png
  square "$size" "$tmp"
  r=$(( size / 6 ))
  magick "$tmp" \( -size "${size}x${size}" xc:none -fill white \
    -draw "roundrectangle 0,0 $((size - 1)),$((size - 1)) $r,$r" \) \
    -compose DstIn -composite "$dir/ic_launcher.png"
  magick "$tmp" \( -size "${size}x${size}" xc:none -fill white \
    -draw "circle $((size / 2)),$((size / 2)) $((size / 2)),0" \) \
    -compose DstIn -composite "$dir/ic_launcher_round.png"
  rm "$tmp"
done
