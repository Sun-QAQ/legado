const storageKey = (server: string) =>
  'legado-web-access:' + new URL(server, location.href).origin

export const getWebAccessKey = (server: string) =>
  sessionStorage.getItem(storageKey(server)) || ''

export const saveWebAccessKey = (server: string, key: string) => {
  if (key) sessionStorage.setItem(storageKey(server), key)
  else sessionStorage.removeItem(storageKey(server))
}

export const withWebAccessKey = (url: string, server: string) => {
  const result = new URL(url)
  const key = getWebAccessKey(server)
  if (key) result.searchParams.set('access_token', key)
  else result.searchParams.delete('access_token')
  return result.toString()
}
