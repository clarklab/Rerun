#!/usr/bin/env bash
# Downloads YouTube (or any yt-dlp-supported) links at <=480p, then converts them into
# CRT-ready clips in the APK's assets via prepare_clips.sh.
#
#   tools/fetch_clips.sh [links.txt]          # default: tools/links.txt
#   tools/fetch_clips.sh <url> [<url>...]
#
# links.txt, one clip per line ("#" starts a comment):
#   URL  [START-END]  [| File Name (Year)]  [| prepare_clips.sh options]
#   https://youtu.be/abc123
#   https://youtu.be/abc123  1:05-2:30                                  # just that section
#   https://youtu.be/abc123  0:00-5:40 | Carrie (1976) Prom             # and name the file
#   https://youtu.be/abc123            | Freddy vs Jason (2003) | --letterbox --mask-bars 12.5
# Without a name the YouTube title is used. Card text comes from clips.csv when the file has a row.
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

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
failed=()

for raw in "${lines[@]}"; do
  line="${raw%%#*}"                       # strip comments
  IFS='|' read -r head name opts <<< "$line"
  name="$(echo "${name:-}" | xargs)"
  read -r url range _ <<< "$head" || true
  [[ -n "${url:-}" ]] || continue

  if [[ -z "$name" ]]; then
    name="$(yt-dlp ${YTDLP_ARGS:-} --no-warnings --skip-download --print title "$url" 2>/dev/null || true)"
    [[ -n "$name" ]] || name="$(basename "$url")"
  fi
  file="$(sanitize "$name")"
  # Tag ranged downloads so re-running with a different range fetches again.
  stored="$file"
  [[ -n "${range:-}" ]] && stored="$file [${range//:/.}]"

  if compgen -G "$DL/$(printf '%s' "$stored" | sed 's/[][*?]/\\&/g').*" >/dev/null; then
    echo "have   $stored"
  else
    echo "fetch  $url ${range:-} -> $file"
    section=()
    [[ -n "${range:-}" ]] && section=(--download-sections "*$range" --force-keyframes-at-cuts)
    # Prefer <=480p (plenty for a CRT, and quick to download); fall back to the smallest available.
    # shellcheck disable=SC2086
    if ! yt-dlp ${YTDLP_ARGS:-} --no-playlist --no-warnings --quiet --progress \
      -f "bv*[height<=480]+ba/b[height<=480]/wv*+ba/w" -S "res:480" \
      --merge-output-format mkv "${section[@]}" \
      -o "$DL/$stored.%(ext)s" "$url"; then
      failed+=("$url"); continue
    fi
  fi

  src="$(ls "$DL/$stored".* | grep -v '\.part$' | head -1)"
  ln -sf "$src" "$staging/$file.${src##*.}"
  # shellcheck disable=SC2086
  "$ROOT/tools/prepare_clips.sh" ${opts:-} "$staging/$file.${src##*.}" > "$staging/log" 2>&1 \
    && { grep -v '^\(done\|now\)' "$staging/log" || true; } \
    || { cat "$staging/log" >&2; failed+=("$url (conversion)"); }
done

count=$(find "$ROOT/app/src/main/assets/clips" -maxdepth 1 -name '*.mp4' | wc -l)
echo "done: $count clips, $(du -sh "$ROOT/app/src/main/assets/clips" | cut -f1) in app/src/main/assets/clips"
if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED:" >&2; printf '  %s\n' "${failed[@]}" >&2; exit 1
fi
