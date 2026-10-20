#!/usr/bin/env bash
# Downloads YouTube (or any yt-dlp-supported) links at <=480p, then converts them into
# CRT-ready clips in the APK's assets via prepare_clips.sh.
#
#   tools/fetch_clips.sh [links.txt]          # default: tools/links.txt
#   tools/fetch_clips.sh <url> [<url>...]
#
# links.txt, one clip per line ("#" starts a comment):
#   https://youtu.be/abc123
#   https://youtu.be/abc123  1:05-2:30                         # just that section
#   https://youtu.be/abc123  1:05-2:30 | Back to the Future (1985)   # and name it
# The name after "|" becomes the clip's title/year in clips.csv; without it the YouTube title is used.
#
# Run this on your own computer: YouTube blocks most cloud/datacenter IPs.
# Extra yt-dlp flags can be passed through YTDLP_ARGS, e.g. YTDLP_ARGS="--cookies-from-browser firefox".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DL="$ROOT/tools/downloads"
command -v yt-dlp >/dev/null || { echo "yt-dlp is required: pip install -U yt-dlp  (or brew install yt-dlp)" >&2; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
mkdir -p "$DL"

lines=()
if [[ $# -eq 0 || ( $# -eq 1 && -f "$1" ) ]]; then
  list="${1:-$ROOT/tools/links.txt}"
  [[ -f "$list" ]] || { echo "no links file at $list" >&2; exit 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do lines+=("$line"); done < "$list"
else
  lines=("$@")
fi

# Filenames the converter can parse back into "Title (Year)".
sanitize() { printf '%s' "$1" | tr -c 'A-Za-z0-9()._ -' '_' | sed 's/__*/_/g; s/  */ /g; s/^[ _]*//; s/[ _]*$//' | cut -c1-120; }

fetched=()
for raw in "${lines[@]}"; do
  line="${raw%%#*}"                       # strip comments
  name=""
  if [[ "$line" == *"|"* ]]; then name="$(echo "${line#*|}" | xargs)"; line="${line%%|*}"; fi
  read -r url range _ <<< "$line" || true
  [[ -n "${url:-}" ]] || continue

  if [[ -z "$name" ]]; then
    name="$(yt-dlp ${YTDLP_ARGS:-} --no-warnings --skip-download --print title "$url" 2>/dev/null || true)"
    [[ -n "$name" ]] || name="$(basename "$url")"
  fi
  file="$(sanitize "$name")"
  [[ -n "${range:-}" ]] && file="$file [${range//:/.}]"

  if compgen -G "$DL/$(printf '%s' "$file" | sed 's/[][*?]/\\&/g').*" >/dev/null; then
    echo "have   $file"
  else
    echo "fetch  $url ${range:-} -> $file"
    section=()
    [[ -n "${range:-}" ]] && section=(--download-sections "*$range" --force-keyframes-at-cuts)
    # Prefer <=480p (plenty for a CRT, and quick to download); fall back to the smallest available.
    # shellcheck disable=SC2086
    yt-dlp ${YTDLP_ARGS:-} --no-playlist --no-warnings \
      -f "bv*[height<=480]+ba/b[height<=480]/wv*+ba/w" -S "res:480" \
      --merge-output-format mkv "${section[@]}" \
      -o "$DL/$file.%(ext)s" "$url"
  fi
  fetched+=("$(ls "$DL/$file".* | head -1)")
done

[[ ${#fetched[@]} -gt 0 ]] || { echo "nothing to fetch"; exit 0; }
# Ranged files carry a "[1.05-2.30]" tag so two sections of one video don't collide;
# hand them to the converter under the clean name.
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
for f in "${fetched[@]}"; do
  base="$(basename "$f")"
  clean="$(echo "${base%.*}" | sed 's/ \[[0-9.]*-[0-9.]*\]$//')"
  n=1; target="$staging/$clean.${base##*.}"
  while [[ -e "$target" ]]; do n=$((n + 1)); target="$staging/$clean $n.${base##*.}"; done
  ln -s "$f" "$target"
done
"$ROOT/tools/prepare_clips.sh" "$staging"/*
