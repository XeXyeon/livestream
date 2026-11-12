export default function Placeholder({ title }: { title: string }) {
  return (
    <section className="rounded-2xl border border-slate-800 bg-slate-900 p-8">
      <h1 className="text-2xl font-bold">{title}</h1>
      <p className="mt-3 text-slate-400">이 화면은 준비 중입니다.</p>
    </section>
  )
}
