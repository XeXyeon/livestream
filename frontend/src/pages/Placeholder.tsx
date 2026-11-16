import { Construction } from 'lucide-react'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { usePageMeta } from '../components/layout/usePageMeta'

export default function Placeholder({ title }: { title: string }) {
  usePageMeta({ title, noindex: true })
  return (
    <section className="rounded-lg border border-border bg-surface p-8">
      <EmptyState headingLevel={1} title={title} description="이 화면은 준비 중입니다." icon={<Construction aria-hidden className="h-8 w-8" />} />
    </section>
  )
}
