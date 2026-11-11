import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { bootstrapAuth, shouldRetryQuery } from './api/client'
import Header from './components/layout/Header'
import Protected from './components/layout/Protected'
import Home from './pages/Home'
import Live from './pages/Live'
import Login from './pages/Login'
import Signup from './pages/Signup'
import StudioEntry from './pages/StudioEntry'
import StudioMobile from './pages/StudioMobile'
import StudioPc from './pages/StudioPc'
import StudioSettings from './pages/StudioSettings'
import Placeholder from './pages/Placeholder'

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: shouldRetryQuery } } })

function AppRoutes() {
  useEffect(() => { void bootstrapAuth() }, [])
  return (
    <Routes>
      <Route path="/widget/:widgetKey" element={<Placeholder title="알림 위젯" />} />
      <Route element={<Header />}>
        <Route path="/" element={<Home />} />
        <Route path="/live/:channelId" element={<Live />} />
        <Route path="/videos/:recordingId" element={<Placeholder title="다시보기" />} />
        <Route path="/channel/:channelId" element={<Placeholder title="채널" />} />
        <Route path="/posts/:postId" element={<Placeholder title="커뮤니티 글" />} />
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<Signup />} />
        <Route element={<Protected />}>
          <Route path="/studio" element={<StudioEntry />} />
          <Route path="/studio/pc" element={<StudioPc />} />
          <Route path="/studio/mobile" element={<StudioMobile />} />
          <Route path="/studio/settings" element={<StudioSettings />} />
          <Route path="/me" element={<Placeholder title="마이페이지" />} />
        </Route>
        <Route path="*" element={<Placeholder title="페이지를 찾을 수 없습니다" />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return <QueryClientProvider client={queryClient}><BrowserRouter><AppRoutes /></BrowserRouter></QueryClientProvider>
}
