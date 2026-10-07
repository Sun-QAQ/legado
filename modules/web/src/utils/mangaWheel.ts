/** 一段连续滚动只翻一页，忽略触控板惯性产生的后续事件。 */
export const createMangaWheelTurner = () => {
  let lastEvent = -Infinity
  let lastTurn = -Infinity
  let distance = 0
  let turned = false
  return (delta: number, now: number): -1 | 0 | 1 => {
    if (now - lastEvent > 180 || Math.sign(delta) !== Math.sign(distance)) {
      distance = 0
      turned = false
    }
    lastEvent = now
    distance += delta
    if (turned || now - lastTurn < 300 || Math.abs(distance) < 24) return 0
    turned = true
    lastTurn = now
    return distance > 0 ? 1 : -1
  }
}
