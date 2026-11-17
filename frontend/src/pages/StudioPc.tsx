/**
 * PC 라이브 방송 화면 (`/studio/pc`).
 * PC 화면 캡처(`getDisplayMedia`)로 화면을 실시간 송출하고, 탭 · 시스템 소리와 마이크 음성을 Web Audio로 믹싱해(`createPcStream`, media/mixer.ts)
 * 함께 보낸다. 송출은 `useBroadcastSession`이 WHIP(`WhipClient`, media/whip.ts)로 하며, 종료는 whip.close() → 스트림 정지 → POST /api/broadcasts/{id}/end 순서다.
 */
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { MonitorUp } from 'lucide-react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import type { CategoryCode } from '../api/types'
import { createPcStream } from '../media/mixer'
import type { MixerWarning, PcStream } from '../media/mixer'
import { NO_H264_TEXT, supportsH264Encoding } from '../media/whip'
import BroadcastInfoForm from '../components/studio/BroadcastInfoForm'
import SessionStatus from '../components/studio/SessionStatus'
import { useBroadcastSession } from '../components/studio/useBroadcastSession'
import type { BroadcastInfo } from '../components/studio/useBroadcastSession'
import Spinner from '../components/common/Spinner'
import { inputClassName } from '../components/common/TextField'
import ChatPanel from '../components/chat/ChatPanel'
import { useBroadcastChat } from '../components/chat/useBroadcastChat'
import DonationBanner from '../components/donation/DonationBanner'
import { useDonationBanner } from '../components/donation/useDonationBanner'
import { usePageMeta } from '../components/layout/usePageMeta'

const WARNING_TEXT: Record<MixerWarning | 'NO_H264', string> = {
  NO_SYSTEM_AUDIO: '탭/시스템 소리가 포함되지 않았습니다. 탭을 공유할 때 "탭 오디오도 공유"를 켜 주세요.',
  MIC_UNAVAILABLE: '마이크를 열지 못했습니다. 브라우저의 마이크 권한을 확인해 주세요.',
  // §6.5 FE-2#1: 송출은 막지 않는다
  NO_H264: NO_H264_TEXT,
}

// S2 오른쪽 패널과 같은 규칙: lg 이상은 헤더 아래에 붙은 화면 높이, 미만은 고정 높이. 방송 전 빈 카드와 ChatPanel이 같은 틀을 써서 열이 뛰지 않는다.
const CHAT_COLUMN_CLASS = 'h-[28rem] lg:sticky lg:top-14 lg:h-[calc(100dvh-3.5rem)]'

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
    <div aria-label={`${label} 음량`} className="h-1.5 w-full overflow-hidden rounded-sm bg-muted">
      <div className="h-full bg-success transition-[width] duration-100" style={{ width: `${Math.min(100, Math.round(value * 100))}%` }} />
    </div>
  )
}

// 소리 한 열: 제목 → 같은 높이의 select → 볼륨 슬라이더 → 레벨 막대. 마이크 · 시스템 소리 두 열이 같은 구조라 높이가 맞는다.
type AudioColumnProps = {
  title: string
  select: { value: string; onChange: (value: string) => void; options: ReactNode }
  gain: { value: number; onChange: (value: number) => void; disabled: boolean }
  gainLabel: string
  level: number
}

function AudioColumn({ title, select, gain, gainLabel, level }: AudioColumnProps) {
  const selectId = useId()
  return (
    <div className="space-y-2">
      <label htmlFor={selectId} className="block text-sm font-medium text-primary">{title}</label>
      <select id={selectId} value={select.value} onChange={(event) => select.onChange(event.target.value)} className={inputClassName.replace('py-2.5', 'py-2')}>
        {select.options}
      </select>
      <label className="block text-xs text-secondary">{gainLabel} 볼륨 {Math.round(gain.value * 100)}%
        <input type="range" min={0} max={2} step={0.05} value={gain.value} disabled={gain.disabled} onChange={(event) => gain.onChange(Number(event.target.value))} className="w-full accent-accent-bg" />
      </label>
      <LevelMeter value={level} label={gainLabel} />
    </div>
  )
}

