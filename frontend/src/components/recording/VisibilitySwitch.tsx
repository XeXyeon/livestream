import { useState } from 'react'
import { Switch } from '@astryxdesign/core/Switch'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { updateRecordingVisibility } from '../../api/recordings'
import type { Visibility } from '../../api/types'

/** 다시보기 공개 설정(주인). S6 · S8 공용. 바뀌면 다시보기 · 내 방송 · 목록 캐시를 새로 받는다 */
export default function VisibilitySwitch({ recordingId, visibility, size = 'md' }: { recordingId: number; visibility: Visibility; size?: 'sm' | 'md' }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState('')

  async function change(checked: boolean) {
    setError('')
    try {
      const updated = await updateRecordingVisibility(recordingId, checked ? 'PUBLIC' : 'PRIVATE')
      queryClient.setQueryData(['recording', recordingId], updated)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['me', 'broadcasts'] }),
        queryClient.invalidateQueries({ queryKey: ['recordings'] }),
        queryClient.invalidateQueries({ queryKey: ['channel'] }),
      ])
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '공개 설정을 바꾸지 못했습니다.')
    }
  }

  return (
    <div>
      <Switch
        label="다시보기 공개"
        description={visibility === 'PUBLIC' ? '누구나 볼 수 있습니다' : '나만 볼 수 있습니다'}
        value={visibility === 'PUBLIC'}
        changeAction={change}
        size={size}
      />
      {error && <p role="alert" className="mt-1 text-sm text-error">{error}</p>}
    </div>
  )
}
