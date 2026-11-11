// 알림 위젯 음성 읽기(Web Speech API `speechSynthesis`, 새 의존성 없음 · §6.7 S11 · §8 TTS 위험).

type SpeakerOptions = {
  /** 브라우저가 사용자 동작 전 읽기를 막았을 때(Chrome `not-allowed`) */
  onBlocked?: () => void
}

export type Speaker = {
  readonly supported: boolean
  speak: (text: string) => Promise<void>
  cancel: () => void
}

/** 한국어 음성을 고른다. 없으면 null(브라우저 기본 음성). */
export function pickKoreanVoice(voices: readonly SpeechSynthesisVoice[]): SpeechSynthesisVoice | null {
  const korean = voices.filter((voice) => voice.lang.toLowerCase().replace('_', '-').startsWith('ko'))
  return korean.find((voice) => voice.localService) ?? korean[0] ?? null
}

/** 읽기가 끝났다는 이벤트가 오지 않는 브라우저(Chrome 일부)를 위한 상한 */
export function speechTimeoutMs(text: string): number {
  return Math.max(8000, text.length * 300)
}

export function createSpeaker(options: SpeakerOptions = {}, synth: SpeechSynthesis | undefined = globalThis.speechSynthesis): Speaker {
  if (!synth || typeof SpeechSynthesisUtterance === 'undefined') {
    return { supported: false, speak: () => Promise.resolve(), cancel: () => undefined }
  }
  let voice = pickKoreanVoice(synth.getVoices())
  // Chrome은 음성 목록을 늦게 채운다.
  synth.addEventListener?.('voiceschanged', () => { voice = pickKoreanVoice(synth.getVoices()) })

  return {
    supported: true,
    speak(text) {
      return new Promise<void>((resolve) => {
        const timer = setTimeout(() => {
          synth.cancel()
          done()
        }, speechTimeoutMs(text))
        const done = () => {
          clearTimeout(timer)
          resolve()
        }
        const utterance = new SpeechSynthesisUtterance(text)
        utterance.lang = 'ko-KR'
        if (voice) utterance.voice = voice
        utterance.onend = done
        utterance.onerror = (event) => {
          if (event.error === 'not-allowed') options.onBlocked?.()
          done()
        }
        synth.speak(utterance)
      })
    },
    cancel() {
      synth.cancel()
    },
  }
}
