#!/usr/bin/env bash
# Музыка сезонных тем для iOS.
#
# AVAudioPlayer не играет Ogg Vorbis, поэтому из app/src/main/assets/music/<track>.ogg
# (+ <track>.json с точкой петли loopStart в сэмплах) делаются:
#   <track>_intro.caf - вступление: сэмплы [0, loopStart), играет один раз;
#   <track>.caf       - петля:      сэмплы [loopStart, конец), крутится бесконечно.
#
# По умолчанию ALAC в CAF: без «разгонной тишины» кодека => петля без щелчка и паузы.
# IOS_AUDIO_CODEC=aac - файлы в ~5 раз меньше (.m4a), но на стыке петли возможна крошечная пауза.
#
# Использование: scripts/ios/convert_audio.sh [папка_ogg] [папка_вывода]
set -euo pipefail

SRC="${1:-app/src/main/assets/music}"
DST="${2:-iosApp/Resources/music}"
CODEC="${IOS_AUDIO_CODEC:-alac}"

command -v ffmpeg >/dev/null || { echo "ffmpeg не найден (brew install ffmpeg)"; exit 1; }
mkdir -p "$DST"

case "$CODEC" in
  alac) EXT=caf; ENC=(-c:a alac -sample_fmt s16p) ;;
  aac)  EXT=m4a; ENC=(-c:a aac -b:a 160k -movflags +faststart) ;;
  *) echo "IOS_AUDIO_CODEC: alac | aac"; exit 1 ;;
esac

shopt -s nullglob
files=("$SRC"/*.ogg)
[ ${#files[@]} -gt 0 ] || { echo "Нет .ogg в $SRC"; exit 1; }

for ogg in "${files[@]}"; do
  name="$(basename "$ogg" .ogg)"
  meta="$SRC/$name.json"
  loop=0
  if [ -f "$meta" ]; then
    loop="$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1])).get("loopStart", 0)))' "$meta")"
  fi
  ff=(ffmpeg -hide_banner -loglevel error -y -i "$ogg" -map_metadata -1 -vn)
  if [ "$loop" -gt 0 ]; then
    "${ff[@]}" -af "atrim=end_sample=${loop}" "${ENC[@]}" "$DST/${name}_intro.$EXT"
    "${ff[@]}" -af "atrim=start_sample=${loop},asetpts=PTS-STARTPTS" "${ENC[@]}" "$DST/${name}.$EXT"
    echo "✓ $name: вступление ${loop} сэмплов + петля  -> $DST/${name}{_intro,}.$EXT"
  else
    "${ff[@]}" "${ENC[@]}" "$DST/${name}.$EXT"
    echo "✓ $name: петля целиком -> $DST/${name}.$EXT"
  fi
done
ls -la "$DST"
