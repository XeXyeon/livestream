import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { MonitorUp } from 'lucide-react'
import type { CategoryCode } from '../api/types'
import { createPcStream } from '../media/mixer'
import type { MixerWarning, PcStream } from '../media/mixer'
import BroadcastInfoForm from '../components/studio/BroadcastInfoForm'
import SessionStatus from '../components/studio/SessionStatus'
import { useBroadcastSession } from '../components/studio/useBroadcastSession'
import type { BroadcastInfo } from '../components/studio/useBroadcastSession'
import Spinner from '../components/common/Spinner'

const WARNING_TEXT: Record<MixerWarning, string> = {
  NO_SYSTEM_AUDIO: '탭/시스템 소리가 포함되지 않았습니다. 탭을 공유할 때 "탭 오디오도 공유"를 켜 주세요.',
  MIC_UNAVAILABLE: '마이크를 열지 못했습니다. 브라우저의 마이크 권한을 확인해 주세요.',
}

const supportsDisplayMedia = () => typeof navigator.mediaDevices?.getDisplayMedia === 'function'

function useAudioInputs(refreshKey: unknown) {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  useEffect(() => {
    if (!navigator.mediaDevices?.enumerateDevices) return
    const load = () => void navigator.mediaDevices.enumerateDevices()
      .then((all) => setDevices(all.filter((device) => device.kind === 'audioinput')))
      .catch(() => undefined)
    load()
    navigator.mediaDevices.addEventListener('devicechange', load)
    return () => navigator.mediaDevices.removeEventListener('devicechange', load)
  }, [refreshKey])
  return devices
}

function LevelMeter({ value, label }: { value: number; label: string }) {
  return (
    <div aria-label={`${label} 음량`} className="h-1.5 w-full overflow-hidden rounded bg-slate-800">
      <div className="h-full bg-emerald-400 transition-[width] duration-100" style={{ width: `${Math.min(100, Math.round(value * 100))}%` }} />
    </div>
  )
}

