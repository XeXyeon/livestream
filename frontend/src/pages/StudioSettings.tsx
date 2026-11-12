import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { getMyChannel, rotateStreamKey, rotateWidgetKey, updateMyChannel } from '../api/channels'
import { ApiError } from '../api/client'
import type { ChannelUpdateInput, MyChannelResponse } from '../api/types'
import CopyField from '../components/common/CopyField'
import Spinner from '../components/common/Spinner'
import TextField, { inputClassName } from '../components/common/TextField'
import { MY_CHANNEL_KEY } from '../components/studio/useBroadcastSession'

const errorText = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)
const card = 'space-y-4 rounded-lg border border-border bg-surface p-5'

// S5 채널 설정: 이름 · 소개, 스트림 키(복사 · 재발급), OBS 안내(WHIP · RTMP), 위젯 URL(복사 · 재발급), 다시보기 자동 공개
export default function StudioSettings() {
  const queryClient = useQueryClient()
  const channel = useQuery({ queryKey: MY_CHANNEL_KEY, queryFn: getMyChannel })
  const refresh = () => queryClient.invalidateQueries({ queryKey: MY_CHANNEL_KEY })

  const update = useMutation({
    mutationFn: (input: ChannelUpdateInput) => updateMyChannel(input),
    onSuccess: (data) => queryClient.setQueryData(MY_CHANNEL_KEY, data),
  })
  const streamKey = useMutation({ mutationFn: rotateStreamKey, onSuccess: () => refresh() })
  const widgetKey = useMutation({ mutationFn: rotateWidgetKey, onSuccess: () => refresh() })

  if (channel.isPending) return <Spinner label="채널 정보를 불러오는 중" />
  if (channel.isError) return <div className="py-20"><Banner role="alert" status="error" title="채널 정보를 불러오지 못했습니다." /></div>
  const data = channel.data
  const live = data.currentBroadcast !== null

  function confirmStreamKey() {
    const message = live
      ? '지금 방송 중입니다. 스트림 키를 재발급하면 방송이 바로 종료되고, 기존 키로는 송출할 수 없습니다. 재발급할까요?'
      : '스트림 키를 재발급하면 OBS 등에 넣어 둔 기존 키로는 송출할 수 없습니다. 재발급할까요?'
    if (window.confirm(message)) streamKey.mutate()
  }

  function confirmWidgetKey() {
    if (window.confirm('위젯 URL을 재발급하면 OBS 브라우저 소스에 넣어 둔 기존 URL이 끊깁니다. 재발급할까요?')) widgetKey.mutate()
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Heading level={1}>채널 설정</Heading>
        <div className="flex gap-1">
          <Button href="/studio" label="스튜디오로" variant="ghost" size="sm" />
          <Button href={`/channel/${data.id}`} label="내 채널 보기" variant="ghost" size="sm" />
        </div>
      </div>

      <ChannelInfoForm key={data.id} channel={data} onSave={(input) => update.mutateAsync(input)} />

      <section className={card}>
        <Heading level={2}>다시보기</Heading>
        <label className="flex items-start gap-3">
          <input type="checkbox" checked={data.autoPublishRecording} disabled={update.isPending} onChange={(event) => update.mutate({ autoPublishRecording: event.target.checked })} className="mt-1 h-4 w-4 accent-accent-bg" />
          <span>
            <span className="block font-medium">방송이 끝나면 다시보기를 자동으로 공개</span>
            <span className="block text-sm text-secondary">끄면 다시보기가 비공개로 저장되고, 마이페이지에서 공개로 바꿀 수 있습니다. 다음 방송부터 적용됩니다.</span>
          </span>
        </label>
        {update.isError && <Banner role="alert" status="error" title={errorText(update.error, '저장하지 못했습니다.')} />}
      </section>

      <section className={card}>
        <Heading level={2}>스트림 키</Heading>
        <p className="text-sm text-secondary">스트림 키를 아는 사람은 내 채널로 방송할 수 있습니다. 다른 사람에게 보여 주지 마세요.</p>
        <CopyField label="스트림 키" value={data.streamKey} secret />
        <Button label={streamKey.isPending ? '재발급 중…' : '스트림 키 재발급'} variant="destructive" isLoading={streamKey.isPending} onClick={confirmStreamKey} />
        {streamKey.isSuccess && <Banner role="status" status="success" title="새 스트림 키를 발급했습니다. OBS 설정도 바꿔 주세요." />}
        {streamKey.isError && <Banner role="alert" status="error" title={errorText(streamKey.error, '재발급하지 못했습니다.')} />}
      </section>

      <section className={card}>
        <Heading level={2}>OBS로 방송하기</Heading>
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-primary">WHIP (OBS 30 이상, 권장)</h3>
          <p className="text-xs text-secondary">설정 → 방송 → 서비스 “WHIP”</p>
          <CopyField label="서버" value={data.publish.obsWhipUrl} />
          <CopyField label="Bearer 토큰" value={data.publish.obsWhipBearer} secret />
        </div>
        <div className="space-y-3 border-t border-border pt-4">
          <h3 className="text-sm font-semibold text-primary">RTMP</h3>
          <p className="text-xs text-secondary">설정 → 방송 → 서비스 “사용자 지정”</p>
          <CopyField label="서버 (RTMP)" value={data.publish.rtmpUrl} />
          <CopyField label="스트림 키 (RTMP)" value={data.publish.rtmpStreamKey} secret />
        </div>
        <ul className="list-disc space-y-1 pl-5 text-xs text-secondary">
          <li>출력 → 키프레임 간격을 <strong className="text-primary">1초</strong>로 두면 시청 지연이 줄어듭니다.</li>
          <li>사이트에서 방송을 종료한 뒤에는 OBS도 “방송 중단”을 눌러 주세요. 30초가 지나도 OBS가 계속 재연결하면 새 방송이 시작됩니다.</li>
        </ul>
      </section>

      <section className={card}>
        <Heading level={2}>후원 알림 위젯</Heading>
        <p className="text-sm text-secondary">OBS “브라우저 소스”에 이 URL을 넣으면 후원 알림이 방송 화면에 뜹니다.</p>
        <CopyField label="위젯 URL" value={data.widgetUrl} secret />
        <ul className="list-disc space-y-1 pl-5 text-sm text-secondary">
          <li>음성 알림을 방송 소리에 섞으려면 브라우저 소스의 “OBS를 통해 오디오 제어”를 켜 주세요.</li>
          <li>카드는 뜨는데 소리가 나지 않으면 이 URL을 일반 Chrome 창으로 열고 화면을 한 번 클릭한 뒤, OBS에서 그 창을 “윈도우 캡처”와 데스크톱 오디오로 잡아 주세요.</li>
        </ul>
        <Button label={widgetKey.isPending ? '재발급 중…' : '위젯 URL 재발급'} variant="secondary" isLoading={widgetKey.isPending} onClick={confirmWidgetKey} />
        {widgetKey.isError && <Banner role="alert" status="error" title={errorText(widgetKey.error, '재발급하지 못했습니다.')} />}
      </section>
    </div>
  )
}

