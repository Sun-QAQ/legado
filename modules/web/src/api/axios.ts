import axios from 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 后台同步失败时保留界面状态，不提示连接异常。 */
    backgroundSync?: boolean
  }
}

/** @type {string} localStorage保存自定义阅读http服务接口的键值 */
export const baseURL_localStorage_key = 'remoteUrl'
const SECOND = 1000

const ajax = axios.create({
  baseURL:
    import.meta.env.VITE_API ||
    localStorage.getItem(baseURL_localStorage_key) ||
    location.origin,
  timeout: 120 * SECOND,
})

export default ajax
