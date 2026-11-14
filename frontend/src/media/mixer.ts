/**
 * 브라우저 미디어 캡처 · 믹싱 모듈 (§6.5). 송출할 MediaStream을 만든다.
 *
 * - PC 화면 캡처: `createPcStream`이 `getDisplayMedia({ video, audio: true })`로 화면(탭 · 창 · 전체 화면) 영상과 탭 · 시스템 소리를 받는다.
 * - 탭/시스템 소리 + 마이크 믹싱: `getUserMedia({ audio })`로 연 마이크와 위 소리를 `AudioContext`의 GainNode로 각각 조절해
 *   `MediaStreamAudioDestinationNode` 하나로 합치고, 화면 영상 트랙과 묶어 한 스트림으로 내보낸다.
 * - 모바일 카메라 송출: `createMobileStream`이 `getUserMedia({ video: facingMode, audio: true })`로 휴대폰 카메라 영상과 마이크 음성을 함께 받는다(전 · 후면 전환 지원).
 *
 * 만든 스트림은 `media/whip.ts`의 WhipClient(WebRTC/WHIP)가 MediaMTX로 송출한다.
 */
export type MixerWarning = 'NO_SYSTEM_AUDIO' | 'MIC_UNAVAILABLE'

export type PcStream = {
  stream: MediaStream
  warnings: MixerWarning[]
  hasSystemAudio: boolean
  hasMic: boolean
  setMicGain: (value: number) => void
  setSystemGain: (value: number) => void
  /** 마이크를 바꾼다(null이면 끔). 새 마이크를 열지 못하면 false. */
  setMic: (deviceId: string | null) => Promise<boolean>
  /** 0~1 음량(미터 표시용) */
  getLevels: () => { mic: number; system: number }
  stop: () => void
}

export type PcStreamOptions = {
  /** 마이크 deviceId. ''이면 기본 마이크, null이면 마이크 없음 */
  mic: string | null
  /** 사용자가 브라우저의 "공유 중지"를 눌렀을 때 */
  onEnded?: () => void
}

export const micConstraints = (deviceId: string): MediaTrackConstraints => ({
  ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
  echoCancellation: true,
  noiseSuppression: true,
})

function level(analyser: AnalyserNode, buffer: Uint8Array<ArrayBuffer>): number {
  analyser.getByteTimeDomainData(buffer)
  let peak = 0
  for (const sample of buffer) peak = Math.max(peak, Math.abs(sample - 128))
  return peak / 128
}

export async function createPcStream({ mic, onEnded }: PcStreamOptions): Promise<PcStream> {
  const display = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true })
  const warnings: MixerWarning[] = []
  const systemTrack = display.getAudioTracks()[0] ?? null
  if (!systemTrack) warnings.push('NO_SYSTEM_AUDIO')

  const context = new AudioContext()
  const destination = context.createMediaStreamDestination()
  const systemGain = context.createGain()
  const micGain = context.createGain()
  const systemAnalyser = context.createAnalyser()
  const micAnalyser = context.createAnalyser()
  systemGain.connect(destination)
  micGain.connect(destination)
  systemGain.connect(systemAnalyser)
  micGain.connect(micAnalyser)
  if (systemTrack) context.createMediaStreamSource(new MediaStream([systemTrack])).connect(systemGain)

  let micStream: MediaStream | null = null
  let micSource: MediaStreamAudioSourceNode | null = null

  async function setMic(deviceId: string | null): Promise<boolean> {
    micSource?.disconnect()
    micSource = null
    micStream?.getTracks().forEach((track) => track.stop())
    micStream = null
    if (deviceId === null) return true
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: micConstraints(deviceId) })
      micSource = context.createMediaStreamSource(micStream)
      micSource.connect(micGain)
      return true
    } catch {
      return false
    }
  }

  if (mic !== null && !(await setMic(mic))) warnings.push('MIC_UNAVAILABLE')
  // 사용자 제스처에서 시작했어도 getDisplayMedia를 기다리는 동안 suspended일 수 있다.
  void context.resume().catch(() => undefined)

  const videoTrack = display.getVideoTracks()[0]
  let stopped = false
  videoTrack.addEventListener('ended', () => { if (!stopped) onEnded?.() })

  const buffer = new Uint8Array(new ArrayBuffer(256))
  systemAnalyser.fftSize = 256
  micAnalyser.fftSize = 256

  return {
    stream: new MediaStream([videoTrack, ...destination.stream.getAudioTracks()]),
    warnings,
    hasSystemAudio: !!systemTrack,
    get hasMic() { return micStream !== null },
    setMicGain: (value) => { micGain.gain.value = value },
    setSystemGain: (value) => { systemGain.gain.value = value },
    setMic,
    getLevels: () => ({ mic: micStream ? level(micAnalyser, buffer) : 0, system: systemTrack ? level(systemAnalyser, buffer) : 0 }),
    stop: () => {
      if (stopped) return
      stopped = true
      display.getTracks().forEach((track) => track.stop())
      micStream?.getTracks().forEach((track) => track.stop())
      destination.stream.getTracks().forEach((track) => track.stop())
      void context.close().catch(() => undefined)
    },
  }
}

// §6.5 모바일: 카메라 + 마이크. 전/후면 전환은 새 카메라 트랙을 열어 RTCRtpSender.replaceTrack으로 바꾼다.
export type Facing = 'user' | 'environment'

export type MobileStream = {
  stream: MediaStream
  facing: Facing
  /** 반대쪽 카메라로 바꾸고 새 비디오 트랙을 돌려준다(송출 중이면 부르는 쪽이 replaceTrack) */
  switchCamera: () => Promise<MediaStreamTrack>
  setMicEnabled: (enabled: boolean) => void
  stop: () => void
}

const cameraConstraints = (facing: Facing): MediaTrackConstraints => ({ facingMode: facing, width: 1280, height: 720 })

export async function createMobileStream({ facing }: { facing: Facing }): Promise<MobileStream> {
  const stream = await navigator.mediaDevices.getUserMedia({ video: cameraConstraints(facing), audio: true })
  const state = { facing, micEnabled: true, stopped: false }

  return {
    stream,
    get facing() { return state.facing },
    async switchCamera() {
      const next: Facing = state.facing === 'user' ? 'environment' : 'user'
      const old = stream.getVideoTracks()[0]
      // 휴대폰은 카메라 두 개를 동시에 못 여는 경우가 많아(iOS) 이전 트랙을 먼저 멈춘다.
      old?.stop()
      let video: MediaStream
      try {
        video = await navigator.mediaDevices.getUserMedia({ video: cameraConstraints(next) })
      } catch (error) {
        // 반대쪽 카메라가 없거나 거절되면 원래 카메라를 다시 연다.
        video = await navigator.mediaDevices.getUserMedia({ video: cameraConstraints(state.facing) })
        swap(old, video.getVideoTracks()[0])
        throw error
      }
      state.facing = next
      return swap(old, video.getVideoTracks()[0])
    },
    setMicEnabled(enabled) {
      state.micEnabled = enabled
      stream.getAudioTracks().forEach((track) => { track.enabled = enabled })
    },
    stop() {
      if (state.stopped) return
      state.stopped = true
      stream.getTracks().forEach((track) => track.stop())
    },
  }

  function swap(old: MediaStreamTrack | undefined, track: MediaStreamTrack): MediaStreamTrack {
    if (old) stream.removeTrack(old)
    stream.addTrack(track)
    return track
  }
}
