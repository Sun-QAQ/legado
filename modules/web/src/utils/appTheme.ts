/** 将 App 强调色映射为 Web 与 Element Plus 的深浅主题色阶。 */
export function appAccentTokens(accent: string | undefined, night: boolean) {
  if (!accent || !/^#[\da-f]{6}$/i.test(accent)) return {}
  const rgb = [1, 3, 5].map(index =>
    parseInt(accent.slice(index, index + 2), 16),
  )
  const mix = (background: number[], weight: number) =>
    '#' +
    rgb
      .map((channel, index) =>
        Math.round(channel * (1 - weight) + background[index] * weight)
          .toString(16)
          .padStart(2, '0'),
      )
      .join('')
  const background = night ? [30, 28, 38] : [255, 255, 255]
  const luminance = rgb.reduce((total, channel, index) => {
    const value = channel / 255
    const linear =
      value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
    return total + linear * [0.2126, 0.7152, 0.0722][index]
  }, 0)
  const tokens: Record<string, string> = {
    '--web-primary': accent,
    '--web-tint': mix(background, 0.9),
    '--web-on-primary': luminance > 0.179 ? '#000000' : '#ffffff',
    '--el-color-primary': accent,
    '--el-color-primary-dark-2': mix(night ? [255, 255, 255] : [0, 0, 0], 0.2),
  }
  for (const level of [3, 5, 7, 8, 9]) {
    tokens[`--el-color-primary-light-${level}`] = mix(background, level / 10)
  }
  return tokens
}
