import { defineTheme } from '@astryxdesign/core/theme'
import { neutralTheme } from '@astryxdesign/theme-neutral/built'

// 어둠 고정(index.html의 data-theme="dark") 방송 서비스 테마. neutral 위에 인디고 강조색만 얹는다.
// 값은 [밝음, 어둠] 순서다. 바꾼 뒤에는 `npm run theme:build`로 gridlive.css · gridlive.js를 다시 만든다.
export const gridliveTheme = defineTheme({
  name: 'gridlive',
  extends: neutralTheme,
  tokens: {
    '--color-accent': ['#4f46e5', '#6366f1'],
    '--color-on-accent': ['#ffffff', '#ffffff'],
    '--color-accent-muted': ['#e0e7ff', '#312e81'],
    '--color-text-accent': ['#4338ca', '#a5b4fc'],
  },
})
