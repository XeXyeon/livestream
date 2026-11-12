// 표시용 변환 (§4.5: API 시각은 UTC ISO-8601, 표시 변환은 프론트)

/** 1234 → "1,234", 12345 → "1.2만" */
export function formatCount(count: number): string {
  if (count >= 10_000) return `${(Math.floor(count / 1000) / 10).toLocaleString('ko-KR')}만`
  return count.toLocaleString('ko-KR')
}

/** 초 → "m:ss" 또는 "h:mm:ss" */
export function formatDuration(totalSeconds: number | null | undefined): string {
  if (totalSeconds == null || totalSeconds < 0) return '--:--'
  const seconds = Math.floor(totalSeconds % 60)
  const minutes = Math.floor(totalSeconds / 60) % 60
  const hours = Math.floor(totalSeconds / 3600)
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

/** ISO 시각 → "방금" · "n분 전" · "n시간 전" · "n일 전" · 그 이상은 날짜 */
export function formatRelative(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return ''
  const time = Date.parse(iso)
  if (Number.isNaN(time)) return ''
  const minutes = Math.floor((now - time) / 60_000)
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}일 전`
  return new Date(time).toLocaleDateString('ko-KR')
}
