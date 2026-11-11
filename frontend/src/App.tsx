import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { bootstrapAuth } from './api/client'
import Header from './components/layout/Header'
import Protected from './components/layout/Protected'
import Home from './pages/Home'
import Live from './pages/Live'
import Login from './pages/Login'
import Signup from './pages/Signup'
import Placeholder from './pages/Placeholder'

const queryClient = new QueryClient()

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
          <Route path="/studio" element={<Placeholder title="방송하기" />} />
          <Route path="/studio/pc" element={<Placeholder title="PC 스튜디오" />} />
          <Route path="/studio/mobile" element={<Placeholder title="모바일 스튜디오" />} />
          <Route path="/studio/settings" element={<Placeholder title="채널 설정" />} />
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
