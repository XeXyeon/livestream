# 기본 bluenviron/mediamtx 이미지는 셸이 없어 runOn* 훅(curl)을 못 돌린다.
# alpine 기반 -ffmpeg 이미지에 curl 을 더한다 (ffmpeg 는 쓰지 않는다).
FROM bluenviron/mediamtx:1.21.3-ffmpeg
RUN apk add --no-cache curl
