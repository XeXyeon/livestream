import { apiFetch, toQueryString } from './client'
import type { Page, RecordingSummary } from './types'

export const listRecordings = (page = 0, size = 20) =>
  apiFetch<Page<RecordingSummary>>(`/recordings${toQueryString({ page, size, sort: 'recent' })}`)
