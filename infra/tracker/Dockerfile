# WebTorrent 트래커 (p2p-media-loader 피어 소개용). DESIGN §2.1 · §7
# - npm 의 wt-tracker@0.0.1 은 TypeScript 소스만 들어 있어 실행되지 않으므로 GitHub 소스를 커밋 고정으로 받는다.
# - uWebSockets.js(20.7x)는 glibc 2.38 이상이 필요해 alpine · bookworm 이 아닌 trixie 이미지를 쓴다.
# - uWebSockets.js v20.71.0 의 ESM_wrapper.mjs 가 없는 index.js 를 import 하는 패키징 오류가 있어 uws.js 를 복사해 우회한다.
FROM node:22-trixie-slim
ARG WT_TRACKER_COMMIT=33127a560ccc95be6ed8eeeb270e65909a6e3a49
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && git clone https://github.com/Novage/wt-tracker.git /opt/wt-tracker \
    && cd /opt/wt-tracker \
    && git checkout "$WT_TRACKER_COMMIT" \
    && npm install --omit=dev \
    && cp node_modules/uWebSockets.js/uws.js node_modules/uWebSockets.js/index.js \
    && apt-get purge -y git && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /opt/wt-tracker/.git
WORKDIR /opt/wt-tracker
COPY config.json /etc/wt-tracker.json
USER node
EXPOSE 8000
CMD ["node", "src/run-tracker.ts", "/etc/wt-tracker.json"]