// S3 PC 송출 스튜디오(Chrome 기준). 왼쪽은 송출 준비 순서(미리보기 → 소스 → 방송), 오른쪽은 채팅만(FE-5). 후원 알림(FE-6)은 미리보기 위 오버레이(§6.7 "S3 배치 정리").
export default function StudioPc() {
  usePageMeta({ title: 'PC 송출 · 스튜디오', noindex: true })
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
  // 방송인도 같은 토픽을 구독한다(서버는 채널 주인의 구독을 시청자 수에 넣지 않는다). viewers는 방송 상세 캐시로 들어가 상태 표시에 반영된다.
  const banner = useDonationBanner()
  const chat = useBroadcastChat(session.broadcastId, { onDonation: banner.push })
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
      <section className="mx-auto max-w-lg rounded-lg border border-border bg-surface p-8 text-center">
        <Heading level={1}>이 브라우저는 화면 공유를 지원하지 않습니다</Heading>
        <p className="mt-2 text-secondary">PC Chrome에서 열거나, 카메라로 방송하는 모바일 화면을 쓰세요.</p>
        <div className="mt-6"><Button href="/studio/mobile" label="모바일 화면으로" variant="primary" /></div>
      </section>
    )
  }
  if (session.channel.isPending) return <Spinner label="채널 정보를 불러오는 중" />
  if (session.channel.isError) return <div className="py-20"><Banner role="alert" status="error" title="채널 정보를 불러오지 못했습니다." /></div>

  const channel = session.channel.data
  const currentInfo = info ?? { title: channel.title, category: channel.category.code as CategoryCode }
  const busy = session.phase === 'starting' || session.phase === 'ending'
  const warnings: (MixerWarning | 'NO_H264')[] = [
    ...(supportsH264Encoding() ? [] : ['NO_H264' as const]),
    ...(media?.warnings.filter((warning) => warning !== 'MIC_UNAVAILABLE' || mic !== null) ?? []),
  ]

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px] lg:items-start">
      <div className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Heading level={1}>PC 스튜디오</Heading>
          <div className="flex gap-1">
            <Button href="/studio/mobile" label="모바일 화면으로" variant="ghost" size="sm" />
            <Button href="/studio/settings" label="채널 설정" variant="ghost" size="sm" />
          </div>
        </div>

        <div className="relative aspect-video overflow-hidden rounded-lg bg-black">
          <video ref={previewRef} muted autoPlay playsInline className="h-full w-full object-contain" />
          {!media && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-slate-400">
              <MonitorUp aria-hidden className="h-10 w-10" />
              <p>화면 공유를 시작하면 미리보기가 나옵니다.</p>
            </div>
          )}
          <div className="pointer-events-none absolute inset-x-3 top-3 z-10 flex justify-center">
            <div className="w-full max-w-md"><DonationBanner donation={banner.current} overlay /></div>
          </div>
        </div>

        <section aria-label="소스" className="space-y-4 rounded-lg border border-border bg-surface p-4">
          <div className="flex flex-wrap items-center gap-3">
            <Button label={media ? '공유 화면 다시 선택' : '화면 공유 시작'} variant={media ? 'secondary' : 'primary'} icon={<MonitorUp aria-hidden className="h-4 w-4" />} isLoading={sharing} isDisabled={active} onClick={() => void share()} />
            {active && <span className="text-xs text-secondary">송출 중에는 공유 화면을 바꿀 수 없습니다. 종료 후 다시 선택하세요.</span>}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <AudioColumn
              title="마이크"
              gainLabel="마이크"
              level={levels.mic}
              select={{
                value: mic ?? 'none',
                onChange: (value) => void changeMic(value),
                options: (
                  <>
                    <option value="">기본 마이크</option>
                    {devices.filter((device) => device.deviceId && device.deviceId !== 'default').map((device, index) => (
                      <option key={device.deviceId} value={device.deviceId}>{device.label || `마이크 ${index + 1}`}</option>
                    ))}
                    <option value="none">마이크 사용 안 함</option>
                  </>
                ),
              }}
              gain={{ value: micGain, onChange: setMicGain, disabled: mic === null }}
            />
            <AudioColumn
              title="시스템 소리"
              gainLabel="시스템 소리"
              level={includeSystem ? levels.system : 0}
              select={{
                value: includeSystem ? 'include' : 'exclude',
                onChange: (value) => setIncludeSystem(value === 'include'),
                options: (
                  <>
                    <option value="include">탭/시스템 소리 포함</option>
                    <option value="exclude">포함 안 함</option>
                  </>
                ),
              }}
              gain={{ value: systemGain, onChange: setSystemGain, disabled: !includeSystem }}
            />
          </div>
          {warnings.map((warning) => <Banner key={warning} role="alert" status="warning" title={WARNING_TEXT[warning]} />)}
          {shareError && <Banner role="alert" status="error" title={shareError} />}
        </section>

        <section aria-label="방송" className="space-y-4 rounded-lg border border-border bg-surface p-4">
          <SessionStatus phase={session.phase} viewerCount={session.viewerCount} />
          <BroadcastInfoForm initial={currentInfo} onChange={setInfo} onSave={session.saveInfo} />
          {active || session.phase === 'ending'
            ? (
              <Button label={session.phase === 'ending' ? '종료하는 중…' : '방송 종료'} variant="destructive" size="lg" width="100%" isLoading={session.phase === 'ending'} onClick={() => void end(stopMedia)} />
            )
            : (
              <Button label="송출 시작" variant="primary" size="lg" width="100%" isDisabled={!media || busy || !currentInfo.title.trim()} onClick={() => media && void session.start(media.stream, { ...currentInfo, title: currentInfo.title.trim() })} />
            )}
          {!media && !active && <p className="text-xs text-secondary">먼저 화면 공유를 시작하세요.</p>}
          {session.phase === 'reconnecting' && <p role="status" className="text-sm text-warning">연결이 끊겨 다시 연결하는 중입니다. 60초 안에 연결되면 방송이 이어집니다.</p>}
          {session.phase === 'ended' && !session.error && <p role="status" className="text-sm text-secondary">방송을 종료했습니다.</p>}
          {session.error && <Banner role="alert" status="error" title={session.error} />}
          {session.broadcastId !== null && (
            <a href={`/live/${channel.id}`} target="_blank" rel="noreferrer" className="block text-sm text-accent hover:underline">시청 화면 새 탭에서 열기</a>
          )}
        </section>
      </div>

      {session.broadcastId !== null
        ? <ChatPanel chat={chat} className={CHAT_COLUMN_CLASS} />
        : <aside aria-label="채팅" className={`flex items-center justify-center rounded-lg border border-border bg-surface p-4 text-sm text-secondary ${CHAT_COLUMN_CLASS}`}>방송을 시작하면 채팅이 열립니다.</aside>}
    </div>
  )
}
