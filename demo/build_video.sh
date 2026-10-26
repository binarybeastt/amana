#!/usr/bin/env bash
# Assembles demo/out/demo.mp4 from demo/raw (screen recordings), demo/cards (PNG cards) and
# demo/vo (one narration clip per scene). Each scene lasts its narration + PAD seconds; device
# footage is trimmed and sped up to fit.
set -euo pipefail
cd "$(dirname "$0")"
FONT="$PWD/../android/app/src/main/res/font/inter.ttf"
OUT=out; mkdir -p $OUT/seg; rm -f $OUT/seg/*.mp4
PAD=1.2
HOLD=2.5   # device scenes end on their last frame for this long
BG=0x0b0b0c

vo_len() { ffprobe -v error -show_entries format=duration -of csv=p=0 "vo/$1.aiff"; }
len() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }
calc() { python3 -c "print($1)"; }

# Audio: the scene's narration, then silence to the scene's length.
audio_in() { echo "-i vo/$1.aiff"; }
AFILTER="aresample=48000,aformat=channel_layouts=mono,apad"

# pv <clip>: video prefilter; on the real phone's clips, blur the status bar's left side
pv() { case "$1" in *phone*) echo "split[pa][pb];[pb]crop=iw*0.6:ih*0.04:0:0,boxblur=14[pbl];[pa][pbl]overlay=0:0,";; *) echo "";; esac; }

# caption text -> drawtext filter (bottom centre)
cap() {
  [ -n "$1" ] || { echo "null"; return; }
  echo "drawtext=fontfile=$FONT:text='$1':fontcolor=white:fontsize=46:x=(w-text_w)/2:y=h-95:box=1:boxcolor=0x000000@0.55:boxborderw=22"
}

# card <name> <vo>: still card for the narration's length
card() {
  local d; d=$(calc "$(vo_len "$2") + $PAD")
  ffmpeg -v error -y -loop 1 -t "$d" -i "cards/$1.png" $(audio_in "$2") \
    -filter_complex "[0:v]scale=1920:1080,format=yuv420p,fps=30[v];[1:a]$AFILTER[a]" \
    -map "[v]" -map "[a]" -t "$d" -c:v libx264 -preset veryfast -crf 20 -c:a aac "$OUT/seg/$3.mp4"
}

# quiet_card <name> <seconds> <seg>: still card with silence
quiet_card() {
  ffmpeg -v error -y -loop 1 -t "$2" -i "cards/$1.png" -f lavfi -t "$2" -i anullsrc=r=48000:cl=mono \
    -filter_complex "[0:v]scale=1920:1080,format=yuv420p,fps=30[v]" \
    -map "[v]" -map 1:a -t "$2" -c:v libx264 -preset veryfast -crf 20 -c:a aac "$OUT/seg/$3.mp4"
}

# one <clip> <start> <end> <vo> <seg> <label> <caption>: one device, centred, window sped to fit
one() {
  local clip=$1 s=$2 e=$3 d; d=$(calc "$(vo_len "$4") + $PAD")
  local f; f=$(calc "($e - $s) / ($d - $HOLD)")
  ffmpeg -v error -y -ss "$s" -to "$e" -i "raw/$clip.mp4" $(audio_in "$4") -filter_complex "
    [0:v]$(pv "$clip")setpts=PTS/$f,scale=-2:860,fps=30,tpad=stop_mode=clone:stop_duration=$HOLD[d];
    color=c=$BG:s=1920x1080:d=$d[bg];
    [bg][d]overlay=(W-w)/2:80:shortest=1,
    drawtext=fontfile=$FONT:text='$6':fontcolor=0xa9a9ad:fontsize=34:x=(w-text_w)/2:y=38,
    $(cap "$7"),format=yuv420p[v];
    [1:a]$AFILTER[a]" -map "[v]" -map "[a]" -t "$d" -c:v libx264 -preset veryfast -crf 20 -c:a aac "$OUT/seg/$5.mp4"
}

# two <clipL> <sL> <eL> <labelL> <clipR> <sR> <eR> <labelR> <vo> <seg> <caption>: side by side
two() {
  local d; d=$(calc "$(vo_len "$9") + $PAD")
  local fl fr; fl=$(calc "($3 - $2) / ($d - $HOLD)"); fr=$(calc "($7 - $6) / ($d - $HOLD)")
  ffmpeg -v error -y -ss "$2" -to "$3" -i "raw/$1.mp4" -ss "$6" -to "$7" -i "raw/$5.mp4" $(audio_in "$9") -filter_complex "
    [0:v]$(pv "$1")setpts=PTS/$fl,scale=-2:860,fps=30,tpad=stop_mode=clone:stop_duration=$HOLD[l];
    [1:v]$(pv "$5")setpts=PTS/$fr,scale=-2:860,fps=30,tpad=stop_mode=clone:stop_duration=$HOLD[r];
    color=c=$BG:s=1920x1080:d=$d[bg];
    [bg][l]overlay=W/2-w-60:80:shortest=1[t];
    [t][r]overlay=W/2+60:80:shortest=1,
    drawtext=fontfile=$FONT:text='$4':fontcolor=0xa9a9ad:fontsize=34:x=W/2-60-text_w-150:y=38,
    drawtext=fontfile=$FONT:text='$8':fontcolor=0xa9a9ad:fontsize=34:x=W/2+60+150:y=38,
    $(cap "${11}"),format=yuv420p[v];
    [2:a]$AFILTER[a]" -map "[v]" -map "[a]" -t "$d" -c:v libx264 -preset veryfast -crf 20 -c:a aac "$OUT/seg/${10}.mp4"
}

card title 01-title 01
card hook 02-hook 02
card cast 03-cast 03
# Prepare: the keypad and the tap, then the "ready" screen (joined first)
ffmpeg -v error -y -i raw/s2a-prepare.mp4 -i raw/s2b-ready.mp4 -filter_complex "[0:v][1:v]concat=n=2:v=1[v]" -map "[v]" -c:v libx264 -preset veryfast -crf 18 raw/s2-joined.mp4
one s2-joined 0 "$(len raw/s2-joined.mp4)" 04-prepare 04 "Tunde · online" "Offline cash ready about a minute after it is mined"
two s3-tunde 0 "$(len raw/s3-tunde.mp4)" "Tunde · offline" s3-phone 0 "$(len raw/s3-phone.mp4)" "Ada · airplane mode, real phone" 05-pay 05 "No internet on either phone"
one s4-relay 0 "$(len raw/s4-relay.mp4)" 06-relay 06 "A stranger’s phone · gets signal" "Bluetooth relay · it carries the payment, it cannot read it"
S5=$(len raw/s5-phone.mp4)
one s5-phone "$(calc "$S5 - 40")" "$S5" 07-settle 07 "Ada · back online" "Settled at block 4,544,461"
quiet_card onchain 5 07b
one s6-still 0 "$(len raw/s6-still.mp4)" 08-cheat 08 "A third phone, offline" "Double spend flagged 0.5 s after the second payment"
card limits 09-limits 09
card close 10-close 10

ls $OUT/seg/*.mp4 | sort | sed "s|^$OUT/seg/|file 'seg/|; s|$|'|" > $OUT/list.txt
ffmpeg -v error -y -f concat -safe 0 -i $OUT/list.txt -c copy $OUT/demo.mp4
echo "$OUT/demo.mp4 $(len $OUT/demo.mp4) s"
