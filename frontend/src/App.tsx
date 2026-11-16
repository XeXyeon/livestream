import { lazy, Suspense, useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { bootstrapAuth, shouldRetryQuery } from './api/client'
import Header from './components/layout/Header'
import Protected from './components/layout/Protected'
import Home from './pages/Home'
import Search from './pages/Search'
import Login from './pages/Login'
import Signup from './pages/Signup'
import Placeholder from './pages/Placeholder'

// 무거운 화면(플레이어 · 송출 · 위젯)은 따로 받는다. 대기 표시는 Header의 Suspense가 맡는다.
const Live = lazy(() => import('./pages/Live'))
const LiveChatPopout = lazy(() => import('./pages/LiveChatPopout'))
const StudioEntry = lazy(() => import('./pages/StudioEntry'))
const StudioPc = lazy(() => import('./pages/StudioPc'))
const StudioMobile = lazy(() => import('./pages/StudioMobile'))
const StudioSettings = lazy(() => import('./pages/StudioSettings'))
const Widget = lazy(() => import('./pages/Widget'))
const Me = lazy(() => import('./pages/Me'))
const Video = lazy(() => import('./pages/Video'))
const Channel = lazy(() => import('./pages/Channel'))
const Post = lazy(() => import('./pages/Post'))
const Clip = lazy(() => import('./pages/Clip'))

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: shouldRetryQuery } } })

function AppRoutes() {
  useEffect(() => { void bootstrapAuth() }, [])
  return (
    <Routes>
      <Route path="/widget/:widgetKey" element={<Suspense fallback={null}><Widget /></Suspense>} />
      {/* 시청 화면 채팅 새 창: 헤더 셸 밖 */}
      <Route path="/live/:channelId/chat" element={<Suspense fallback={null}><LiveChatPopout /></Suspense>} />
      <Route element={<Header />}>
        <Route path="/" element={<Home />} />
        <Route path="/search" element={<Search />} />
        <Route path="/live/:channelId" element={<Live />} />
        <Route path="/videos/:recordingId" element={<Video />} />
        <Route path="/channel/:channelId" element={<Channel />} />
        <Route path="/posts/:postId" element={<Post />} />
        <Route path="/clips/:clipId" element={<Clip />} />
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<Signup />} />
        <Route element={<Protected />}>
          <Route path="/studio" element={<StudioEntry />} />
          <Route path="/studio/pc" element={<StudioPc />} />
          <Route path="/studio/mobile" element={<StudioMobile />} />
          <Route path="/studio/settings" element={<StudioSettings />} />
          <Route path="/me" element={<Me />} />
        </Route>
        <Route path="*" element={<Placeholder title="페이지를 찾을 수 없습니다" />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return <QueryClientProvider client={queryClient}><BrowserRouter><AppRoutes /></BrowserRouter></QueryClientProvider>
}
