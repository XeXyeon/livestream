import { useEffect, useMemo, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { AlertQueue } from '../components/widget/alertQueue'
import type { WidgetDonation } from '../components/widget/alertQueue'
import { createSpeaker } from '../components/widget/speech'
import { subscribeWidgetEvents } from '../components/widget/widgetEvents'
import type { WidgetStatus } from '../components/widget/widgetEvents'
import { usePageMeta } from '../components/layout/usePageMeta'

const STATUS_TEXT: Record<WidgetStatus, string> = {
  connecting: '연결 중…',
  connected: '연결됨 · 후원을 기다리는 중',
  reconnecting: '연결이 끊겨 다시 연결하는 중…',
  'invalid-key': '',
}

const DEMO_DONATION: WidgetDonation = {
  version: 1,
  type: 'donation',
  id: -1,
  broadcastId: null,
  donor: { nickname: '테스트' },
  amount: 1000,
  message: '알림 위젯 테스트입니다',
  createdAt: new Date(0).toISOString(),
}

/**
 * 알림 위젯(S11 · §6.7): OBS 브라우저 소스에 넣는 투명 배경 화면.
 * 쿼리: `tts=0` 음성 끔, `debug=1` 연결 상태 표시, `demo=1` 열자마자 테스트 알림 한 번(서버 없이 TTS 확인용).
 */
export default function Widget() {
  usePageMeta({ title: '후원 위젯', noindex: true })
  const { widgetKey = '' } = useParams()
  const [searchParams] = useSearchParams()
  const ttsEnabled = searchParams.get('tts') !== '0'
  const debug = searchParams.get('debug') === '1'
  const demo = searchParams.get('demo') === '1'

  const [current, setCurrent] = useState<WidgetDonation | null>(null)
  const [status, setStatus] = useState<WidgetStatus>('connecting')
  const [speechBlocked, setSpeechBlocked] = useState(false)

  const speaker = useMemo(
    () => (ttsEnabled ? createSpeaker({ onBlocked: () => setSpeechBlocked(true) }) : null),
    [ttsEnabled],
  )

  // 투명 배경: OBS 브라우저 소스가 방송 화면 위에 겹친다.
  // 앱 테마가 <html>을 color-scheme: dark로 두면 브라우저가 바탕 캔버스를 어둡게 칠할 수 있어 위젯에서는 normal로 되돌린다.
  useEffect(() => {
    const targets = [document.documentElement, document.body]
    const previous = targets.map((element) => element.style.background)
    const previousScheme = document.documentElement.style.colorScheme
    targets.forEach((element) => { element.style.background = 'transparent' })
    document.documentElement.style.colorScheme = 'normal'
    return () => {
      targets.forEach((element, index) => { element.style.background = previous[index] })
      document.documentElement.style.colorScheme = previousScheme
    }
  }, [])

  useEffect(() => {
    const queue = new AlertQueue({
      show: setCurrent,
      speak: (text) => speaker?.speak(text) ?? Promise.resolve(),
    })
    const unsubscribe = subscribeWidgetEvents(widgetKey, {
      onDonation: (donation) => { queue.push(donation) },
      onStatus: setStatus,
    })
    const demoTimer = demo ? setTimeout(() => queue.push(DEMO_DONATION), 1500) : undefined
    return () => {
      clearTimeout(demoTimer)
      unsubscribe()
      queue.dispose()
      speaker?.cancel()
    }
  }, [widgetKey, speaker, demo])

  // 사용자 동작이 한 번 있어야 읽기를 허용하는 브라우저(일반 Chrome 창)용.
  function unlockSpeech() {
    if (!speechBlocked || !speaker) return
    setSpeechBlocked(false)
    void speaker.speak('음성 알림을 켰습니다')
  }

  if (status === 'invalid-key') {
    return (
      <main className="flex min-h-screen items-center justify-center p-6">
        <p role="alert" className="rounded-xl bg-black/70 px-5 py-4 text-center text-lg text-white">
          위젯 키가 바뀌었습니다. 스튜디오 설정에서 새 위젯 URL을 복사해 다시 넣어 주세요.
        </p>
      </main>
    )
  }

  return (
    <main className="relative min-h-screen select-none overflow-hidden" onClick={unlockSpeech}>
      {current && (
        <div className="flex justify-center p-6" aria-live="polite">
          <div key={current.id} className="max-w-xl rounded-2xl border-2 border-amber-300 bg-slate-900/85 px-8 py-5 text-center text-white shadow-2xl">
            <p className="text-2xl font-bold">
              <span className="text-amber-300">{current.donor.nickname}</span>님이{' '}
              <span className="text-amber-300">{current.amount.toLocaleString('ko-KR')}원</span> 후원!
            </p>
            {current.message && <p className="mt-2 text-xl break-words">{current.message}</p>}
          </div>
        </div>
      )}
      {speechBlocked && (
        <button type="button" onClick={(event) => { event.stopPropagation(); unlockSpeech() }} className="absolute bottom-4 left-4 rounded-lg bg-black/70 px-3 py-2 text-sm text-white">
          화면을 한 번 클릭하면 음성 알림이 켜집니다
        </button>
      )}
      {debug && (
        <p className="absolute bottom-4 right-4 rounded bg-black/70 px-2 py-1 text-xs text-white">
          {STATUS_TEXT[status]}{speaker ? '' : ' · 음성 끔'}{speaker && !speaker.supported ? ' · 음성 미지원' : ''}
        </p>
      )}
    </main>
  )
}
