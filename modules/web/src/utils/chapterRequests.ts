export const createChapterRequests = () => {
  const active = new Map<string, AbortController>()
  let generation = 0
  let disposed = false
  const reset = () => {
    generation++
    active.forEach(controller => controller.abort())
    active.clear()
  }
  return {
    reset,
    get pending() {
      return active.size
    },
    dispose() {
      disposed = true
      reset()
    },
    start(bookUrl: string, index: number, replace: boolean) {
      if (disposed) return
      if (replace) reset()
      const key = JSON.stringify([bookUrl, index])
      if (active.has(key)) return
      const controller = new AbortController()
      const revision = generation
      active.set(key, controller)
      return {
        signal: controller.signal,
        isCurrent: () =>
          !disposed && revision === generation && !controller.signal.aborted,
        finish: () => {
          if (active.get(key) === controller) active.delete(key)
        },
      }
    },
  }
}
