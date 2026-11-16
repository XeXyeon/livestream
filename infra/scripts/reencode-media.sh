#!/bin/sh
# 다시보기 · 클립 영상 재인코딩 백필 (FE-3#1 · CLIP-1#3, DESIGN §4.10 · §4.18)
#
# 재인코딩을 넣기 전에 `-c copy` 로 만든 다시보기 · 클립 가운데 브라우저가 디코딩하지 못하는 것
# (MediaMTX 기본값 avcC, 녹화 중 해상도 변경)을 백엔드와 같은 규칙(VideoTranscode)으로 다시 인코딩해 제자리에서 바꾼다.
#
# 실행은 백엔드 컨테이너 안에서 한다(ffmpeg 가 있고 /recordings 가 붙어 있다). 기본은 미리보기(아무것도 바꾸지 않음):
#   docker compose -f infra/compose.yaml exec -T backend sh -s < infra/scripts/reencode-media.sh
#   docker compose -f infra/compose.yaml exec -T backend sh -s -- --apply < infra/scripts/reencode-media.sh
#   docker compose -f infra/compose.yaml exec -T backend sh -s -- --apply vod/clips/x.mp4 < infra/scripts/reencode-media.sh   # 파일 하나만
#
# - 대상: /recordings 의 vod/*.mp4 · private/*.mp4 · vod/clips/*.mp4 · private/clips/*.mp4 (초안 private/clip-drafts/ 는 10분 뒤 지워지므로 제외)
# - 이미 재인코딩한 파일(comment=gridlive-h264-v1)과 영상이 없는 파일(소리만)은 건너뛴다. 여러 번 돌려도 된다.
# - 같은 폴더의 임시 파일에 쓰고, 결과에 영상이 있는지 확인한 뒤 mv 로 바꾼다(재생 중인 시청자는 이전 파일을 끝까지 읽는다).
#   결과 길이가 원본의 90% 미만이면(중간에 끊김) 버린다. 인코딩하는 사이 원본이 옮겨지거나(공개 설정 변경 vod/ ↔ private/)
#   지워졌으면 결과를 버리고, mv 직후에도 반대 폴더에 같은 이름이 생겼으면 방금 쓴 쪽을 지운다(비공개로 바꾼 영상이 vod/ 에
#   되살아나지 않게). 중단(Ctrl-C · 연결 끊김)되면 임시 파일을 지운다.
# - 한 번에 하나씩, nice 로 낮은 우선순위, 2스레드. 운영 VM(2 vCPU)에서는 1080p 1시간짜리가 1시간 넘게 걸릴 수 있다 —
#   방송이 없는 시간에 돌린다.
# - DB 는 건드리지 않는다. recording.size_bytes · clip.size_bytes 는 옛 크기로 남는다(화면 표시용, 재생에는 쓰지 않음).
#   영상이 없는 클립(CLIP-1#3: 빈 mp4 인데 READY)은 바꾸지 않고 "EMPTY" 로 알려 주기만 한다 — DB 에서 직접 처리한다.
set -eu

ROOT=${RECORDINGS_DIR:-/recordings}
TAG=gridlive-h264-v1
MAX_LONG_SIDE=1920
APPLY=0
if [ "${1:-}" = "--apply" ]; then
	APPLY=1
	shift
fi

if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
	echo "ffmpeg · ffprobe 가 없습니다(백엔드 컨테이너 안에서 실행하세요)" >&2
	exit 1
fi

# 영상 정보: "폭 높이 패킷수 길이" (영상 없으면 0 0 0 길이)
probe() {
	ffprobe -v error -select_streams v:0 -count_packets \
		-show_entries stream=width,height,nb_read_packets:format=duration \
		-of default=noprint_wrappers=1 "$1" 2>/dev/null | awk -F= '
		$1=="width"{w=$2} $1=="height"{h=$2} $1=="nb_read_packets"{p=$2} $1=="duration"{d=$2}
		END{ if (w !~ /^[0-9]+$/) w=0; if (h !~ /^[0-9]+$/) h=0; if (p !~ /^[0-9]+$/) p=0; if (d == "" || d == "N/A") d=0;
		     print w, h, p, d }'
}

tagged() {
	ffprobe -v error -show_entries format_tags=comment -of default=noprint_wrappers=1:nokey=1 "$1" 2>/dev/null | grep -qx "$TAG"
}

# VideoTranscode.targetSize: 긴 변 1920 이하, 짝수, 키우지 않음
target_size() {
	awk -v w="$1" -v h="$2" -v m="$MAX_LONG_SIDE" 'BEGIN{
		l = (w > h) ? w : h; s = (l > m) ? m / l : 1;
		tw = int(w * s); th = int(h * s); tw -= tw % 2; th -= th % 2;
		if (tw < 2) tw = 2; if (th < 2) th = 2; print tw, th }'
}

