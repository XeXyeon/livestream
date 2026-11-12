import { describe, expect, it } from 'vitest'
import { isMobileDevice, studioTarget } from './devices'

const nav = (userAgent: string, displayMedia: boolean) =>
  ({ userAgent, mediaDevices: displayMedia ? { getDisplayMedia: () => undefined } : {} }) as unknown as Navigator

const MAC_CHROME = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130 Safari/537.36'
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1'
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36'

describe('기기 감지', () => {
  it('PC Chrome은 PC, 휴대폰 UA나 getDisplayMedia가 없으면 모바일', () => {
    expect(isMobileDevice(nav(MAC_CHROME, true))).toBe(false)
    expect(isMobileDevice(nav(IPHONE, false))).toBe(true)
    expect(isMobileDevice(nav(ANDROID, true))).toBe(true)
    expect(isMobileDevice(nav(MAC_CHROME, false))).toBe(true)
  })

  it('?force=가 감지보다 우선한다', () => {
    expect(studioTarget('pc', nav(IPHONE, false))).toBe('pc')
    expect(studioTarget('mobile', nav(MAC_CHROME, true))).toBe('mobile')
    expect(studioTarget('etc', nav(MAC_CHROME, true))).toBe('pc')
    expect(studioTarget(null, nav(ANDROID, true))).toBe('mobile')
  })
})
