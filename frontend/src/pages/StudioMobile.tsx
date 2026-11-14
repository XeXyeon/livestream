import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Mic, MicOff, SwitchCamera, X } from 'lucide-react'
import type { CategoryCode } from '../api/types'
import { createMobileStream } from '../media/mixer'
import type { Facing, MobileStream } from '../media/mixer'
import BroadcastInfoForm from '../components/studio/BroadcastInfoForm'
import SessionStatus from '../components/studio/SessionStatus'
import { useBroadcastSession } from '../components/studio/useBroadcastSession'
import type { BroadcastInfo } from '../components/studio/useBroadcastSession'

type WakeLockSentinelLike = { release: () => Promise<void> }

// 송출 중 화면 꺼짐 방지(지원하는 브라우저만). 화면이 다시 보이면 다시 요청한다.
function useWakeLock(enabled: boolean) {
  useEffect(() => {
    const wakeLock = (navigator as Navigator & { wakeLock?: { request: (type: 'screen') => Promise<WakeLockSentinelLike> } }).wakeLock
    if (!enabled || !wakeLock) return
    let sentinel: WakeLockSentinelLike | null = null
    let cancelled = false
    const request = () => {
      if (document.visibilityState !== 'visible') return
      void wakeLock.request('screen').then((lock) => {
        if (cancelled) void lock.release()
        else sentinel = lock
      }).catch(() => undefined)
    }
    request()
    document.addEventListener('visibilitychange', request)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', request)
      void sentinel?.release().catch(() => undefined)
    }
  }, [enabled])
}

