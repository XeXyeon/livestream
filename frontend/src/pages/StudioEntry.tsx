import { Navigate, useSearchParams } from 'react-router-dom'
import { studioTarget } from '../media/devices'
import { usePageMeta } from '../components/layout/usePageMeta'

// /studio: ?force=pc|mobile 우선, 아니면 기기 감지로 PC · 모바일 스튜디오로 replace
export default function StudioEntry() {
  usePageMeta({ title: '스튜디오', noindex: true })
  const [params] = useSearchParams()
  return <Navigate to={`/studio/${studioTarget(params.get('force'))}`} replace />
}