# 지정한 파일은 미리 검사한다(아래 목록은 $(...) 안에서 돌아 exit 가 스크립트를 멈추지 못한다)
for rel in "$@"; do
	case "$rel" in
		*..*|*clip-drafts/*) echo "대상 폴더가 아닙니다: $rel" >&2; exit 1 ;;
		vod/*.mp4|private/*.mp4) ;;
		*) echo "대상 폴더가 아닙니다: $rel" >&2; exit 1 ;;
	esac
done

list_files() {
	if [ "$#" -gt 0 ]; then
		for rel in "$@"; do
			echo "$ROOT/$rel"
		done
		return
	fi
	for dir in vod private vod/clips private/clips; do
		[ -d "$ROOT/$dir" ] || continue
		find "$ROOT/$dir" -maxdepth 1 -type f -name '*.mp4' ! -name '.*'
	done
}

# vod/x.mp4 ↔ private/x.mp4, vod/clips/x.mp4 ↔ private/clips/x.mp4
twin_of() {
	case "$1" in
		vod/*) echo "private/${1#vod/}" ;;
		private/*) echo "vod/${1#private/}" ;;
	esac
}

tmp=""
trap 'if [ -n "$tmp" ]; then rm -f "$tmp"; fi' EXIT
trap 'exit 130' INT TERM HUP

total=0; todo=0; done_n=0; skipped=0; empty=0; failed=0
for f in $(list_files "$@"); do
	total=$((total + 1))
	rel=${f#"$ROOT"/}
	if [ ! -f "$f" ]; then
		echo "없음      $rel"; failed=$((failed + 1)); continue
	fi
	if tagged "$f"; then
		skipped=$((skipped + 1)); continue
	fi
	set -- $(probe "$f")
	w=$1; h=$2; packets=$3; dur=$4
	if [ "$w" -eq 0 ] || [ "$packets" -eq 0 ]; then
		if [ "$(awk -v d="$dur" 'BEGIN{print (d >= 0.5) ? 1 : 0}')" -eq 1 ]; then
			skipped=$((skipped + 1))   # 소리만 있는 녹화: 다시 인코딩할 영상이 없다
		else
			echo "EMPTY     $rel (영상 · 길이 없음, DB 상태 확인 필요)"; empty=$((empty + 1))
		fi
		continue
	fi
	# 실제로 디코딩한 프레임 크기(앞 · 가운데 영상 패킷 30개씩 중 가장 큰 것, VideoTranscode.sourceSize). 스트림 크기는
	# MediaMTX 기본값 avcC(1920×1080)일 수 있어 믿지 않는다. 디코딩한 프레임이 없으면 스트림 크기.
	mid=$(awk -v d="$dur" 'BEGIN{print (d >= 4) ? int(d / 2) "%+#30" : ""}')
	set -- $( { ffprobe -v error -select_streams v:0 -read_intervals '%+#30' -show_entries frame=width,height -of csv=p=0 "$f" 2>/dev/null
		if [ -n "$mid" ]; then ffprobe -v error -select_streams v:0 -read_intervals "$mid" -show_entries frame=width,height -of csv=p=0 "$f" 2>/dev/null; fi
	} | awk -F, '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && $1 * $2 > bw * bh { bw = $1; bh = $2 } END { print bw + 0, bh + 0 }')
	if [ "$1" -gt 0 ] && [ "$2" -gt 0 ]; then w=$1; h=$2; fi
	set -- $(target_size "$w" "$h")
	tw=$1; th=$2
	todo=$((todo + 1))
	if [ "$APPLY" -eq 0 ]; then
		echo "대상      $rel ${w}x${h} → ${tw}x${th} ${dur}s"
		continue
	fi

	tmp="$(dirname "$f")/.reencode-$$-$(basename "$f")"
	start=$(date +%s)
	if nice -n 10 ffmpeg -hide_banner -loglevel error -nostdin -y -i "$f" \
		-map 0:v:0 -map '0:a?' \
		-vf "scale=w=$tw:h=$th:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$tw:$th:(ow-iw)/2:(oh-ih)/2,setsar=1" \
		-c:v libx264 -preset veryfast -crf 23 -maxrate 6M -bufsize 12M -pix_fmt yuv420p \
		-force_key_frames 'expr:gte(t,n_forced*2)' -threads 2 -c:a copy \
		-metadata "comment=$TAG" -movflags +faststart "$tmp" </dev/null; then
		set -- $(probe "$tmp")
		twin="$ROOT/$(twin_of "$rel")"
		if [ "$1" -eq 0 ] || [ "$3" -eq 0 ]; then
			echo "버림      $rel (결과에 영상 없음)"
		elif [ "$(awk -v a="$4" -v b="$dur" 'BEGIN{print (a >= b * 0.9) ? 1 : 0}')" -ne 1 ]; then
			echo "버림      $rel (결과 길이 ${4}s < 원본 ${dur}s 의 90%)"
		elif [ ! -f "$f" ] || [ -e "$twin" ]; then
			echo "버림      $rel (인코딩하는 사이 원본이 옮겨지거나 지워짐)"
		else
			mv -f "$tmp" "$f"
			tmp=""
			if [ -e "$twin" ]; then
				rm -f "$f"   # mv 직전에 백엔드가 원본을 반대 폴더로 옮겼다: 방금 되살린 쪽을 지운다
				echo "버림      $rel (교체 순간 원본이 옮겨짐, 옮겨진 원본은 그대로)"
				failed=$((failed + 1))
				continue
			fi
			echo "완료      $rel ${w}x${h} → ${tw}x${th} ${dur}s ($(( $(date +%s) - start ))초)"
			done_n=$((done_n + 1))
			continue
		fi
	else
		echo "실패      $rel (ffmpeg)"
	fi
	rm -f "$tmp"
	tmp=""
	failed=$((failed + 1))
done

if [ "$APPLY" -eq 0 ]; then
	echo "미리보기: 전체 $total, 재인코딩 대상 $todo, 건너뜀 $skipped, EMPTY $empty. 실행하려면 --apply"
else
	echo "결과: 전체 $total, 완료 $done_n, 건너뜀 $skipped, EMPTY $empty, 실패 $failed"
fi
