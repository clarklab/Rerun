#!/usr/bin/env bash
# Converts any pile of video files into CRT-ready clips inside the APK's assets.
#
#   tools/prepare_clips.sh [--letterbox] [--max-seconds N] <input dir or files...>
#
# Every clip becomes 640x480 (4:3) H.264 + AAC with loudness evened out, so the shuffle
# doesn't jump from whisper-quiet to blaring. Widescreen sources are center-cropped to fill
# the tube; pass --letterbox to keep the full width with black bars instead.
# New files also get a row in clips.csv (title/year guessed from the filename) for you to finish.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/clips"
CSV="$OUT/clips.csv"
MODE=crop
MAX_SECONDS=""

inputs=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --letterbox) MODE=letterbox; shift ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) inputs+=("$1"); shift ;;
  esac
done
[[ ${#inputs[@]} -gt 0 ]] || { echo "usage: $0 [--letterbox] [--max-seconds N] <dir or files...>" >&2; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }

mkdir -p "$OUT"
[[ -f "$CSV" ]] || echo "file,title,director,year,studio" > "$CSV"

if [[ "$MODE" == crop ]]; then
  # Scale so the frame covers 640x480, then trim the overflow (honors anamorphic DVD sources via SAR).
  VF="scale='trunc(iw*sar)':ih,setsar=1,scale=640:480:force_original_aspect_ratio=increase,crop=640:480"
else
  VF="scale='trunc(iw*sar)':ih,setsar=1,scale=640:480:force_original_aspect_ratio=decrease,pad=640:480:(ow-iw)/2:(oh-ih)/2"
fi
VF="$VF,setsar=1,fps=30000/1001,format=yuv420p"

files=()
for arg in "${inputs[@]}"; do
  if [[ -d "$arg" ]]; then
    while IFS= read -r -d '' f; do files+=("$f"); done < <(
      find "$arg" -type f \( -iname '*.mp4' -o -iname '*.m4v' -o -iname '*.mkv' -o -iname '*.mov' \
        -o -iname '*.avi' -o -iname '*.webm' -o -iname '*.mpg' -o -iname '*.mpeg' -o -iname '*.wmv' \
        -o -iname '*.flv' -o -iname '*.ts' -o -iname '*.vob' \) -print0 | sort -z)
  else
    files+=("$arg")
  fi
done

for src in "${files[@]}"; do
  base="$(basename "${src%.*}")"
  # Asset names: keep it filesystem- and URI-safe, but readable (the title fallback parses it).
  safe="$(printf '%s' "$base" | tr -c 'A-Za-z0-9()._ -' '_' | sed 's/  */ /g; s/^ //; s/ $//')"
  dest="$OUT/$safe.mp4"
  if [[ -f "$dest" ]]; then echo "skip   $safe.mp4 (already converted)"; continue; fi

  echo "encode $safe.mp4"
  limit=()
  [[ -n "$MAX_SECONDS" ]] && limit=(-t "$MAX_SECONDS")
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$src" "${limit[@]}" \
    -map 0:v:0 -map 0:a:0? -sn -dn \
    -vf "$VF" \
    -c:v libx264 -profile:v main -level 3.1 -preset slow -crf 24 -maxrate 1500k -bufsize 3000k \
    -g 60 -c:a aac -b:a 128k -ac 2 -ar 48000 -af "loudnorm=I=-16:TP=-1.5:LRA=11" \
    -movflags +faststart "$dest.tmp.mp4"
  mv "$dest.tmp.mp4" "$dest"

  if ! grep -qiF "$safe.mp4," "$CSV"; then
    title="${base//_/ }"; year=""
    year_re='^(.*[^ (-])[ (-]*((19|20)[0-9][0-9])\)?$'
    if [[ "$title" =~ $year_re ]]; then
      title="${BASH_REMATCH[1]}"; year="${BASH_REMATCH[2]}"
    fi
    printf '%s,"%s",,%s,\n' "$safe.mp4" "${title//\"/\"\"}" "$year" >> "$CSV"
  fi
done

count=$(find "$OUT" -maxdepth 1 -name '*.mp4' | wc -l)
size=$(du -sh "$OUT" | cut -f1)
echo "done: $count clips, $size in $OUT"
echo "now fill in director/year/studio in $CSV, then: ./gradlew assembleRelease"
