import { defineStore } from 'pinia'
import {
  emptyBookSource,
  emptyRssSource,
  getSourceUniqueKey,
  convertSourcesToMap,
} from '@utils/souce'
import type { BookSoure, RssSource, Source } from '@/source'
import { sourceMode } from '@/config/sourceContext'

const isBookSource = () => sourceMode.value === 'book'
const emptySource = () => (isBookSource() ? emptyBookSource : emptyRssSource)

export const useSourceStore = defineStore('source', {
  state: () => {
    return {
      bookSources: shallowRef([] as BookSoure[]), // 临时存放所有书源,
      rssSources: shallowRef([] as RssSource[]), // 临时存放所有订阅源
      savedSources: [] as Source[], // 批量保存到阅读app成功的源
      currentSource: JSON.parse(JSON.stringify(emptySource())) as Source, // 当前编辑的源
      currentTab: 'editForm',
      jsonError: '',
      undoHistory: [] as Source[],
      redoHistory: [] as Source[],
      historyReplay: false,
      draftBaseline: JSON.stringify(emptySource()),
      editTabSource: {} as Source, // 生成序列化的json数据
      isDebuging: false,
      editorKind: sourceMode.value,
      drafts: {} as Record<string, { current: Source; json: Source }>,
    }
  },
  getters: {
    isDirty: state =>
      JSON.stringify(state.currentSource) !== state.draftBaseline ||
      !!state.jsonError,
    sources: (state): Source[] =>
      isBookSource() ? state.bookSources : state.rssSources,
    sourcesMap: function (): Map<string, Source> {
      return convertSourcesToMap(this.sources)
    },
    savedSourcesMap: (state): Map<string, Source> =>
      convertSourcesToMap(state.savedSources),
    currentSourceUrl: state =>
      isBookSource()
        ? (state.currentSource as BookSoure).bookSourceUrl
        : (state.currentSource as RssSource).sourceUrl,
    searchKey: (state): string =>
      isBookSource()
        ? (state.currentSource as BookSoure)?.ruleSearch?.checkKeyWord || '我的'
        : '',
  },
  actions: {
    selectType() {
      if (this.editorKind === sourceMode.value) return
      this.drafts[this.editorKind] = {
        current: this.currentSource,
        json: this.editTabSource,
      }
      this.clearAllHistory()
      this.editorKind = sourceMode.value
      const draft = this.drafts[this.editorKind]
      this.currentSource =
        draft?.current || JSON.parse(JSON.stringify(emptySource()))
      this.editTabSource = draft?.json || ({} as Source)
      this.savedSources = []
      this.isDebuging = false
      this.draftBaseline = JSON.stringify(this.currentSource)
      this.currentTab = 'editForm'
      this.jsonError = ''
    },
    startDebug() {
      this.currentTab = 'editDebug'
      this.isDebuging = true
    },
    debugFinish() {
      this.isDebuging = false
    },

    //拉取源后保存
    saveSources(data: Source[]) {
      if (isBookSource()) {
        this.bookSources = markRaw(data) as BookSoure[]
      } else {
        this.rssSources = markRaw(data) as RssSource[]
      }
    },
    //批量推送
    setPushReturnSources(returnSoures: Source[]) {
      this.savedSources = returnSoures
    },
    //删除源
    deleteSources(data: Source[]) {
      const keys = new Set(data.map(getSourceUniqueKey))
      this.saveSources(
        this.sources.filter(source => !keys.has(getSourceUniqueKey(source))),
      )
    },
    //保存当前编辑源
    saveCurrentSource() {
      const source = this.currentSource,
        map = this.sourcesMap
      map.set(getSourceUniqueKey(source), JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
      this.draftBaseline = JSON.stringify(this.currentSource)
    },
    // 更改当前编辑的源qq
    changeCurrentSource(source: Source, resetBaseline = true) {
      this.currentSource = Object.assign(
        JSON.parse(JSON.stringify(emptySource())),
        JSON.parse(JSON.stringify(source)),
      )
      if (resetBaseline) {
        this.draftBaseline = JSON.stringify(this.currentSource)
        this.clearAllHistory()
      }
      this.jsonError = ''
    },
    // update editTab tabName and editTab info
    changeTabName(tabName: string) {
      this.currentTab = tabName
      localStorage.setItem('tabName', tabName)
    },
    changeEditTabSource(source: Source) {
      this.editTabSource = JSON.parse(JSON.stringify(source))
    },
    recordHistory(previous: string) {
      if (this.historyReplay) return
      this.undoHistory.push(JSON.parse(previous))
      if (this.undoHistory.length > 50) this.undoHistory.shift()
      this.redoHistory = []
    },
    editHistoryUndo() {
      const previous = this.undoHistory.pop()
      if (!previous) return
      this.historyReplay = true
      this.redoHistory.push(JSON.parse(JSON.stringify(this.currentSource)))
      this.currentSource = previous
      this.jsonError = ''
      this.historyReplay = false
    },
    editHistoryRedo() {
      const next = this.redoHistory.pop()
      if (!next) return
      this.historyReplay = true
      this.undoHistory.push(JSON.parse(JSON.stringify(this.currentSource)))
      this.currentSource = next
      this.jsonError = ''
      this.historyReplay = false
    },
    clearAllHistory() {
      this.undoHistory = []
      this.redoHistory = []
    },
    clearEdit() {
      this.editTabSource = {} as Source
      this.currentSource = JSON.parse(JSON.stringify(emptySource())) //复制一份新对象
    },

    // clear all source
    clearAllSource() {
      if (isBookSource()) this.bookSources = []
      else this.rssSources = []
      this.savedSources = []
    },
  },
})
