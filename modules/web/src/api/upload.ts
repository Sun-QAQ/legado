import ajax from './axios'
import type { LeagdoApiResponse } from './api'

export const uploadBook = (
  file: File,
  signal: AbortSignal,
  onProgress: (percent: number) => void,
) => {
  const form = new FormData()
  form.append('fileName', file.name)
  form.append('fileData', file)
  return ajax.post<LeagdoApiResponse<boolean>>('addLocalBook', form, {
    signal,
    timeout: 0,
    onUploadProgress: event => {
      if (event.total)
        onProgress(Math.round((event.loaded / event.total) * 100))
    },
  })
}
