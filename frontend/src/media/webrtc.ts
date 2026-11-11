// WHIP · WHEP 공용 도우미 (§6.5). 같은 서버(MediaMTX)에 직접 붙으므로 TURN 없이 host 후보만 쓴다.

/** ICE 후보 수집이 끝날 때까지(최대 timeoutMs) 기다린다. 늦으면 그때까지 모인 후보로 진행한다. */
export function waitForIceGathering(pc: RTCPeerConnection, timeoutMs = 2000): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', onChange)
      resolve()
    }
    const onChange = () => { if (pc.iceGatheringState === 'complete') done() }
    const timer = setTimeout(done, timeoutMs)
    pc.addEventListener('icegatheringstatechange', onChange)
  })
}

/** 201 응답의 Location(상대 경로일 수 있음)을 요청 URL 기준 절대 URL로 바꾼다. */
export function resolveLocation(location: string | null, requestUrl: string): string | null {
  if (!location) return null
  const base = new URL(requestUrl, globalThis.location?.href ?? 'http://localhost/')
  return new URL(location, base).toString()
}

/** 성공한 POST 뒤 시도가 무효가 됐을 때(close · 다시 붙기) 서버에 만들어진 세션을 지운다. 지우지 않으면 타임아웃까지 남는다. */
export function discardSession(response: Response, requestUrl: string, headers?: HeadersInit): void {
  const url = resolveLocation(response.headers.get('Location'), requestUrl)
  if (url) void fetch(url, { method: 'DELETE', headers }).catch(() => undefined)
}
