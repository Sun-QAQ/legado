export type webReadConfig = {
  /** App 下发的只读强调色，不属于 Web 阅读设置。 */
  appAccentColor?: string
  theme: number
  font: number
  fontSize: number
  readWidth: number
  infiniteLoading: boolean
  customFontName: string
  jumpDuration: number
  spacing: {
    paragraph: number
    line: number
    letter: number
  }
}