// S4 모바일 송출: 전체 화면 카메라 미리보기 · 방송 정보 · 전/후면 · 마이크 음소거 · 큰 시작/종료 · 시청자 수
export default function StudioMobile() {
  const videoRef = useRef<HTMLVideoElement>(null)
  const mediaRef = useRef<MobileStream | null>(null)
  const mountedRef = useRef(false)
  const [media, setMedia] = useState<MobileStream | null>(null)
  const [facing, setFacing] = useState<Facing>('user')
  const [micOn, setMicOn] = useState(true)
  const [cameraError, setCameraError] = useState('')
  const [switching, setSwitching] = useState(false)
  const [info, setInfo] = useState<BroadcastInfo | null>(null)
  const [showInfo, setShowInfo] = useState(false)

  const stopMedia = useCallback(() => {
    mediaRef.current?.stop()
    mediaRef.current = null
    setMedia(null)
  }, [])

  const session = useBroadcastSession('mobile', { onServerEnded: stopMedia })
  useWakeLock(session.active)

  const openCamera = useCallback(async (nextFacing: Facing) => {
    setCameraError('')
    try {
      const next = await createMobileStream({ facing: nextFacing })
      // 권한 요청 중에 화면을 떠났으면 열린 카메라 · 마이크를 바로 끈다.
      if (!mountedRef.current) { next.stop(); return }
      mediaRef.current?.stop()
      mediaRef.current = next
      setMedia(next)
      setFacing(next.facing)
      setMicOn(true)
    } catch {
      setCameraError('카메라 · 마이크를 열지 못했습니다. 브라우저 권한을 확인해 주세요.')
    }
  }, [])

  // 들어오면 바로 카메라를 연다. 떠나면 캡처를 멈춘다(송출은 세션 훅이 끊는다).
  useEffect(() => {
    mountedRef.current = true
    void openCamera('user')
    return () => {
      mountedRef.current = false
      mediaRef.current?.stop()
    }
  }, [openCamera])

  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = media?.stream ?? null
  }, [media])

  useEffect(() => {
    if (!session.active) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [session.active])

  async function switchCamera() {
    if (!media) return
    setSwitching(true)
    let failed = false
    try {
      await media.switchCamera()
    } catch {
      failed = true
    }
    // 전환에 실패해도 mixer가 원래 카메라를 다시 열어 stream에 넣는다. 이전 트랙은 이미 멈췄으므로
    // 성공 · 실패 모두 stream의 현재 비디오 트랙을 송출에 다시 붙여야 시청 화면이 멈추지 않는다.
    try {
      const track = media.stream.getVideoTracks()[0]
      if (track && track.readyState === 'live') await session.replaceTrack('video', track)
      if (videoRef.current) {
        videoRef.current.srcObject = null
        videoRef.current.srcObject = media.stream
      }
    } catch {
      failed = true
    } finally {
      if (failed) setCameraError('카메라를 바꾸지 못했습니다.')
      setFacing(media.facing)
      setSwitching(false)
    }
  }

  function toggleMic() {
    media?.setMicEnabled(!micOn)
    setMicOn(!micOn)
  }

  async function endBroadcast() {
    await session.end(stopMedia)
  }

  const channel = session.channel.data
  const currentInfo = info ?? (channel ? { title: channel.title, category: channel.category.code as CategoryCode } : null)
  const busy = session.phase === 'starting' || session.phase === 'ending'
  const round = 'flex h-12 w-12 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur disabled:opacity-40'

  return (
    <div className="fixed inset-0 z-50 overflow-hidden bg-black text-white">
      <video ref={videoRef} muted autoPlay playsInline className={`absolute inset-0 h-full w-full object-cover ${facing === 'user' ? '-scale-x-100' : ''}`} />

      <div className="absolute inset-x-0 top-0 space-y-2 bg-gradient-to-b from-black/80 to-transparent p-4 pt-[max(1rem,env(safe-area-inset-top))]">
        <div className="flex items-center justify-between gap-3">
          <SessionStatus phase={session.phase} viewerCount={session.viewerCount} />
          <div className="flex items-center gap-3 text-sm">
            {!session.active && <Link to="/studio/pc" className="text-slate-200 underline-offset-2 hover:underline">PC 화면으로</Link>}
            {!session.active && <Link to="/" aria-label="스튜디오 나가기" className="text-slate-200"><X aria-hidden className="h-6 w-6" /></Link>}
          </div>
        </div>
        {currentInfo && (
          <button type="button" onClick={() => setShowInfo(!showInfo)} className="block max-w-full truncate text-left text-lg font-bold">
            {currentInfo.title || '방송 제목을 입력하세요'} <span className="text-sm font-normal text-slate-300">· 수정</span>
          </button>
        )}
        {showInfo && currentInfo && (
          <div className="rounded-lg bg-surface/90 p-3">
            <BroadcastInfoForm compact initial={currentInfo} onChange={setInfo} onSave={session.saveInfo} />
          </div>
        )}
        {session.channel.isError && <p role="alert" className="text-sm text-rose-300">채널 정보를 불러오지 못했습니다.</p>}
      </div>

      <div className="absolute inset-x-0 bottom-0 space-y-3 bg-gradient-to-t from-black/80 to-transparent p-4 pb-[max(1.5rem,env(safe-area-inset-bottom))]">
        {cameraError && (
          <div role="alert" className="flex items-center justify-between gap-3 rounded-lg bg-rose-950/90 p-3 text-sm text-rose-100">
            <span>{cameraError}</span>
            {!media && <button type="button" onClick={() => void openCamera(facing)} className="shrink-0 rounded bg-white/15 px-2 py-1">다시 시도</button>}
          </div>
        )}
        {session.phase === 'reconnecting' && <p role="status" className="text-center text-sm text-amber-300">연결이 끊겨 다시 연결하는 중입니다…</p>}
        {session.phase === 'ended' && !session.error && <p role="status" className="text-center text-sm text-slate-200">방송을 종료했습니다.</p>}
        {session.error && <p role="alert" className="text-center text-sm text-rose-300">{session.error}</p>}
        <div className="flex items-center justify-between">
          <button type="button" aria-label={micOn ? '마이크 끄기' : '마이크 켜기'} aria-pressed={!micOn} disabled={!media} onClick={toggleMic} className={round}>
            {micOn ? <Mic aria-hidden className="h-6 w-6" /> : <MicOff aria-hidden className="h-6 w-6 text-rose-400" />}
          </button>
          {session.active || session.phase === 'ending'
            ? (
              <button type="button" disabled={session.phase === 'ending'} onClick={() => void endBroadcast()} className="h-20 w-20 rounded-full border-4 border-white bg-rose-600 text-sm font-bold disabled:opacity-50">
                {session.phase === 'ending' ? '종료 중' : '종료'}
              </button>
            )
            : (
              <button
                type="button"
                disabled={!media || !currentInfo?.title.trim() || busy}
                onClick={() => {
                  if (!media || !currentInfo) return
                  setShowInfo(false)
                  void session.start(media.stream, { ...currentInfo, title: currentInfo.title.trim() })
                }}
                className="h-20 w-20 rounded-full border-4 border-white bg-rose-600 text-sm font-bold disabled:opacity-50"
              >
                시작
              </button>
            )}
          <button type="button" aria-label="전/후면 카메라 전환" disabled={!media || switching} onClick={() => void switchCamera()} className={round}>
            <SwitchCamera aria-hidden className="h-6 w-6" />
          </button>
        </div>
        {!media && session.phase === 'ended' && (
          <button type="button" onClick={() => void openCamera(facing)} className="w-full rounded-lg bg-white/15 py-2 text-sm">카메라 다시 켜기</button>
        )}
      </div>
    </div>
  )
}
