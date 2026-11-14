import js from '@eslint/js'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  // src/theme/gridlive.*(.css · .js · .d.ts)은 `npm run theme:build`가 만드는 생성물이다. 원본은 gridliveTheme.ts
  { ignores: ['dist', 'src/theme/gridlive.*'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
)
