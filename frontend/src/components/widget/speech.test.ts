import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SPEECH_START_TIMEOUT_MS, createSpeaker, pickKoreanVoice, speechTimeoutMs } from './speech'

const voice = (lang: string, localService = false, name = lang) => ({ lang, localService, name }) as SpeechSynthesisVoice

class FakeUtterance {
  text: string
  lang = ''
  voice: SpeechSynthesisVoice | null = null
  onend: (() => void) | null = null
  onstart: (() => void) | null = null
  onerror: ((event: { error: string }) => void) | null = null
  constructor(text: string) { this.text = text }
}

describe('pickKoreanVoice', () => {
  it('한국어 음성 중 기기 음성을 먼저, 없으면 null', () => {
    expect(pickKoreanVoice([voice('en-US'), voice('ko-KR', false, 'net'), voice('ko_KR', true, 'local')])?.name).toBe('local')
    expect(pickKoreanVoice([voice('en-US')])).toBeNull()
  })
})

describe('createSpeaker', () => {
  let spoken: FakeUtterance[]
  let synth: { getVoices: () => SpeechSynthesisVoice[]; speak: (u: FakeUtterance) => void; cancel: ReturnType<typeof vi.fn>; addEventListener: () => void }

  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('SpeechSynthesisUtterance', FakeUtterance)
    spoken = []
    synth = { getVoices: () => [voice('ko-KR')], speak: (u) => spoken.push(u), cancel: vi.fn(), addEventListener: () => undefined }
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('ko-KR로 읽고 onend에서 끝난다', async () => {
    const speaker = createSpeaker({}, synth as unknown as SpeechSynthesis)
    let done = false
    void speaker.speak('안녕').then(() => { done = true })
    expect(spoken[0].lang).toBe('ko-KR')
    expect(spoken[0].voice?.lang).toBe('ko-KR')
    spoken[0].onend?.()
    await vi.advanceTimersByTimeAsync(0)
    expect(done).toBe(true)
  })

  it('브라우저가 막으면(not-allowed) onBlocked를 부르고, 끝 이벤트가 없어도 상한 시간 뒤 끝낸다', async () => {
    const onBlocked = vi.fn()
    const speaker = createSpeaker({ onBlocked }, synth as unknown as SpeechSynthesis)
    void speaker.speak('a')
    spoken[0].onerror?.({ error: 'not-allowed' })
    expect(onBlocked).toHaveBeenCalled()

    let done = false
    void speaker.speak('b').then(() => { done = true })
    await vi.advanceTimersByTimeAsync(speechTimeoutMs('b'))
    expect(done).toBe(true)
    expect(synth.cancel).toHaveBeenCalled()
  })

  it('speechSynthesis가 없으면 지원 안 함으로 바로 끝낸다', async () => {
    const speaker = createSpeaker({}, undefined)
    expect(speaker.supported).toBe(false)
    await expect(speaker.speak('x')).resolves.toBeUndefined()
  })

  it('읽기가 시작되지 않으면(음성 엔진 없음) 3초 뒤 포기하고, 시작했으면 끝까지 기다린다', async () => {
    const speaker = createSpeaker({}, synth as unknown as SpeechSynthesis)
    let first = false
    void speaker.speak('a').then(() => { first = true })
    await vi.advanceTimersByTimeAsync(SPEECH_START_TIMEOUT_MS)
    expect(first).toBe(true)

    let second = false
    void speaker.speak('b').then(() => { second = true })
    spoken[1].onstart?.()
    await vi.advanceTimersByTimeAsync(SPEECH_START_TIMEOUT_MS)
    expect(second).toBe(false)
    spoken[1].onend?.()
    await vi.advanceTimersByTimeAsync(0)
    expect(second).toBe(true)
  })
})