// S3 PC 송출 스튜디오(Chrome 기준). 채팅 · 후원 알림은 FE-5 · FE-6에서 오른쪽 칸에 붙인다.
export default function StudioPc() {
  const [media, setMedia] = useState<PcStream | null>(null)
  const mediaRef = useRef<PcStream | null>(null)
  const previewRef = useRef<HTMLVideoElement>(null)
  const [mic, setMic] = useState<string | null>('')
  const [includeSystem, setIncludeSystem] = useState(true)
  const [micGain, setMicGain] = useState(1)
  const [systemGain, setSystemGain] = useState(1)
  const [levels, setLevels] = useState({ mic: 0, system: 0 })
  const [shareError, setShareError] = useState('')
  const [sharing, setSharing] = useState(false)
  const [info, setInfo] = useState<BroadcastInfo | null>(null)
  const devices = useAudioInputs(media)

  const stopMedia = useCallback(() => {
    mediaRef.current?.stop()
    mediaRef.current = null
    setMedia(null)
  }, [])

  const session = useBroadcastSession('pc', { onServerEnded: stopMedia })
  const { end, active } = session
  const activeRef = useRef(active)
  useEffect(() => { activeRef.current = active }, [active])

  // 페이지를 떠나면 캡처를 멈춘다(송출은 세션 훅이 끊는다).
  const mountedRef = useRef(false)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      mediaRef.current?.stop()
    }
  }, [])

  useEffect(() => {
    if (previewRef.current) previewRef.current.srcObject = media?.stream ?? null
  }, [media])

  useEffect(() => {
    if (!media) return
    const timer = setInterval(() => setLevels(media.getLevels()), 120)
    return () => clearInterval(timer)
  }, [media])

  useEffect(() => { media?.setMicGain(micGain) }, [media, micGain])
  useEffect(() => { media?.setSystemGain(includeSystem ? systemGain : 0) }, [media, includeSystem, systemGain])

  useEffect(() => {
    if (!active) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [active])

  async function share() {
    setShareError('')
    setSharing(true)
    try {
      const next = await createPcStream({
        mic,
        // 사용자가 브라우저의 "공유 중지"를 누르면: 송출 중이면 종료 순서대로 끝내고, 아니면 미리보기만 지운다.
        onEnded: () => {
          if (activeRef.current) void end(stopMedia)
          else stopMedia()
        },
      })
      // 공유 · 마이크 권한을 고르는 중에 화면을 떠났으면 열린 캡처를 바로 끈다.
      if (!mountedRef.current) { next.stop(); return }
      mediaRef.current?.stop()
      mediaRef.current = next
      setMedia(next)
    } catch (cause) {
      // 선택 창 취소와 브라우저 차단(앱 내장 브라우저 등)은 둘 다 NotAllowedError라 구분하지 않고 함께 안내한다.
      setShareError(cause instanceof DOMException && cause.name === 'NotAllowedError'
        ? '화면 공유가 취소되었거나 이 브라우저에서 허용되지 않았습니다. Chrome에서 다시 시도해 주세요.'
        : '화면 공유를 시작하지 못했습니다.')
    } finally {
      setSharing(false)
    }
  }

  async function changeMic(value: string) {
    const next = value === 'none' ? null : value
    setMic(next)
    if (media && !(await media.setMic(next))) setShareError(WARNING_TEXT.MIC_UNAVAILABLE)
  }

  if (!supportsDisplayMedia()) {
    return (
      <section className="mx-auto max-w-lg rounded-2xl border border-slate-800 bg-slate-900 p-8 text-center">
        <h1 className="text-xl font-bold">이 브라우저는 화면 공유를 지원하지 않습니다</h1>
        <p className="mt-2 text-slate-400">PC Chrome에서 열거나, 카메라로 방송하는 모바일 화면을 쓰세요.</p>
        <Link to="/studio/mobile" className="mt-6 inline-block rounded-lg bg-indigo-500 px-4 py-2.5 font-semibold text-white hover:bg-indigo-400">모바일 화면으로</Link>
      </section>
    )
  }
  if (session.channel.isPending) return <Spinner label="채널 정보를 불러오는 중" />
  if (session.channel.isError) return <p role="alert" className="py-20 text-center text-rose-300">채널 정보를 불러오지 못했습니다.</p>

  const channel = session.channel.data
  const currentInfo = info ?? { title: channel.title, category: channel.category.code as CategoryCode }
  const busy = session.phase === 'starting' || session.phase === 'ending'
  const warnings = media?.warnings.filter((warning) => warning !== 'MIC_UNAVAILABLE' || mic !== null) ?? []

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
      <div className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-bold">PC 스튜디오</h1>
          <div className="flex gap-3 text-sm">
            <Link to="/studio/mobile" className="text-slate-300 hover:text-white">모바일 화면으로</Link>
            <Link to="/studio/settings" className="text-slate-300 hover:text-white">채널 설정</Link>
          </div>
        </div>

        <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
          <video ref={previewRef} muted autoPlay playsInline className="h-full w-full object-contain" />
          {!media && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-slate-400">
              <MonitorUp aria-hidden className="h-10 w-10" />
              <p>화면 공유를 시작하면 미리보기가 나옵니다.</p>
            </div>
          )}
        </div>

        <section aria-label="소스" className="space-y-4 rounded-xl border border-slate-800 bg-slate-900 p-4">
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" onClick={() => void share()} disabled={sharing || active} className="rounded-lg bg-slate-700 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-600 disabled:opacity-50">
              {media ? '공유 화면 다시 선택' : '화면 공유 시작'}
            </button>
            {active && <span className="text-xs text-slate-500">송출 중에는 공유 화면을 바꿀 수 없습니다. 종료 후 다시 선택하세요.</span>}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <label className="block text-sm font-medium">마이크
                <select value={mic ?? 'none'} onChange={(event) => void changeMic(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-white">
                  <option value="">기본 마이크</option>
                  {devices.filter((device) => device.deviceId && device.deviceId !== 'default').map((device, index) => (
                    <option key={device.deviceId} value={device.deviceId}>{device.label || `마이크 ${index + 1}`}</option>
                  ))}
                  <option value="none">마이크 사용 안 함</option>
                </select>
              </label>
              <label className="block text-xs text-slate-400">마이크 볼륨 {Math.round(micGain * 100)}%
                <input type="range" min={0} max={2} step={0.05} value={micGain} disabled={mic === null} onChange={(event) => setMicGain(Number(event.target.value))} className="w-full" />
              </label>
              <LevelMeter value={levels.mic} label="마이크" />
            </div>
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-sm font-medium">
                <input type="checkbox" checked={includeSystem} onChange={(event) => setIncludeSystem(event.target.checked)} />
                탭/시스템 소리 포함
              </label>
              <label className="block text-xs text-slate-400">시스템 소리 볼륨 {Math.round(systemGain * 100)}%
                <input type="range" min={0} max={2} step={0.05} value={systemGain} disabled={!includeSystem} onChange={(event) => setSystemGain(Number(event.target.value))} className="w-full" />
              </label>
              <LevelMeter value={includeSystem ? levels.system : 0} label="시스템 소리" />
            </div>
          </div>
          {warnings.map((warning) => <p key={warning} role="alert" className="text-sm text-amber-300">{WARNING_TEXT[warning]}</p>)}
          {shareError && <p role="alert" className="text-sm text-rose-300">{shareError}</p>}
        </section>
      </div>

      <div className="space-y-4">
        <section className="space-y-4 rounded-xl border border-slate-800 bg-slate-900 p-4">
          <SessionStatus phase={session.phase} viewerCount={session.viewerCount} />
          <BroadcastInfoForm initial={currentInfo} onChange={setInfo} onSave={session.saveInfo} />
          {active || session.phase === 'ending'
            ? (
              <button type="button" disabled={session.phase === 'ending'} onClick={() => void end(stopMedia)} className="w-full rounded-lg bg-rose-600 px-4 py-3 font-semibold text-white hover:bg-rose-500 disabled:opacity-50">
                {session.phase === 'ending' ? '종료하는 중…' : '방송 종료'}
              </button>
            )
            : (
              <button type="button" disabled={!media || busy || !currentInfo.title.trim()} onClick={() => media && void session.start(media.stream, { ...currentInfo, title: currentInfo.title.trim() })} className="w-full rounded-lg bg-indigo-500 px-4 py-3 font-semibold text-white hover:bg-indigo-400 disabled:opacity-50">
                송출 시작
              </button>
            )}
          {!media && !active && <p className="text-xs text-slate-500">먼저 화면 공유를 시작하세요.</p>}
          {session.phase === 'reconnecting' && <p role="status" className="text-sm text-amber-300">연결이 끊겨 다시 연결하는 중입니다. 60초 안에 연결되면 방송이 이어집니다.</p>}
          {session.phase === 'ended' && !session.error && <p role="status" className="text-sm text-slate-300">방송을 종료했습니다.</p>}
          {session.error && <p role="alert" className="text-sm text-rose-300">{session.error}</p>}
          {session.broadcastId !== null && (
            <Link to={`/live/${channel.id}`} target="_blank" className="block text-sm text-indigo-300 hover:text-indigo-200">시청 화면 새 탭에서 열기</Link>
          )}
        </section>
        {/* FE-5 ChatPanel · FE-6 후원 알림 자리 */}
        <aside aria-label="채팅" className="min-h-48 rounded-xl border border-slate-800 bg-slate-900 p-4 text-sm text-slate-500">채팅은 곧 열립니다.</aside>
      </div>
    </div>
  )
}
