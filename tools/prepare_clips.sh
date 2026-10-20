#!/usr/bin/env bash
# Converts any pile of video files into CRT-ready clips inside the APK's assets.
#
#   tools/prepare_clips.sh [--letterbox] [--no-autocrop] [--max-seconds N] <input dir or files...>
#
# Every clip becomes 640x480 (4:3) H.264 + AAC with loudness evened out, so the shuffle
# doesn't jump from whisper-quiet to blaring. Widescreen sources are center-cropped to fill
# the tube; pass --letterbox to keep the full width with black bars instead.
# Black bars baked into the source (a 4:3 show pillarboxed in a 16:9 upload, a scope film
# letterboxed) are detected and trimmed first; --no-autocrop turns that off.
# New files also get a row in clips.csv (title/year guessed from the filename) for you to finish.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/clips"
CSV="$OUT/clips.csv"
MODE=crop
MAX_SECONDS=""
AUTOCROP=1

inputs=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --letterbox) MODE=letterbox; shift ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --no-autocrop) AUTOCROP=0; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) inputs+=("$1"); shift ;;
  esac
done
[[ ${#inputs[@]} -gt 0 ]] || { echo "usage: $0 [--letterbox] [--no-autocrop] [--max-seconds N] <dir or files...>" >&2; exit 1; }
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

# Prints "crop=w:h:x:y," when the source has black bars worth trimming, else nothing.
# cropdetect with reset=0 keeps the widest picture seen across the clip, so dark scenes don't over-crop.
detect_bars() {
  local src="$1" dims w h crop cw ch
  dims="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$src")"
  w="${dims%%x*}"; h="${dims##*x}"
  crop="$(ffmpeg -nostdin -hide_banner -i "$src" ${MAX_SECONDS:+-t "$MAX_SECONDS"} -an -sn \
    -vf "fps=2,cropdetect=limit=24:round=2:reset=0" -f null - 2>&1 | grep -o 'crop=[0-9:]*' | tail -1)"
  [[ -n "$crop" && -n "$w" && -n "$h" ]] || return 0
  IFS=: read -r cw ch _ _ <<< "${crop#crop=}"
  # Ignore slivers (under 3% per axis) and implausible results (nearly everything black).
  if (( (cw * 100 < w * 97 || ch * 100 < h * 97) && cw * 3 > w && ch * 3 > h )); then
    printf '%s,' "$crop"
  fi
}

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
  bars=""
  [[ "$AUTOCROP" == 1 ]] && bars="$(detect_bars "$src")"
  [[ -n "$bars" ]] && echo "       trimming black bars (${bars%,})"
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$src" "${limit[@]}" \
    -map 0:v:0 -map 0:a:0? -sn -dn \
    -vf "$bars$VF" \
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
