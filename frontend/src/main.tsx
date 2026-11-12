import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Theme } from '@astryxdesign/core/theme'
import './styles/index.css'
import App from './App.tsx'
import { gridliveTheme } from './theme/gridlive'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Theme theme={gridliveTheme} mode="dark">
      <App />
    </Theme>
  </StrictMode>,
)