function ChannelInfoForm({ channel, onSave }: { channel: MyChannelResponse; onSave: (input: ChannelUpdateInput) => Promise<unknown> }) {
  const [name, setName] = useState(channel.name)
  const [description, setDescription] = useState(channel.description ?? '')
  const [status, setStatus] = useState<{ ok: boolean; text: string } | null>(null)
  const [saving, setSaving] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    setStatus(null)
    try {
      await onSave({ name: name.trim(), description })
      setStatus({ ok: true, text: '저장했습니다.' })
    } catch (cause) {
      setStatus({ ok: false, text: errorText(cause, '저장하지 못했습니다.') })
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={(event) => void handleSubmit(event)} className={card}>
      <Heading level={2}>채널 정보</Heading>
      <TextField label="채널 이름" required maxLength={30} value={name} onChange={(event) => setName(event.target.value)} />
      <label className="block text-sm font-medium text-primary">채널 소개
        <textarea maxLength={500} rows={4} value={description} onChange={(event) => setDescription(event.target.value)} className={`mt-2 ${inputClassName}`} />
        <span className="mt-1 block text-right text-xs text-secondary">{description.length}/500</span>
      </label>
      {status && <Banner role={status.ok ? 'status' : 'alert'} status={status.ok ? 'success' : 'error'} title={status.text} />}
      <Button type="submit" label={saving ? '저장 중…' : '저장'} variant="primary" isLoading={saving} isDisabled={!name.trim()} />
    </form>
  )
}
