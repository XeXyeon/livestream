import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { IconButton } from '@astryxdesign/core/IconButton'
import { Search, X } from 'lucide-react'
import { inputClassName } from '../common/TextField'
import { readSearchParams, SEARCH_MAX_LENGTH, searchPath } from './searchQuery'

/**
 * 헤더 가운데의 통합 검색창(§6.7). 제출(Enter · 돋보기)하면 /search?q= 로 간다. /search에서는 주소의 q를 입력창에 맞추고,
 * 다른 페이지로 가면 비운다. sm 미만에서는 돋보기 버튼만 두고, 누르면 헤더 한 줄을 덮는 입력창(자동 포커스 + 닫기)이 열린다.
 * 헤더(sticky)가 위치 기준이다.
 */
export default function SearchBox() {
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const [value, setValue] = useState(() => (pathname === '/search' ? readSearchParams(new URLSearchParams(search)).q : ''))
  const [open, setOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  // 주소가 바뀌면(뒤로 가기 포함) 입력창을 주소에 맞추고 휴대폰 입력창은 닫는다
  const location = `${pathname}${search}`
  const [seenLocation, setSeenLocation] = useState(location)
  if (seenLocation !== location) {
    setSeenLocation(location)
    setValue(pathname === '/search' ? readSearchParams(new URLSearchParams(search)).q : '')
    setOpen(false)
  }

  useEffect(() => {
    if (open) inputRef.current?.focus()
  }, [open])

  function submit(event: FormEvent) {
    event.preventDefault()
    const path = searchPath(value)
    if (path === null) return
    setOpen(false)
    void navigate(path)
  }

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') setOpen(false)
  }

  return (
    <>
      {!open && (
        <div className="sm:hidden">
          <IconButton label="검색" icon={<Search aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={() => setOpen(true)} />
        </div>
      )}
      <form
        role="search"
        onSubmit={submit}
        className={`${open
          ? 'absolute inset-x-0 top-0 z-40 flex h-14 items-center gap-2 bg-body px-3'
          : 'hidden'} sm:static sm:z-auto sm:flex sm:h-auto sm:min-w-0 sm:max-w-md sm:flex-1 sm:bg-transparent sm:px-0`}
      >
        <input
          ref={inputRef}
          type="search"
          value={value}
          maxLength={SEARCH_MAX_LENGTH}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="채널 · 라이브 · 다시보기 검색"
          aria-label="검색어"
          autoComplete="off"
          enterKeyHint="search"
          className={`${inputClassName} min-w-0 flex-1 !py-1.5 text-sm`}
        />
        <IconButton type="submit" label="검색" icon={<Search aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" />
        {open && (
          <div className="sm:hidden">
            <IconButton label="검색 닫기" icon={<X aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={() => setOpen(false)} />
          </div>
        )}
      </form>
    </>
  )
}
