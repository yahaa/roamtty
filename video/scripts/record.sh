#!/bin/bash
# Records the Roamtty FGS demo on emulator-5554 (portrait 1080x2400).
set -u
# Prerequisites: review build installed, the "Play review demo" host saved with its password, no sessions in the list.
cd "$(dirname "$0")/.."
e(){ "${ADB:-adb}" -s "${SERIAL:-emulator-5554}" "$@"; }
e=e
OUT=raw; mkdir -p $OUT
tap(){ $e shell input tap "$1" "$2"; }
typ(){ for w in "$@"; do $e shell input text "$w"; sleep 0.25; done; }
fgs(){ echo "== $1 $(date +%T)" >> $OUT/fgs.log; $e shell dumpsys activity services com.roamtty.app | grep -E "ServiceRecord|isForeground|foregroundId|types=" >> $OUT/fgs.log; $e shell dumpsys notification --noredact | grep -A2 "pkg=com.roamtty.app" | head -6 >> $OUT/fgs.log; }
: > $OUT/fgs.log
# clean status bar
$e shell settings put global sysui_demo_allowed 1
b(){ $e shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
b enter; b clock -e hhmm 0941; b battery -e level 100 -e plugged false; b network -e wifi show -e level 4 -e fully true; b network -e mobile show -e datatype none -e level 4 -e fully true
$e shell am start -n com.roamtty.app/io.itermux.app.MainActivity >/dev/null; sleep 2
fgs before
$e shell rm -f /sdcard/rt.mp4
$e shell screenrecord --bit-rate 16000000 --size 1080x2400 /sdcard/rt.mp4 & REC=$!
sleep 2.5
date +%s.%N > $OUT/t0
mark(){ echo "$(python3 -c 'import time;print(time.time())') $1" >> $OUT/marks.txt; }
: > $OUT/marks.txt; mark start
tap 1006 346; mark newsession; sleep 2
tap 500 1725; mark claude; sleep 2
tap 500 2070; mark host; sleep 3
tap 500 734; mark folder; sleep 2
tap 575 2242; mark startagent; sleep 10
mark type1
typ "Read%scart.py" "%sand" "%sexplain" "%sit" "%sbriefly"; sleep 0.8
$e shell input keyevent KEYCODE_ENTER; mark send1; sleep 18
mark answer1; fgs connected
$e shell input keyevent KEYCODE_HOME; mark home; sleep 1.5
$e shell am start -a android.settings.SETTINGS >/dev/null; mark otherapp; sleep 2.5
$e shell input swipe 540 5 540 1500 400; mark shade1; sleep 4
tap 540 880; mark returned; sleep 2.5
tap 540 1500; sleep 1.2; mark type2
typ "Suggest" "%sone" "%stest" "%scase"; sleep 0.8
$e shell input keyevent KEYCODE_ENTER; mark send2; sleep 15
mark answer2
$e shell input swipe 540 5 540 1500 400; mark shade2; sleep 3
tap 293 1096; mark disconnect; sleep 3.5
fgs after; mark empty
$e shell cmd statusbar collapse; sleep 2.5; mark final
$e shell pkill -INT screenrecord; sleep 3; wait $REC
$e pull /sdcard/rt.mp4 $OUT/rt.mp4
b exit
